package com.mealforge.lightphone

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Drives the real [MealforgeClient] against the test-only loopback fixture in
 * `apps/light-phone/contract/fixtureServer.ts`: the unchanged Hono/tRPC
 * `buildApp(db)` over an in-memory SQLite database with explicit fixture rows.
 *
 * Nothing here mocks the client, the protocol or the server. Every assertion
 * about confirmed state is re-read over HTTP from the same real server, so a
 * write only "counts" when the next `groceryItems` call reports it.
 *
 * Fixture records live in `FixtureServer`/`contract/fixtureServer.ts`: plans 1-4
 * (active `Fixture`, upcoming `Other`, unnamed upcoming, favorite completed
 * `Weekend`), recipes 1-2, meals 1-5 and grocery rows 1-2. Each test gets a
 * fresh server + database, so mutations never leak between tests.
 */
class MealforgeClientIntegrationTest {
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
    fun activePlanAndGroceryItemsComeFromTheRealServer() = runBlocking {
        val mealforge = client!!

        val plan = mealforge.activePlan()
        if (plan == null) {
            fail("fixture seeds one active plan")
            return@runBlocking
        }
        assertEquals(1, plan.planId)

        val items = mealforge.groceryItems(1)
        assertEquals(1, items.size)
        val spinach = items.single()
        assertEquals(1, spinach.id)
        assertEquals(1, spinach.planId)
        assertEquals("Spinach", spinach.name)
        assertEquals("200 g", spinach.quantityText)
        assertEquals("produce", spinach.section)
        assertFalse(spinach.checked)
        assertFalse(spinach.isManual)
        assertEquals(0, spinach.sortOrder)

        // Grocery reads are scoped by plan; plan 2's row must never leak into
        // plan 1's list.
        val otherItems = mealforge.groceryItems(2)
        assertEquals(1, otherItems.size)
        assertEquals(2, otherItems.single().planId)
        assertEquals("Other plan item", otherItems.single().name)
    }

    @Test
    fun manualItemAddCheckRecheckUncheckRemoveAreConfirmedByServerReads() = runBlocking {
        val mealforge = client!!

        val added = mealforge.addManualItem(1, "Paper towels", "1 roll", "pantry")
        val itemId = added.id
        assertTrue("server assigns a positive id", itemId > 0)
        assertEquals(1, added.planId)
        assertEquals("Paper towels", added.name)
        assertEquals("1 roll", added.quantityText)
        assertEquals("pantry", added.section)
        assertFalse(added.checked)
        assertTrue(added.isManual)
        assertEquals(10_000, added.sortOrder)

        // The server, not the client, is the confirmation: read the list back.
        val afterAdd = mealforge.groceryItems(1)
        assertEquals(listOf(1, itemId), afterAdd.map { it.id })
        assertEquals(added, afterAdd.last())

        val checked = mealforge.setChecked(itemId, true)
        assertEquals(added.copy(checked = true), checked)
        assertEquals(added.copy(checked = true), mealforge.groceryItems(1).last())

        // Re-checking an already checked item stays checked; it is not a toggle.
        val rechecked = mealforge.setChecked(itemId, true)
        assertTrue(rechecked.checked)
        assertTrue(mealforge.groceryItems(1).last().checked)

        val unchecked = mealforge.setChecked(itemId, false)
        assertFalse(unchecked.checked)
        assertEquals(added, unchecked)
        assertEquals(added, mealforge.groceryItems(1).last())

        mealforge.removeManualItem(itemId)
        val afterRemove = mealforge.groceryItems(1)
        assertEquals(listOf(1), afterRemove.map { it.id })
        assertEquals("Spinach", afterRemove.single().name)
    }

    @Test
    fun manualItemDefaultsComeFromTheServer() = runBlocking {
        val mealforge = client!!

        val added = mealforge.addManualItem(1, "  Bananas  ", null, null)
        assertEquals("Bananas", added.name)
        assertEquals("", added.quantityText)
        assertEquals("other", added.section)
        assertTrue(added.isManual)
        assertFalse(added.checked)
        assertEquals(10_000, added.sortOrder)

        // Defaults survive a real read, and the row belongs to plan 1 only.
        val stored = mealforge.groceryItems(1).single { it.id == added.id }
        assertEquals(added, stored)
        assertTrue(mealforge.groceryItems(2).none { it.id == added.id })

        mealforge.removeManualItem(added.id)
        assertTrue(mealforge.groceryItems(1).none { it.id == added.id })
    }

    @Test
    fun generatedItemRemovalSurfacesTheServerRefusalAndKeepsTheItem() = runBlocking {
        val mealforge = client!!

        assertServerFailure("Only manually added items can be removed from the list.") {
            mealforge.removeManualItem(1)
        }

        // The refusal must not have deleted anything.
        val items = mealforge.groceryItems(1)
        assertEquals(listOf(1), items.map { it.id })
        assertFalse(items.single().isManual)
    }

    @Test
    fun missingCheckTargetSurfacesThePlainServerError() = runBlocking {
        val mealforge = client!!

        assertServerFailure("Grocery item 99 does not exist.") {
            mealforge.setChecked(99, true)
        }

        val items = mealforge.groceryItems(1)
        assertEquals(listOf(1), items.map { it.id })
        assertFalse("a server error must not confirm a write", items.single().checked)
    }

    @Test
    fun invalidCheckTargetFailsVisiblyWithoutConfirmingAWrite() = runBlocking {
        val mealforge = client!!

        // itemId 0 fails the router's Zod schema (HTTP 400). Whether the client
        // or the server rejects it, the call must fail with a message rather
        // than returning a fabricated row.
        assertServerFailure {
            mealforge.setChecked(0, true)
        }

        val items = mealforge.groceryItems(1)
        assertEquals(listOf(1), items.map { it.id })
        assertFalse(items.single().checked)
    }

    /**
     * Asserts the call fails with a message, and — when [expectedMessage] is
     * given — that the server's text reaches the caller, unwrapping
     * client/transport exception chains so the real backend message is kept.
     */
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
