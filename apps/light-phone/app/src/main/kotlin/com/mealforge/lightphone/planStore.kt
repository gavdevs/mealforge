package com.mealforge.lightphone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlanState(
    val plan: ActivePlan? = null,
    val loading: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null,
    val isStale: Boolean = false,
)

class PlanStore(private val client: MealforgeClient, private val scope: CoroutineScope, private val planId: Int) {
    private val lock = Any()
    private val mutable = MutableStateFlow(PlanState())
    val state: StateFlow<PlanState> = mutable.asStateFlow()
    private var generation = 0L
    private var stopped = true
    private var request: Job? = null
    private var interrupted = false

    fun refresh(): Job = synchronized(lock) {
        generation++
        request?.cancel()
        stopped = false
        val token = generation
        mutable.value = state.value.copy(loading = true, pending = false, error = null, isStale = state.value.plan != null)
        launchRequest {
            try {
                val plan = client.plan(planId)
                validate(plan)
                publish(token) { PlanState(plan = plan, error = interruptionNotice()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publish(token) { it.copy(loading = false, error = error.message ?: "Could not read plan.", isStale = it.plan != null) }
            }
        }
    }

    fun rename(name: String?): Job? = synchronized(lock) { mutate { client.renamePlan(planId, name) } }

    fun toggleFavorite(): Job? = synchronized(lock) {
        val plan = state.value.plan ?: return@synchronized null
        if (!plan.isFavorite && plan.name == null) {
            mutable.value = state.value.copy(error = "Name this plan before favoriting it.")
            return@synchronized null
        }
        mutate { client.togglePlanFavorite(planId) }
    }

    fun activate(): Job? = synchronized(lock) {
        if (state.value.plan?.status == "active") return@synchronized null
        mutate { client.activatePlan(planId) }
    }

    fun complete(): Job? = synchronized(lock) {
        if (state.value.plan?.status != "active") return@synchronized null
        mutate { client.completePlan(planId) }
    }

    fun setMealCooked(mealId: Int, cooked: Boolean): Job? = synchronized(lock) {
        val plan = state.value.plan ?: return@synchronized null
        if (plan.status == "upcoming" || plan.meals.none { it.mealId == mealId }) return@synchronized null
        mutate { client.setMealCooked(mealId, cooked) }
    }

    fun stop() = synchronized(lock) {
        interrupted = interrupted || state.value.pending
        stopped = true
        generation++
        request?.cancel()
        request = null
        mutable.value = state.value.copy(loading = false, pending = false, isStale = state.value.plan != null)
    }

    private fun mutate(action: suspend () -> ActivePlan): Job? {
        val current = state.value
        if (stopped || current.loading || current.pending || current.isStale || current.plan == null) return null
        val token = generation
        mutable.value = current.copy(pending = true, error = null)
        return launchRequest {
            try {
                val result = action()
                validate(result)
                publish(token) {
                    interrupted = false
                    PlanState(plan = result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                var readSucceeded = true
                val confirmed = try {
                    client.plan(planId).also { validate(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    readSucceeded = false
                    null
                }
                publish(token) { it.copy(
                    plan = if (readSucceeded) confirmed else it.plan,
                    error = "${error.message ?: "Plan change failed."} Check the current state before retrying; the change may have reached the server.",
                    isStale = !readSucceeded,
                ) }
            } finally {
                publish(token) { it.copy(pending = false) }
            }
        }
    }

    private fun interruptionNotice(): String? =
        if (interrupted) "Previous change interrupted. It may have reached the server. Check the refreshed state before retrying." else null

    private fun validate(plan: ActivePlan?) {
        check(plan == null || (plan.planId == planId &&
            (plan.status == "active" || plan.status == "upcoming" || plan.status == "completed"))) {
            "MealForge returned a different or invalid plan."
        }
    }

    private fun publish(token: Long, update: (PlanState) -> PlanState) = synchronized(lock) {
        if (!stopped && generation == token) mutable.value = update(state.value)
    }

    private fun launchRequest(block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        request = job
        job.invokeOnCompletion { synchronized(lock) { if (request === job) request = null } }
        job.start()
        return job
    }
}
