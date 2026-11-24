package com.mealforge.lightphone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GroceryState(
    val plan: ActivePlan? = null,
    val items: List<GroceryItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val pendingItemIds: Set<Int> = emptySet(),
    val adding: Boolean = false,
    val isStale: Boolean = false,
)

class GroceryStore(
    private val client: MealforgeClient,
    private val scope: CoroutineScope,
    initial: GroceryState = GroceryState(),
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(initial.copy(isStale = initial.plan != null))
    val state: StateFlow<GroceryState> = mutableState.asStateFlow()
    private val jobs = mutableSetOf<Job>()
    private var generation = 0L
    private var stopped = true

    fun refresh(): Job = synchronized(lock) {
        generation++
        cancelRequests()
        stopped = false
        val token = generation
        mutableState.value = state.value.copy(
            loading = true, error = null, pendingItemIds = emptySet(), adding = false,
            isStale = state.value.plan != null,
        )
        launchRequest {
            try {
                val plan = client.activePlan()
                check(plan == null || (plan.planId > 0 && plan.status == "active")) {
                    "MealForge returned an invalid active plan."
                }
                val items = if (plan == null) emptyList() else client.groceryItems(plan.planId)
                if (plan != null) validateItems(items, plan.planId)
                publish(token) { GroceryState(plan = plan, items = items) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publish(token) { it.copy(loading = false, error = error.message ?: "Could not load groceries.", isStale = it.plan != null) }
            }
        }
    }

    fun setChecked(itemId: Int, checked: Boolean): Job? = synchronized(lock) {
        if (state.value.items.none { it.id == itemId }) return@synchronized null
        mutate(itemId) { client.setChecked(itemId, checked) }
    }

    fun addManualItem(name: String, quantityText: String?, section: String?): Job? = synchronized(lock) {
        if (name.isBlank()) {
            mutableState.value = state.value.copy(error = "Enter an item name.")
            return@synchronized null
        }
        mutate(null) { planId -> client.addManualItem(planId, name, quantityText, section) }
    }

    fun removeManualItem(itemId: Int): Job? = synchronized(lock) {
        val item = state.value.items.find { it.id == itemId } ?: return@synchronized null
        if (!item.isManual || item.checked) return@synchronized null
        mutate(itemId, remove = true) {
            client.removeManualItem(itemId)
            null
        }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            generation++
            cancelRequests()
            mutableState.value = state.value.copy(
                loading = false, adding = false, pendingItemIds = emptySet(),
                isStale = state.value.plan != null,
            )
        }
    }

    private fun mutate(
        itemId: Int?,
        remove: Boolean = false,
        request: suspend (Int) -> GroceryItem?,
    ): Job? {
        val current = state.value
        val plan = current.plan ?: return null
        if (stopped || current.loading || current.isStale ||
            (itemId == null && current.adding) || (itemId != null && itemId in current.pendingItemIds)) return null
        val token = generation
        mutableState.value = current.copy(
            error = null,
            adding = current.adding || itemId == null,
            pendingItemIds = if (itemId == null) current.pendingItemIds else current.pendingItemIds + itemId,
        )
        return launchRequest {
            try {
                val row = request(plan.planId)
                if (!remove) {
                    check(row != null && row.id > 0 && row.planId == plan.planId &&
                        (itemId == null || row.id == itemId) && (itemId != null || row.isManual)) {
                        "MealForge confirmed a different grocery item. Refresh before trying again."
                    }
                }
                publish(token) { latest ->
                    check(latest.plan?.planId == plan.planId)
                    val rows = when {
                        remove -> latest.items.filterNot { it.id == itemId }
                        itemId != null -> latest.items.map { if (it.id == itemId) row!! else it }
                        else -> (latest.items.filterNot { it.id == row!!.id } + row!!)
                            .sortedWith(compareBy<GroceryItem> { it.sortOrder }.thenBy { it.name })
                    }
                    latest.copy(items = rows)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed response does not prove that a write was not applied.
                // Read back, but never automatically replay the mutation.
                val reconciled = try {
                    client.groceryItems(plan.planId).also { validateItems(it, plan.planId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                publish(token) { latest -> latest.copy(
                    items = reconciled ?: latest.items,
                    error = error.message ?: "Grocery change failed. Refresh before retrying.",
                    isStale = reconciled == null,
                ) }
            } finally {
                publish(token) { it.copy(
                    adding = if (itemId == null) false else it.adding,
                    pendingItemIds = if (itemId == null) it.pendingItemIds else it.pendingItemIds - itemId,
                ) }
            }
        }
    }

    private fun validateItems(items: List<GroceryItem>, planId: Int) {
        val ids = HashSet<Int>(items.size)
        check(items.all { it.planId == planId && it.id > 0 && ids.add(it.id) }) {
            "MealForge returned groceries for a different plan or duplicate item IDs."
        }
    }

    private fun publish(token: Long, update: (GroceryState) -> GroceryState) {
        synchronized(lock) {
            if (!stopped && token == generation) mutableState.value = update(state.value)
        }
    }

    private fun launchRequest(block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.add(job)
        job.invokeOnCompletion { synchronized(lock) { jobs.remove(job) } }
        job.start()
        return job
    }

    private fun cancelRequests() {
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
    }
}
