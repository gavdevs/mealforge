package com.mealforge.lightphone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RecipeCardActionState(
    val pendingIds: Set<Int> = emptySet(),
    val error: String? = null,
)

internal class RecipeCardActions(
    private val client: MealforgeClient,
    private val scope: CoroutineScope,
    private val onConfirmed: (RecipeSummary) -> Unit,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(RecipeCardActionState())
    val state: StateFlow<RecipeCardActionState> = mutable.asStateFlow()
    private val requests = mutableMapOf<Int, Job>()
    private var generation = 0L
    private var stopped = true

    fun resume(): Unit = synchronized(lock) { stopped = false }

    fun stop(): Unit = synchronized(lock) {
        stopped = true
        generation++
        val interrupted = state.value.pendingIds.isNotEmpty()
        val iterator = requests.values.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            iterator.remove()
            job.cancel()
        }
        mutable.value = state.value.copy(
            pendingIds = emptySet(),
            error = if (interrupted) "Favorite change interrupted. It may have reached the server. Check the refreshed state before trying again." else state.value.error,
        )
    }

    fun toggle(id: Int): Job? = synchronized(lock) {
        if (stopped || id <= 0 || id in state.value.pendingIds) return@synchronized null
        val token = generation
        mutable.value = state.value.copy(pendingIds = state.value.pendingIds + id)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = client.toggleRecipeFavorite(id)
                check(result.id == id) { "MealForge confirmed a different recipe." }
                publish(token) {
                    onConfirmed(result)
                    mutable.value = state.value.copy(error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed response does not establish whether the POST was applied.
                val current = try {
                    client.recipe(id).also { check(it == null || it.id == id) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                publish(token) {
                    current?.let { recipe -> onConfirmed(RecipeSummary(
                        recipe.id, recipe.title, recipe.description, recipe.servings,
                        recipe.prepMinutes, recipe.cookMinutes, recipe.tags, recipe.mealTypes,
                        recipe.isFavorite, recipe.createdAt,
                    )) }
                    mutable.value = state.value.copy(error = "Recipe $id: ${error.message ?: "Favorite change failed."} Check the current state before retrying; the change may have reached the server.")
                }
            } finally {
                publish(token) { mutable.value = state.value.copy(pendingIds = state.value.pendingIds - id) }
            }
        }
        requests[id] = job
        job.invokeOnCompletion { synchronized(lock) { if (requests[id] === job) requests.remove(id) } }
        job.start()
        job
    }

    private fun publish(token: Long, action: () -> Unit): Unit = synchronized(lock) {
        if (!stopped && token == generation) action()
    }
}
