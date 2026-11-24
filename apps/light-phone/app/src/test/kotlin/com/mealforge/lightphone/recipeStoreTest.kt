package com.mealforge.lightphone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for [RecipeStore] against the real Hono/tRPC fixture over
 * loopback. The store reads one recipe by id, and every assertion inspects
 * state only after the server confirmed or refused the request.
 *
 * Seeded recipes: 1 `Lemon chicken` (not favorite, dinner, ingredients Lemon /
 * Parsley / Chicken, used by plans 2 and 1) and 2 `Bean bowls` (favorite).
 */
class RecipeStoreTest {

    @Test
    fun refreshLoadsTheRecipeAndDropsWritesWhileLoading() =
        recipeStoreTest(recipeId = 1) { server, store ->
            server.gateNext("recipes.byId", method = "GET")
            val loading = store.refresh()

            server.awaitGateStarted()
            store.awaitState { it.loading }
            assertNull(
                "a write while the recipe is loading must be dropped",
                store.toggleFavorite(),
            )
            assertEquals(0, server.requestCount("POST", "recipes.toggleFavorite"))

            server.releaseGate()
            withTimeout(5_000) { loading.join() }

            val loaded = store.awaitState { !it.loading && it.recipe != null }
            assertEquals(1, loaded.recipe!!.id)
            assertEquals("Lemon chicken", loaded.recipe!!.title)
            assertEquals("Bright roast", loaded.recipe!!.description)
            assertEquals(4, loaded.recipe!!.servings)
            assertEquals(10, loaded.recipe!!.prepMinutes)
            assertEquals(25, loaded.recipe!!.cookMinutes)
            assertEquals(listOf("bright", "quick"), loaded.recipe!!.tags)
            assertEquals(listOf("dinner"), loaded.recipe!!.mealTypes)
            assertFalse(loaded.recipe!!.isFavorite)
            assertEquals(
                listOf("Lemon", "Parsley", "Chicken"),
                loaded.recipe!!.ingredients.map { it.name },
            )
            assertEquals(
                listOf(2, 1),
                loaded.recipe!!.usedInPlans.map { it.planId },
            )
            assertNull(loaded.error)
            assertFalse(loaded.pending)
            assertFalse("a successful read is not stale", loaded.isStale)

            assertEquals(1, server.requestCount("GET", "recipes.byId"))
            assertEquals(0, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    @Test
    fun duplicateFavoriteTapWritesOnceAndPublishesOnlyTheServerResult() =
        recipeStoreTest(recipeId = 1) { server, store ->
            store.refresh().also { job ->
                store.awaitState { !it.loading && it.recipe?.id == 1 }
                job.join()
            }

            server.gateNext("recipes.toggleFavorite")
            val first = store.toggleFavorite()
            assertNotNull(first)
            server.awaitGateStarted()

            val pending = store.awaitState { it.pending }
            assertFalse(
                "the favorite must not flip before the server confirms it",
                pending.recipe!!.isFavorite,
            )
            assertNull("a duplicate tap while pending must be dropped", store.toggleFavorite())
            assertEquals(1, server.requestCount("POST", "recipes.toggleFavorite"))

            server.releaseGate()
            withTimeout(5_000) { first!!.join() }

            val confirmed = store.awaitState { it.recipe?.isFavorite == true && !it.pending }
            assertTrue("only the server result is published", confirmed.recipe!!.isFavorite)
            assertNull(confirmed.error)
            assertEquals(1, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    @Test
    fun missingRecipeShowsNoErrorWhileAnInvalidIdSurfacesTheValidationError() {
        recipeStoreTest(recipeId = 99) { server, store ->
            // Joining the request is race-free: the store publishes its final
            // state before the job completes.
            withTimeout(5_000) { store.refresh().join() }
            val loaded = store.state.value

            assertNull("a missing recipe is empty state, not a failure", loaded.recipe)
            assertNull("a missing recipe is not an error", loaded.error)
            assertFalse(loaded.loading)
            assertFalse(loaded.pending)
            assertFalse(loaded.isStale)
            assertNull("there is nothing to favorite", store.toggleFavorite())
            assertEquals(1, server.requestCount("GET", "recipes.byId"))
            assertEquals(0, server.requestCount("POST", "recipes.toggleFavorite"))
        }

        recipeStoreTest(recipeId = 0) { server, store ->
            val job = store.refresh()
            val failed = store.awaitState { !it.loading && it.error != null }
            job.join()

            assertNull("a rejected request must not look like a missing recipe", failed.recipe)
            assertFalse("the server rejection must stay visible", failed.error!!.isBlank())
            assertFalse(
                "id 0 is an API validation failure, not a fake not-found result",
                failed.error!!.contains("does not exist"),
            )
            assertFalse(failed.isStale)
            assertNull("a rejected read leaves nothing to write", store.toggleFavorite())
            assertEquals(1, server.requestCount("GET", "recipes.byId"))
            assertEquals(0, server.requestCount("POST", "recipes.toggleFavorite"))
        }
    }

    @Test
    fun stopInvalidatesAGatedWriteAndBlocksWritesUntilRefresh() =
        recipeStoreTest(recipeId = 1) { server, store ->
            store.refresh().also { job ->
                store.awaitState { !it.loading && it.recipe?.id == 1 }
                job.join()
            }

            server.gateNext("recipes.toggleFavorite")
            val gated = store.toggleFavorite()
            assertNotNull(gated)
            server.awaitGateStarted()
            store.awaitState { it.pending }

            store.stop()
            server.releaseGate()
            withTimeout(5_000) { gated!!.join() }

            val stopped = store.state.value
            assertEquals("the last read stays on screen", "Lemon chicken", stopped.recipe!!.title)
            assertFalse(
                "a write that landed after stop must not be published",
                stopped.recipe!!.isFavorite,
            )
            assertFalse(stopped.loading)
            assertFalse(stopped.pending)
            assertTrue("leaving the screen makes the read stale", stopped.isStale)
            assertNull("a stopped store must not write", store.toggleFavorite())
            assertEquals(1, server.requestCount("POST", "recipes.toggleFavorite"))

            val readsBeforeRefresh = server.requestCount("GET", "recipes.byId")

            store.refresh().also { job ->
                store.awaitState { !it.loading && !it.isStale && it.recipe != null }
                job.join()
            }
            assertEquals(
                "refresh performs exactly one read",
                readsBeforeRefresh + 1,
                server.requestCount("GET", "recipes.byId"),
            )

            val refreshed = store.state.value.recipe!!
            val toggled = store.toggleFavorite()
            assertNotNull("refresh re-enables writes", toggled)
            val confirmed = store.awaitState { it.recipe != null && !it.pending }
            withTimeout(5_000) { toggled!!.join() }

            assertEquals(
                "the published favorite must be the flipped server response",
                !refreshed.isFavorite,
                confirmed.recipe!!.isFavorite,
            )
            assertNull(confirmed.error)
            assertEquals(2, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    private fun recipeStoreTest(
        recipeId: Int,
        body: suspend CoroutineScope.(FixtureServer, RecipeStore) -> Unit,
    ) = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val store = RecipeStore(client, scope, recipeId)
                try {
                    body(server, store)
                } finally {
                    store.stop()
                    scope.cancel()
                }
            }
        }
    }

    private suspend fun RecipeStore.awaitState(
        timeoutMs: Long = 5_000,
        predicate: (RecipeState) -> Boolean,
    ): RecipeState = withTimeout(timeoutMs) { state.first(predicate) }
}
