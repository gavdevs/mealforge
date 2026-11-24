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
 * Behavior tests for [PlanStore] against the real Hono/tRPC fixture over
 * loopback. The store reads its plan by id from the server, and every
 * assertion inspects state only after the server confirmed or refused a write.
 *
 * Seeded plans: 1 `Fixture` active (meals 1-2), 2 `Other` upcoming (meal 3),
 * 3 unnamed upcoming (meal 4), 4 `Weekend` favorite/completed (meal 5 cooked).
 */
class PlanStoreTest {

    @Test
    fun refreshLoadsThePlanAndDropsWritesWhileLoading() = planStoreTest(planId = 1) { server, store ->
        server.gateNext("plans.byId", method = "GET")
        val loading = store.refresh()

        server.awaitGateStarted()
        store.awaitState { it.loading }
        assertNull(
            "a write while the plan is loading must be dropped",
            store.rename("Early"),
        )
        assertEquals(0, server.requestCount("POST", "plans.rename"))

        server.releaseGate()
        withTimeout(5_000) { loading.join() }

        val loaded = store.awaitState { !it.loading && it.plan != null }
        assertEquals(1, loaded.plan!!.planId)
        assertEquals("Fixture", loaded.plan!!.displayName)
        assertEquals("active", loaded.plan!!.status)
        assertFalse(loaded.plan!!.isFavorite)
        assertEquals(listOf(1, 2), loaded.plan!!.meals.map { it.mealId })
        assertEquals(listOf("dinner", "lunch"), loaded.plan!!.meals.map { it.mealType })
        assertNull(loaded.plan!!.meals.single { it.mealId == 1 }.cookedAt)
        assertNull(loaded.error)
        assertFalse(loaded.pending)
        assertFalse("a successful read is not stale", loaded.isStale)

        assertEquals(1, server.requestCount("GET", "plans.byId"))
        assertEquals(0, server.requestCount("POST", "plans.rename"))
    }

    @Test
    fun upcomingCookedForeignMealAndUnnamedFavoriteAreRejectedWithoutWriting() =
        planStoreTest(planId = 3) { server, store ->
            store.refresh().also { job ->
                store.awaitState { !it.loading && it.plan?.planId == 3 }
                job.join()
            }

            assertNull(
                "an upcoming plan has no cooked control",
                store.setMealCooked(4, true),
            )
            assertNull(
                "a meal on another plan must not be edited through this store",
                store.setMealCooked(2, true),
            )
            assertNull(
                "an unnamed plan needs a name before it can be favorited",
                store.toggleFavorite(),
            )

            val refused = store.state.value
            val message = refused.error.orEmpty()
            assertTrue(
                "the name requirement must be visible, got: $message",
                message.contains("name", ignoreCase = true),
            )
            assertEquals(0, server.requestCount("POST", "plans.setMealCooked"))
            assertEquals(0, server.requestCount("POST", "plans.toggleFavorite"))
            assertEquals("Meal Plan 3", refused.plan!!.displayName)
            assertFalse(refused.plan!!.isFavorite)

            val rename = store.rename("  Three  ")
            assertNotNull(rename)
            store.awaitState { it.plan?.displayName == "Three" && !it.pending }
            withTimeout(5_000) { rename!!.join() }

            val favorite = store.toggleFavorite()
            assertNotNull("a named plan can be favorited", favorite)
            val favorited = store.awaitState { it.plan?.isFavorite == true && !it.pending }
            withTimeout(5_000) { favorite!!.join() }

            assertEquals("the server trims the new name", "Three", favorited.plan!!.displayName)
            assertNull("a confirmed write clears the hint", favorited.error)
            assertEquals(1, server.requestCount("POST", "plans.rename"))
            assertEquals(1, server.requestCount("POST", "plans.toggleFavorite"))
        }

    @Test
    fun duplicateFavoriteTapWritesOnceAndPublishesOnlyTheServerResult() =
        planStoreTest(planId = 1) { server, store ->
            store.refresh().also { job ->
                store.awaitState { !it.loading && it.plan?.planId == 1 }
                job.join()
            }

            server.gateNext("plans.toggleFavorite")
            val first = store.toggleFavorite()
            assertNotNull(first)
            server.awaitGateStarted()

            val pending = store.awaitState { it.pending }
            assertFalse(
                "the favorite must not flip before the server confirms it",
                pending.plan!!.isFavorite,
            )
            assertNull("a duplicate tap while pending must be dropped", store.toggleFavorite())
            assertNull("the pending gate spans rename", store.rename("Blocked"))
            assertNull("the pending gate spans cooked toggles", store.setMealCooked(1, true))
            assertNull("the pending gate spans completion", store.complete())
            assertNull("the pending gate spans activation", store.activate())
            assertEquals(0, server.requestCount("POST", "plans.rename"))
            assertEquals(0, server.requestCount("POST", "plans.setMealCooked"))
            assertEquals(0, server.requestCount("POST", "plans.complete"))
            assertEquals(0, server.requestCount("POST", "plans.activate"))

            server.releaseGate()
            withTimeout(5_000) { first!!.join() }

            val confirmed = store.awaitState { it.plan?.isFavorite == true && !it.pending }
            assertTrue("only the server result is published", confirmed.plan!!.isFavorite)
            assertNull(confirmed.error)
            assertEquals(1, server.requestCount("POST", "plans.toggleFavorite"))
        }

    @Test
    fun refusedActivationKeepsThePlanVisibleAndRereadsIt() =
        planStoreTest(planId = 2) { server, store ->
            store.refresh().also { job ->
                store.awaitState { !it.loading && it.plan?.planId == 2 }
                job.join()
            }
            assertEquals(1, server.requestCount("GET", "plans.byId"))

            val activation = store.activate()
            assertNotNull(activation)
            withTimeout(5_000) { activation!!.join() }

            val failed = store.awaitState { !it.pending }
            val message = failed.error.orEmpty()
            assertTrue(
                "the backend refusal must be visible, got: $message",
                message.contains("already active"),
            )
            assertEquals("the displayed plan must not change", 2, failed.plan!!.planId)
            assertEquals("upcoming", failed.plan!!.status)
            assertEquals("Other", failed.plan!!.displayName)
            assertEquals(1, server.requestCount("POST", "plans.activate"))
            assertEquals(
                "a refused write must be reconciled by a fresh read",
                2,
                server.requestCount("GET", "plans.byId"),
            )
        }

    @Test
    fun stopInvalidatesAGatedWriteAndBlocksWritesUntilRefresh() =
        planStoreTest(planId = 1) { server, store ->
            store.refresh().also { job ->
                store.awaitState { !it.loading && it.plan?.planId == 1 }
                job.join()
            }

            server.gateNext("plans.toggleFavorite")
            val gated = store.toggleFavorite()
            assertNotNull(gated)
            server.awaitGateStarted()
            store.awaitState { it.pending }

            store.stop()
            server.releaseGate()
            withTimeout(5_000) { gated!!.join() }

            val stopped = store.state.value
            assertEquals("the last read stays on screen", "Fixture", stopped.plan!!.displayName)
            assertFalse(
                "a write that landed after stop must not be published",
                stopped.plan!!.isFavorite,
            )
            assertFalse(stopped.loading)
            assertFalse(stopped.pending)
            assertTrue("leaving the screen makes the read stale", stopped.isStale)
            assertNull("a stopped store must not start writes", store.toggleFavorite())
            assertNull("a stopped store must not rename", store.rename("Blocked"))
            assertNull("a stale store must not write cooked state", store.setMealCooked(1, true))
            assertEquals(1, server.requestCount("POST", "plans.toggleFavorite"))
            assertEquals(0, server.requestCount("POST", "plans.rename"))
            assertEquals(0, server.requestCount("POST", "plans.setMealCooked"))

            val readsBeforeRefresh = server.requestCount("GET", "plans.byId")

            store.refresh().also { job ->
                store.awaitState { !it.loading && !it.isStale && it.plan != null }
                job.join()
            }
            assertEquals(
                "refresh performs exactly one read",
                readsBeforeRefresh + 1,
                server.requestCount("GET", "plans.byId"),
            )

            val renamed = store.rename("After")
            assertNotNull("refresh re-enables writes", renamed)
            val confirmed = store.awaitState { it.plan?.displayName == "After" && !it.pending }
            withTimeout(5_000) { renamed!!.join() }

            assertEquals("After", confirmed.plan!!.displayName)
            assertNull(confirmed.error)
            assertEquals(1, server.requestCount("POST", "plans.rename"))
        }

    private fun planStoreTest(
        planId: Int,
        body: suspend CoroutineScope.(FixtureServer, PlanStore) -> Unit,
    ) = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val store = PlanStore(client, scope, planId)
                try {
                    body(server, store)
                } finally {
                    store.stop()
                    scope.cancel()
                }
            }
        }
    }

    private suspend fun PlanStore.awaitState(
        timeoutMs: Long = 5_000,
        predicate: (PlanState) -> Boolean,
    ): PlanState = withTimeout(timeoutMs) { state.first(predicate) }
}
