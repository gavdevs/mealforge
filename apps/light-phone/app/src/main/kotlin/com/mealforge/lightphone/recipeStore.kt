package com.mealforge.lightphone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecipeState(val recipe: RecipeDetail? = null, val loading: Boolean = false,
    val pending: Boolean = false, val error: String? = null, val isStale: Boolean = false)

class RecipeStore(private val client: MealforgeClient, private val scope: CoroutineScope, private val recipeId: Int) {
    private val lock = Any()
    private val mutable = MutableStateFlow(RecipeState(loading = true))
    val state: StateFlow<RecipeState> = mutable.asStateFlow()
    private var generation = 0L
    private var stopped = true
    private var request: Job? = null
    private var interrupted = false

    fun refresh(): Job = synchronized(lock) {
        generation++
        request?.cancel()
        stopped = false
        val token = generation
        mutable.value = state.value.copy(loading = true, pending = false, error = null, isStale = state.value.recipe != null)
        launchRequest {
            try {
                val recipe = client.recipe(recipeId).also { validate(it) }
                publish(token) { RecipeState(recipe = recipe, error = if (interrupted)
                    "Previous favorite change interrupted. Check the refreshed state before trying again." else null) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                publish(token) { it.copy(loading = false, error = error.message ?: "Could not read recipe.", isStale = it.recipe != null) }
            }
        }
    }

    fun toggleFavorite(): Job? = synchronized(lock) {
        val current = state.value
        if (stopped || current.loading || current.pending || current.isStale || current.recipe == null) return@synchronized null
        val token = generation
        mutable.value = current.copy(pending = true, error = null)
        launchRequest {
            try {
                val confirmed = client.toggleRecipeFavorite(recipeId)
                check(confirmed.id == recipeId) { "MealForge confirmed a different recipe." }
                val full = client.recipe(recipeId).also { validate(it) }
                publish(token) { interrupted = false; RecipeState(recipe = full) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                var readSucceeded = true
                val confirmed = try { client.recipe(recipeId).also { validate(it) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { readSucceeded = false; null }
                publish(token) { it.copy(
                    recipe = if (readSucceeded) confirmed else it.recipe,
                    error = "${error.message ?: "Favorite request or refresh failed."} Check the current favorite state before retrying; the change may have reached the server.",
                    isStale = !readSucceeded,
                ) }
            } finally { publish(token) { it.copy(pending = false) } }
        }
    }

    fun stop() = synchronized(lock) {
        interrupted = interrupted || state.value.pending
        stopped = true
        generation++
        request?.cancel()
        request = null
        mutable.value = state.value.copy(loading = false, pending = false, isStale = state.value.recipe != null)
    }

    private fun validate(recipe: RecipeDetail?) {
        check(recipe == null || recipe.id == recipeId) { "MealForge returned a different recipe." }
    }
    private fun publish(token: Long, update: (RecipeState) -> RecipeState) = synchronized(lock) {
        if (!stopped && token == generation) mutable.value = update(state.value)
    }
    private fun launchRequest(block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        request = job
        job.invokeOnCompletion { synchronized(lock) { if (request === job) request = null } }
        job.start()
        return job
    }
}
