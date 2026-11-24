package com.mealforge.lightphone

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for the inline recipe-card favorite action against the real
 * Hono/tRPC fixture over loopback. Each card row keeps its own pending gate
 * while the list keeps accepting taps on other rows, and a favorite is only
 * reported once the server confirmed the change for that same recipe.
 *
 * Seeded recipes: 1 `Lemon chicken` (not favorite) and 2 `Bean bowls`
 * (favorite). Nothing here is mocked; a reported confirmation is checked
 * against a fresh `recipes.list` read of the same server.
 */
class RecipeCardActionsTest {

    @Test
    fun togglingDoesNothingUntilResumed() =
        recipeCardActionsTest { server, _, actions, confirmed ->
            assertTrue(
                "no row is pending before the screen is shown",
                actions.state.value.pendingIds.isEmpty(),
            )
            assertNull("an inactive card action must not write", actions.toggle(1))
            assertEquals(0, server.requestCount("POST", "recipes.toggleFavorite"))
            assertTrue(confirmed.isEmpty())

            actions.resume()
            val first = actions.toggle(1)
            assertNotNull("a resumed card action writes", first)
            withTimeout(5_000) { first!!.join() }

            assertEquals(listOf(1), confirmed.map { it.id })
            assertEquals(1, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    @Test
    fun duplicateTapOnTheSameRowIsRefusedWhileGatedAndOnlyTheServerResultIsReported() =
        recipeCardActionsTest { server, client, actions, confirmed ->
            actions.resume()

            server.gateNext("recipes.toggleFavorite")
            val gated = actions.toggle(1)
            assertNotNull(gated)
            server.awaitGateStarted()

            awaitThat { 1 in actions.state.value.pendingIds }
            assertNull("a second tap on the gated row must not write again", actions.toggle(1))
            assertNull("the row stays refused until the first request settles", actions.toggle(1))
            assertEquals(
                "only the first tap reached the server",
                1,
                server.requestCount("POST", "recipes.toggleFavorite"),
            )
            assertTrue("nothing is reported before the server answers", confirmed.isEmpty())

            server.releaseGate()
            withTimeout(5_000) { gated!!.join() }
            awaitThat { actions.state.value.pendingIds.isEmpty() && confirmed.size == 1 }

            assertNull(actions.state.value.error)
            assertEquals("exactly one confirmation for the row", listOf(1), confirmed.map { it.id })
            val stored = client.recipes().single { it.id == 1 }
            assertEquals(
                "the reported favorite is what the server now stores",
                stored.isFavorite,
                confirmed.single().isFavorite,
            )
            assertEquals(1, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    @Test
    fun anotherRowStaysUsableWhileOneFavoriteIsPending() =
        recipeCardActionsTest { server, _, actions, confirmed ->
            actions.resume()

            server.gateNext("recipes.toggleFavorite")
            val gated = actions.toggle(1)
            assertNotNull(gated)
            server.awaitGateStarted()
            awaitThat { 1 in actions.state.value.pendingIds }
            assertFalse("recipe 2 is not the pending row", 2 in actions.state.value.pendingIds)

            val other = actions.toggle(2)
            assertNotNull("the per-row gate must not freeze the whole list", other)
            withTimeout(5_000) { other!!.join() }

            assertEquals(listOf(2), confirmed.map { it.id })
            assertFalse("Bean bowls started as a favorite", confirmed.single().isFavorite)
            assertNull(actions.state.value.error)
            assertTrue("the gated row is still pending", 1 in actions.state.value.pendingIds)

            server.releaseGate()
            withTimeout(5_000) { gated!!.join() }
            awaitThat { actions.state.value.pendingIds.isEmpty() }

            assertEquals("each row confirms its own change", listOf(2, 1), confirmed.map { it.id })
            assertNull(actions.state.value.error)
            assertEquals(2, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    @Test
    fun invalidIdsAreRefusedWithoutARequest() =
        recipeCardActionsTest { server, _, actions, confirmed ->
            actions.resume()

            assertNull(actions.toggle(0))
            assertNull(actions.toggle(-3))
            assertEquals(
                "a server-invalid id must never be sent",
                0,
                server.requestCount("POST", "recipes.toggleFavorite"),
            )
            assertTrue(actions.state.value.pendingIds.isEmpty())
            assertTrue(confirmed.isEmpty())
        }

    @Test
    fun missingRecipeFailsVisiblyOnceWithoutPretendingSuccess() =
        recipeCardActionsTest { server, _, actions, confirmed ->
            actions.resume()

            val missing = actions.toggle(99)
            assertNotNull("the tap was accepted for a server round trip", missing)
            withTimeout(5_000) { missing!!.join() }
            awaitThat { actions.state.value.pendingIds.isEmpty() }

            val message = actions.state.value.error.orEmpty()
            assertTrue(
                "the refusal must be visible and name the missing recipe, got: $message",
                message.contains("does not exist") || message.contains("99"),
            )
            assertTrue("a failed favorite must not report a confirmation", confirmed.isEmpty())
            assertEquals(
                "a failure is not retried",
                1,
                server.requestCount("POST", "recipes.toggleFavorite"),
            )

            // The card is still usable afterwards, and a success clears the hint.
            val retryRow = actions.toggle(1)
            assertNotNull(retryRow)
            withTimeout(5_000) { retryRow!!.join() }
            assertEquals(listOf(1), confirmed.map { it.id })
            assertNull(actions.state.value.error)
            assertEquals(2, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    @Test
    fun stopSuppressesTheLateConfirmationAndLeavesTheInterruptedWriteAmbiguous() =
        recipeCardActionsTest { server, _, actions, confirmed ->
            actions.resume()

            server.gateNext("recipes.toggleFavorite")
            val gated = actions.toggle(1)
            assertNotNull(gated)
            server.awaitGateStarted()
            awaitThat { 1 in actions.state.value.pendingIds }

            actions.stop()
            server.releaseGate()
            withTimeout(5_000) { gated!!.join() }
            awaitThat { actions.state.value.pendingIds.isEmpty() }

            assertTrue("leaving the list must not report a confirmation", confirmed.isEmpty())
            val message = actions.state.value.error.orEmpty().lowercase()
            assertTrue("leaving mid-write must leave a visible notice", message.isNotBlank())
            assertTrue(
                "the notice must say the favorite was interrupted, got: $message",
                "interrupt" in message || "cancel" in message ||
                    "may" in message || "might" in message,
            )
            assertTrue(
                "the notice must tell the user to check the state before retrying, got: $message",
                "check" in message || "retry" in message || "again" in message,
            )
            assertNull("a stopped list must not write", actions.toggle(1))
            assertEquals(
                "the interrupted request is the only write",
                1,
                server.requestCount("POST", "recipes.toggleFavorite"),
            )

            actions.resume()
            val afterReturn = actions.toggle(1)
            assertNotNull("returning to the list re-enables the card action", afterReturn)
            withTimeout(5_000) { afterReturn!!.join() }
            assertEquals(listOf(1), confirmed.map { it.id })
            assertEquals(2, server.requestCount("POST", "recipes.toggleFavorite"))
        }

    private fun recipeCardActionsTest(
        body: suspend CoroutineScope.(FixtureServer, MealforgeClient, RecipeCardActions, List<RecipeSummary>) -> Unit,
    ) = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val confirmed = CopyOnWriteArrayList<RecipeSummary>()
                val actions = RecipeCardActions(client, scope) { confirmed.add(it) }
                try {
                    body(server, client, actions, confirmed)
                } finally {
                    actions.stop()
                    scope.cancel()
                }
            }
        }
    }

    /** Waits for observable state without assuming a concrete flow type. */
    private suspend fun awaitThat(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }
}
