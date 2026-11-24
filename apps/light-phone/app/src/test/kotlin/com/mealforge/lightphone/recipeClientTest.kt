package com.mealforge.lightphone

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Drives the recipe surface of the real [MealforgeClient] against the shared
 * loopback fixture: the unchanged Hono/tRPC app over in-memory SQLite.
 *
 * Fixture rows this test relies on (seeded by `contract/fixtureServer.ts`):
 * recipe 1 `Lemon chicken` (prep 10 / cook 25, tags `bright`+`quick`, dinner,
 * ingredients Lemon / Parsley / Chicken) and recipe 2 `Bean bowls` (prep null /
 * cook 15, tags `pantry`, lunch+dinner, favorite, newer, ingredient Beans).
 * Plans 1 `Fixture` and 2 `Other` use recipe 1; plans 1, 3 (unnamed) and
 * 4 `Weekend` use recipe 2. Every assertion is a real HTTP read or a
 * server-confirmed mutation response; nothing is mocked.
 */
class RecipeClientTest {
    private var fixture: FixtureServer? = null
    private var client: MealforgeClient? = null

    @Before
    fun startFixture() {
        val server = FixtureServer.start()
        fixture = server
        client = MealforgeClient(server.baseUrl)
    }

    @After
    fun stopFixture() {
        try {
            client?.close()
        } finally {
            fixture?.close()
        }
    }

    @Test
    fun listOrdersNewestFirstAndSearchesTitleTagAndIngredient() = runBlocking {
        val mealforge = client!!

        // History order is createdAt desc, so the newer bean recipe leads.
        val all = mealforge.recipes()
        assertEquals(listOf(2, 1), all.map { it.id })

        val beans = all[0]
        assertEquals("Bean bowls", beans.title)
        assertEquals(2, beans.servings)
        assertNull("prep minutes are nullable in the contract", beans.prepMinutes)
        assertEquals(15, beans.cookMinutes)
        assertEquals(listOf("pantry"), beans.tags)
        assertEquals(listOf("lunch", "dinner"), beans.mealTypes)
        assertTrue(beans.isFavorite)
        assertTrue("server timestamps are ISO strings", beans.createdAt?.contains("T") == true)

        val chicken = all[1]
        assertEquals(1, chicken.id)
        assertEquals("Lemon chicken", chicken.title)
        assertEquals("Bright roast", chicken.description)
        assertEquals(4, chicken.servings)
        assertEquals(10, chicken.prepMinutes)
        assertEquals(25, chicken.cookMinutes)
        assertEquals(listOf("bright", "quick"), chicken.tags)
        assertEquals(listOf("dinner"), chicken.mealTypes)
        assertFalse(chicken.isFavorite)
        assertTrue(chicken.createdAt != null)

        // Search covers title, tags and ingredient names. SQLite LIKE makes the
        // title/tag/ingredient match case-insensitive, and the web relies on it.
        assertEquals(listOf(1), mealforge.recipes(query = "lemon").map { it.id })
        assertEquals("tag-only search", listOf(2), mealforge.recipes(query = "pantry").map { it.id })
        assertEquals(
            "ingredient-only search",
            listOf(1),
            mealforge.recipes(query = "parsley").map { it.id },
        )
        assertEquals(listOf(1), mealforge.recipes(query = "bright").map { it.id })
        assertEquals(listOf(2), mealforge.recipes(query = "bowls").map { it.id })
        assertTrue("no match is an empty list, not an error", mealforge.recipes(query = "zzz").isEmpty())

        // limit is applied by the server after ordering.
        assertEquals(listOf(2), mealforge.recipes(limit = 1).map { it.id })
        assertEquals(listOf(2, 1), mealforge.recipes(limit = 100).map { it.id })
    }

    @Test
    fun listFiltersByFavoriteAndExactMealType() = runBlocking {
        val mealforge = client!!

        assertEquals(listOf(2), mealforge.recipes(favoritesOnly = true).map { it.id })

        // Recipe 2 is tagged lunch+dinner; recipe 1 is dinner only. Meal-type
        // filtering matches whole JSON elements, not substrings.
        assertEquals(listOf(2, 1), mealforge.recipes(mealType = "dinner").map { it.id })
        assertEquals(listOf(2), mealforge.recipes(mealType = "lunch").map { it.id })
        assertTrue(mealforge.recipes(mealType = "snack").isEmpty())
        assertTrue(mealforge.recipes(mealType = "breakfast").isEmpty())

        // Filters combine.
        assertEquals(listOf(2), mealforge.recipes(favoritesOnly = true, mealType = "lunch").map { it.id })
        assertEquals(listOf(2), mealforge.recipes(favoritesOnly = true, mealType = "dinner").map { it.id })
        assertTrue(mealforge.recipes(favoritesOnly = true, mealType = "breakfast").isEmpty())

        // The list view's own request shape: search + filter + limit together.
        assertEquals(
            listOf(2),
            mealforge.recipes(query = "beans", mealType = "lunch", limit = 100).map { it.id },
        )
    }

    @Test
    fun detailPreservesNullsSectionsAndPlanUsage() = runBlocking {
        val mealforge = client!!

        val detail = mealforge.recipe(1)
        if (detail == null) {
            fail("fixture seeds recipe 1")
            return@runBlocking
        }

        assertEquals("Lemon chicken", detail.title)
        assertEquals("Bright roast", detail.description)
        assertEquals(4, detail.servings)
        assertEquals(10, detail.prepMinutes)
        assertEquals(25, detail.cookMinutes)
        assertEquals(listOf("bright", "quick"), detail.tags)
        assertEquals(listOf("dinner"), detail.mealTypes)
        assertFalse(detail.isFavorite)
        assertTrue(detail.createdAt != null)
        assertEquals(
            "1. Season chicken\nwith herbs\n\n2) Cook for six minutes.\n3. Serve.",
            detail.stepsMarkdown,
        )

        // Ingredients arrive in sortOrder. A null quantity means "to taste" and
        // a null unit means a bare count; both must survive parsing as null.
        assertEquals(listOf("Lemon", "Parsley", "Chicken"), detail.ingredients.map { it.name })
        assertEquals(listOf(0, 1, 2), detail.ingredients.map { it.sortOrder })
        val lemon = detail.ingredients[0]
        assertEquals(2.0, lemon.quantity!!, 0.0)
        assertNull("a bare count has no unit", lemon.unit)
        assertEquals("produce", lemon.section)
        val parsley = detail.ingredients[1]
        assertNull("null quantity must survive parsing", parsley.quantity)
        assertNull(parsley.unit)
        assertEquals("produce", parsley.section)
        val chicken = detail.ingredients[2]
        assertEquals(600.0, chicken.quantity!!, 0.0)
        assertEquals("g", chicken.unit)
        assertEquals("meat-seafood", chicken.section)
        assertEquals("ingredient rows carry server ids", 3, detail.ingredients.map { it.id }.toSet().size)

        // Distinct plans, newest first.
        assertEquals(
            listOf(Triple(2, "Other", "Other"), Triple(1, "Fixture", "Fixture")),
            detail.usedInPlans.map { Triple(it.planId, it.name, it.displayName) },
        )

        val beanDetail = mealforge.recipe(2)
        if (beanDetail == null) {
            fail("fixture seeds recipe 2")
            return@runBlocking
        }
        assertEquals(2, beanDetail.servings)
        assertNull(beanDetail.prepMinutes)
        assertEquals(15, beanDetail.cookMinutes)
        assertTrue(beanDetail.isFavorite)
        assertEquals("Warm beans.\n\nServe in bowls.", beanDetail.stepsMarkdown)
        assertEquals(1, beanDetail.ingredients.size)
        val beans = beanDetail.ingredients.single()
        assertEquals("Beans", beans.name)
        assertEquals(1.0, beans.quantity!!, 0.0)
        assertEquals("tin", beans.unit)
        assertEquals("pantry", beans.section)
        assertEquals(
            listOf(
                Triple(4, "Weekend", "Weekend"),
                Triple(3, null, "Meal Plan 3"),
                Triple(1, "Fixture", "Fixture"),
            ),
            beanDetail.usedInPlans.map { Triple(it.planId, it.name, it.displayName) },
        )

        // Missing recipe is null; the contract explicitly distinguishes that
        // from a rejected request.
        assertNull(mealforge.recipe(99))
        assertServerFailure {
            mealforge.recipe(0)
        }
        // An out-of-range limit fails the router schema instead of being clamped.
        assertServerFailure {
            mealforge.recipes(limit = 201)
        }
    }

    @Test
    fun favoriteToggleIsConfirmedByServerReadsAndRefusals() = runBlocking {
        val mealforge = client!!

        val favorited = mealforge.toggleRecipeFavorite(1)
        assertEquals(1, favorited.id)
        assertEquals("Lemon chicken", favorited.title)
        assertTrue(favorited.isFavorite)
        assertEquals("the mutation response is a full summary", 4, favorited.servings)
        assertEquals(listOf("bright", "quick"), favorited.tags)

        // Only the server's next read confirms the write.
        assertEquals(listOf(2, 1), mealforge.recipes(favoritesOnly = true).map { it.id })
        val stored = mealforge.recipes().single { it.id == 1 }
        assertTrue(stored.isFavorite)
        assertEquals(favorited, stored)
        assertTrue("detail sees the same favorite state", mealforge.recipe(1)!!.isFavorite)

        // Toggling again restores only the seeded favorite.
        val unfavorited = mealforge.toggleRecipeFavorite(1)
        assertFalse(unfavorited.isFavorite)
        assertEquals(listOf(2), mealforge.recipes(favoritesOnly = true).map { it.id })
        assertEquals(unfavorited, mealforge.recipes().single { it.id == 1 })
        assertFalse(mealforge.recipe(1)!!.isFavorite)

        // A missing recipe is a visible error and must change nothing.
        assertServerFailure("Recipe 99 does not exist.") {
            mealforge.toggleRecipeFavorite(99)
        }
        assertEquals(listOf(2), mealforge.recipes(favoritesOnly = true).map { it.id })
    }

    /** Asserts the call fails with a message and keeps the backend text. */
    private suspend fun assertServerFailure(
        expectedMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        val failure = runCatching { block() }.exceptionOrNull()
        if (failure == null) {
            fail("expected the server to reject the request")
            return
        }
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue("failure carried no message: $failure", messages.any { it.isNotBlank() })
        if (expectedMessage != null) {
            assertTrue(
                "expected server message containing '$expectedMessage', got $messages",
                messages.any { it.contains(expectedMessage) },
            )
        }
    }
}
