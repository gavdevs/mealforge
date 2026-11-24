package com.mealforge.lightphone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Behavioral contract tests for the plan methods the native client must expose,
 * exercised against the real Hono/tRPC app over loopback
 * ([FixtureServer]) rather than a stub.
 *
 * Seeded state (see `contract/fixtureServer.ts`): plan 1 `Fixture` active with
 * meals 1-2, plan 2 `Other` upcoming with meal 3, plan 3 unnamed upcoming with
 * meal 4, plan 4 `Weekend` favorite/completed with cooked meal 5.
 *
 * Every mutation assertion is re-read through a query, so a write only counts
 * when the server reports it.
 */
class PlanClientTest {

    @Test
    fun plansListSendsFiltersAndParsesLifecycleFields() = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val all = client.plans()
                assertEquals(listOf(4, 3, 2, 1), all.map { it.planId })

                val weekend = all.first { it.planId == 4 }
                assertEquals("Weekend", weekend.name)
                assertTrue(weekend.isFavorite)
                assertEquals("completed", weekend.status)
                assertNotNull(weekend.completedAt)
                assertTrue(
                    "completedAt must be the server ISO timestamp, was ${weekend.completedAt}",
                    weekend.completedAt!!.startsWith("2026-02-01T12:00:00"),
                )

                val unnamed = all.first { it.planId == 3 }
                assertNull(unnamed.name)
                assertEquals("Meal Plan 3", unnamed.displayName)
                assertNull(unnamed.completedAt)

                val fixture = all.first { it.planId == 1 }
                assertEquals("Fixture", fixture.name)
                assertEquals("active", fixture.status)
                assertNull(fixture.completedAt)
                assertEquals(listOf(1, 2), fixture.meals.map { it.mealId })
                assertEquals(listOf("dinner", "lunch"), fixture.meals.map { it.mealType })
                assertEquals(listOf(1, 2), fixture.meals.map { it.recipeId })
                assertEquals(listOf("Lemon chicken", "Bean bowls"), fixture.meals.map { it.title })

                assertEquals(listOf(2, 3), client.plans(status = "upcoming").map { it.planId })
                assertEquals(listOf(1), client.plans(status = "active").map { it.planId })
                assertEquals(listOf(4), client.plans(status = "completed").map { it.planId })
                assertEquals(listOf(4), client.plans(favoritesOnly = true).map { it.planId })
                assertEquals(listOf(4), client.plans(limit = 1).map { it.planId })

                assertEquals(6, server.requestCount("GET", "plans.list"))
            }
        }
    }

    @Test
    fun planByIdReadsTheFullDetailAndReturnsNullWhenAbsent() = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val plan = client.plan(1)
                assertNotNull(plan)
                assertEquals("Fixture", plan!!.name)
                assertEquals("Fixture", plan.displayName)
                assertEquals("active", plan.status)
                assertFalse(plan.isFavorite)
                assertNull(plan.completedAt)
                assertNotNull(plan.createdAt)

                val dinner = plan.meals.first { it.mealId == 1 }
                assertEquals("dinner", dinner.mealType)
                assertEquals(1, dinner.recipeId)
                assertEquals("Lemon chicken", dinner.title)
                assertNull(dinner.cookedAt)

                val lunch = plan.meals.first { it.mealId == 2 }
                assertEquals("lunch", lunch.mealType)
                assertEquals(2, lunch.recipeId)
                assertEquals("Bean bowls", lunch.title)

                assertNull("an absent plan is null, not an error", client.plan(99))
            }
        }
    }

    @Test
    fun conflictingActivationIsVisibleThenCompletionFreesTheSlot() = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                assertRefusal("is already active") { client.activatePlan(2) }
                assertEquals(1, client.activePlan()!!.planId)
                assertEquals("upcoming", client.plan(2)!!.status)

                val completed = client.completePlan(1)
                assertEquals("completed", completed.status)
                assertNotNull(completed.completedAt)
                assertNull("completion frees the active slot", client.activePlan())
                assertEquals("completed", client.plan(1)!!.status)

                val activated = client.activatePlan(2)
                assertEquals("active", activated.status)
                assertEquals(2, activated.planId)
                assertNull(activated.completedAt)
                assertEquals(2, client.activePlan()!!.planId)
                assertEquals("active", client.plan(2)!!.status)
                assertEquals("completed", client.plan(1)!!.status)

                assertRefusal("Meal plan 99 does not exist") { client.activatePlan(99) }
                assertEquals("a refused activation changes nothing", 2, client.activePlan()!!.planId)
            }
        }
    }

    @Test
    fun reactivatingACompletedPlanResetsItsCookedMarks() = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val weekend = client.plan(4)!!
                assertEquals("completed", weekend.status)
                assertNotNull(weekend.completedAt)
                assertTrue(weekend.isFavorite)
                assertNotNull(weekend.meals.single { it.mealId == 5 }.cookedAt)

                client.completePlan(1)
                val active = client.activatePlan(4)
                assertEquals("active", active.status)
                assertEquals("Weekend", active.displayName)
                assertNull("activation clears completedAt", active.completedAt)
                assertNull(
                    "cook it again starts with no cooked meals",
                    active.meals.single { it.mealId == 5 }.cookedAt,
                )

                val reread = client.plan(4)!!
                assertNull(reread.completedAt)
                assertNull(reread.meals.single { it.mealId == 5 }.cookedAt)
            }
        }
    }

    @Test
    fun renameTrimsClearsAndCannotStripAFavoriteName() = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val renamed = client.renamePlan(2, "  Taco Week  ")
                assertEquals("Taco Week", renamed.name)
                assertEquals("Taco Week", renamed.displayName)
                assertEquals("Taco Week", client.plan(2)!!.name)

                val cleared = client.renamePlan(2, null)
                assertNull(cleared.name)
                assertEquals("Meal Plan 2", cleared.displayName)
                assertNull(client.plan(2)!!.name)

                assertRefusal("favorite plan needs a name") { client.renamePlan(4, null) }
                assertEquals("Weekend", client.plan(4)!!.name)
                assertTrue(client.plan(4)!!.isFavorite)

                // Whitespace-only is not "clear": the server trims then rejects
                // the empty string, so the stored name must survive.
                assertRefusal { client.renamePlan(1, "   ") }
                assertEquals("Fixture", client.plan(1)!!.name)
            }
        }
    }

    @Test
    fun favoriteToggleRequiresANameAndRoundTripsThroughReads() = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                assertRefusal("Name this plan before favoriting it") {
                    client.togglePlanFavorite(3)
                }
                assertNull(client.plan(3)!!.name)
                assertFalse(client.plan(3)!!.isFavorite)

                client.renamePlan(3, "Three")
                val favorited = client.togglePlanFavorite(3)
                assertTrue(favorited.isFavorite)
                assertEquals("Three", favorited.name)
                assertTrue(client.plan(3)!!.isFavorite)

                assertFalse(client.togglePlanFavorite(3).isFavorite)
                assertFalse(client.plan(3)!!.isFavorite)

                // A favorite keeps its name until it is unfavorited.
                assertFalse(client.togglePlanFavorite(4).isFavorite)
                assertTrue(client.togglePlanFavorite(4).isFavorite)
                assertRefusal("favorite plan needs a name") { client.renamePlan(4, null) }
                assertEquals("Weekend", client.plan(4)!!.name)
                assertFalse(client.togglePlanFavorite(4).isFavorite)
                val cleared = client.renamePlan(4, null)
                assertNull(cleared.name)
                assertEquals("Meal Plan 4", client.plan(4)!!.displayName)
            }
        }
    }

    @Test
    fun cookedToggleIsAbsoluteAndBindsTheContainingPlan() = runBlocking {
        FixtureServer.start().use { server ->
            MealforgeClient(server.baseUrl).use { client ->
                val cooked = client.setMealCooked(1, true)
                assertEquals(1, cooked.planId)
                assertNotNull(cooked.meals.single { it.mealId == 1 }.cookedAt)
                assertNull("only the targeted meal changes", cooked.meals.single { it.mealId == 2 }.cookedAt)
                assertNotNull(client.plan(1)!!.meals.single { it.mealId == 1 }.cookedAt)

                // Absolute, not a toggle: cooking an already cooked meal keeps it cooked.
                assertNotNull(client.setMealCooked(1, true).meals.single { it.mealId == 1 }.cookedAt)

                val uncooked = client.setMealCooked(1, false)
                assertNull(uncooked.meals.single { it.mealId == 1 }.cookedAt)
                assertNull(client.plan(1)!!.meals.single { it.mealId == 1 }.cookedAt)

                // The server returns the *containing* plan: meal 4 is on plan 3.
                val upcoming = client.setMealCooked(4, true)
                assertEquals(3, upcoming.planId)
                assertEquals("upcoming", upcoming.status)
                assertNotNull(upcoming.meals.single { it.mealId == 4 }.cookedAt)
                assertNotNull(client.plan(3)!!.meals.single { it.mealId == 4 }.cookedAt)

                assertRefusal("Meal 99 does not exist") { client.setMealCooked(99, true) }
                assertNull("a refused meal write changes nothing", client.plan(1)!!.meals.single { it.mealId == 1 }.cookedAt)
            }
        }
    }

    /**
     * Asserts the call fails with the server's message reaching the caller,
     * unwrapping client/transport exception chains.
     */
    private suspend fun assertRefusal(expectedFragment: String? = null, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        if (failure == null) {
            fail("expected the server to refuse the request")
            return
        }
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue("failure carried no message: $failure", messages.any { it.isNotBlank() })
        if (expectedFragment != null) {
            assertTrue(
                "expected a message containing '$expectedFragment', got $messages",
                messages.any { it.contains(expectedFragment) },
            )
        }
    }
}
