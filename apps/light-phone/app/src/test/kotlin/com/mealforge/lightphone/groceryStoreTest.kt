package com.mealforge.lightphone

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for the grocery store over the real Ktor/OkHttp client and a
 * disposable local tRPC server. Assertions read the store's actual StateFlow
 * after server confirmation; no internal collaborator is mocked.
 */
class GroceryStoreTest {

    @Test
    fun refreshLoadsActivePlanAndPreservesServerItemOrder() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(
            listOf(
                TestFixture.item(id = 11, name = "garlic", quantityText = "2 cloves", section = "produce"),
                TestFixture.item(id = 12, name = "milk", quantityText = "3½ cups", section = "dairy-eggs"),
                TestFixture.item(id = 13, name = "salt", quantityText = "", section = "spices"),
            ),
        )

        val job = store.refresh()
        val loaded = store.awaitState { !it.loading && it.plan != null && it.items.size == 3 }
        job.join()

        assertEquals(TestFixture.PLAN.planId, loaded.plan!!.planId)
        assertEquals("Tracer Kitchen", loaded.plan!!.displayName)
        assertEquals("active", loaded.plan!!.status)
        assertEquals(listOf("garlic", "milk", "salt"), loaded.items.map { it.name })
        assertEquals(listOf("2 cloves", "3½ cups", ""), loaded.items.map { it.quantityText })
        assertEquals(listOf("produce", "dairy-eggs", "spices"), loaded.items.map { it.section })
        assertTrue(loaded.items.all { !it.checked && !it.isManual })
        assertNull(loaded.error)

        val itemsReads = server.gets("grocery.itemsForPlan")
        assertEquals(1, itemsReads.size)
        assertEquals(
            TestFixture.PLAN.planId,
            itemsReads.single().inputJson()?.get("planId")?.jsonInt(),
        )
    }

    @Test
    fun checkAndUncheckApplyTheServerReturnedRow() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(
            listOf(
                TestFixture.item(id = 21, name = "milk", quantityText = "1½ cups", section = "dairy-eggs"),
                TestFixture.item(id = 22, name = "oats", quantityText = "2 cups", section = "pantry"),
            ),
        )
        store.refresh().also { job ->
            store.awaitState { !it.loading && it.items.size == 2 }
            job.join()
        }

        val checkJob = store.setChecked(21, true)
        assertNotNull(checkJob)
        val checked = store.awaitState { state ->
            state.items.any { it.id == 21 && it.checked } && state.pendingItemIds.isEmpty()
        }
        checkJob!!.join()

        assertTrue(checked.items.single { it.id == 21 }.checked)
        assertEquals("1½ cups", checked.items.single { it.id == 21 }.quantityText)
        assertFalse(checked.items.single { it.id == 22 }.checked)
        assertNull(checked.error)

        val uncheckJob = store.setChecked(21, false)
        assertNotNull(uncheckJob)
        val unchecked = store.awaitState { state ->
            state.items.any { it.id == 21 && !it.checked } && state.pendingItemIds.isEmpty()
        }
        uncheckJob!!.join()

        assertFalse(unchecked.items.single { it.id == 21 }.checked)
        val writes = server.posts("grocery.setChecked")
        assertEquals(2, writes.size)
        assertEquals(21, writes[0].bodyJson()["itemId"]?.jsonInt())
        assertEquals(true, writes[0].bodyJson()["checked"]?.jsonBoolean())
        assertEquals(21, writes[1].bodyJson()["itemId"]?.jsonInt())
        assertEquals(false, writes[1].bodyJson()["checked"]?.jsonBoolean())
    }

    @Test
    fun repeatedTapWhileTheSameRowIsInFlightWritesOnce() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(listOf(TestFixture.item(id = 31, name = "milk", quantityText = "1 cup")))
        store.refresh().also { job ->
            store.awaitState { !it.loading && it.items.size == 1 }
            job.join()
        }

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.gateNextPost("grocery.setChecked", started, release)

        val first = store.setChecked(31, true)
        assertNotNull(first)
        assertTrue("first write never reached the server", started.await(5, TimeUnit.SECONDS))
        assertTrue(31 in store.awaitState { 31 in it.pendingItemIds }.pendingItemIds)

        assertNull(
            "a duplicate tap while the row is in flight must be dropped",
            store.setChecked(31, true),
        )

        release.countDown()
        first!!.join()

        val settled = store.awaitState { state ->
            state.items.any { it.id == 31 && it.checked } && state.pendingItemIds.isEmpty()
        }
        assertTrue(settled.items.single { it.id == 31 }.checked)
        assertEquals(1, server.posts("grocery.setChecked").size)
    }

    @Test
    fun refusedCheckKeepsConfirmedItemsAndSurfacesTheServerError() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(listOf(TestFixture.item(id = 41, name = "milk", quantityText = "1 cup")))
        store.refresh().also { job ->
            store.awaitState { !it.loading && it.items.size == 1 }
            job.join()
        }
        server.failSetCheckedMessage = "Grocery item 41 does not exist."

        val rejectJob = store.setChecked(41, true)
        assertNotNull(rejectJob)
        store.awaitState { it.error != null }
        rejectJob!!.join()

        val failed = store.state.value
        val message = failed.error.orEmpty()
        assertTrue("error should carry the server refusal, was: $message", message.contains("does not exist"))
        assertFalse("a refused write must not leave a confirmed check", failed.items.single { it.id == 41 }.checked)
        assertTrue(failed.pendingItemIds.isEmpty())
        assertEquals(1, server.posts("grocery.setChecked").size)
    }

    @Test
    fun aSlowRefreshCannotOverwriteANewerConfirmedList() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(listOf(TestFixture.item(id = 51, name = "milk", quantityText = "1 cup")))
        store.refresh().also { job ->
            store.awaitState { !it.loading && it.items.size == 1 }
            job.join()
        }

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.gateNextItemsRead(started, release)
        server.setItems(listOf(TestFixture.item(id = 51, name = "milk", quantityText = "stale amount")))

        val slow = store.refresh()
        assertTrue("slow refresh never reached the list endpoint", started.await(5, TimeUnit.SECONDS))

        server.setItems(listOf(TestFixture.item(id = 51, name = "milk", quantityText = "fresh amount")))
        val fresh = store.refresh()
        store.awaitState { state -> state.items.any { it.quantityText == "fresh amount" } }

        release.countDown()
        withTimeout(5_000) { listOf(slow, fresh).joinAll() }

        val settled = store.state.value
        assertEquals(listOf("fresh amount"), settled.items.map { it.quantityText })
        assertTrue(settled.pendingItemIds.isEmpty())
        assertNull(settled.error)
    }

    @Test
    fun stopDiscardsALateRefreshAndDropsFurtherWrites() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(listOf(TestFixture.item(id = 61, name = "milk", quantityText = "confirmed amount")))
        store.refresh().also { job ->
            store.awaitState { !it.loading && it.items.size == 1 }
            job.join()
        }

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.gateNextItemsRead(started, release)
        server.setItems(listOf(TestFixture.item(id = 61, name = "milk", quantityText = "after stop")))

        val late = store.refresh()
        assertTrue("late refresh never reached the list endpoint", started.await(5, TimeUnit.SECONDS))
        store.stop()
        release.countDown()
        withTimeout(5_000) { late.join() }

        assertEquals(listOf("confirmed amount"), store.state.value.items.map { it.quantityText })
        assertNull("add after stop must not start a write", store.addManualItem("paper towels", null, null))
        assertNull("check after stop must not start a write", store.setChecked(61, true))
        assertTrue(server.posts("grocery.addManualItem").isEmpty())
        assertTrue(server.posts("grocery.setChecked").isEmpty())
    }

    @Test
    fun generatedAndCheckedManualItemsHaveNoRemovalPath() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(
            listOf(
                TestFixture.item(id = 71, name = "spinach", quantityText = "200 g", section = "produce"),
                TestFixture.item(id = 72, name = "paper towels", isManual = true, checked = true),
                TestFixture.item(id = 73, name = "sponges", isManual = true, checked = false),
            ),
        )
        store.refresh().also { job ->
            store.awaitState { !it.loading && it.items.size == 3 }
            job.join()
        }

        assertNull("generated rows have no remove path", store.removeManualItem(71))
        assertNull("checked manual rows must be unchecked before removal", store.removeManualItem(72))
        assertTrue(server.posts("grocery.removeManualItem").isEmpty())

        val removeJob = store.removeManualItem(73)
        assertNotNull(removeJob)
        val removed = store.awaitState { state -> state.items.none { it.id == 73 } }
        removeJob!!.join()

        assertEquals(listOf(71, 72), removed.items.map { it.id })
        assertEquals(listOf(71, 72), server.items().map { it.id })
        val writes = server.posts("grocery.removeManualItem")
        assertEquals(1, writes.size)
        assertEquals(73, writes.single().bodyJson()["itemId"]?.jsonInt())
    }

    @Test
    fun manualAddSendsOptionalFieldsAndBindsTheSelectedPlan() = groceryTest { server, store ->
        server.setPlan(TestFixture.PLAN)
        server.setItems(
            listOf(TestFixture.item(id = 81, name = "spinach", quantityText = "200 g", section = "produce")),
        )
        store.refresh().also { job ->
            store.awaitState { !it.loading && it.items.size == 1 }
            job.join()
        }

        val defaultAdd = store.addManualItem("paper towels", null, null)
        assertNotNull(defaultAdd)
        val withDefault = store.awaitState { state -> state.items.any { it.name == "paper towels" } }
        defaultAdd!!.join()

        val defaultItem = withDefault.items.single { it.name == "paper towels" }
        assertEquals("", defaultItem.quantityText)
        assertEquals("other", defaultItem.section)
        assertTrue(defaultItem.isManual)
        assertFalse(defaultItem.checked)
        assertEquals(TestFixture.PLAN.planId, defaultItem.planId)

        val detailedAdd = store.addManualItem("lemons", "2 whole", "produce")
        assertNotNull(detailedAdd)
        val withDetailed = store.awaitState { state -> state.items.any { it.name == "lemons" } }
        detailedAdd!!.join()

        val detailedItem = withDetailed.items.single { it.name == "lemons" }
        assertEquals("2 whole", detailedItem.quantityText)
        assertEquals("produce", detailedItem.section)
        assertTrue(detailedItem.isManual)
        assertEquals(TestFixture.PLAN.planId, detailedItem.planId)

        val writes = server.posts("grocery.addManualItem")
        assertEquals(2, writes.size)
        assertEquals(TestFixture.PLAN.planId, writes[0].bodyJson()["planId"]?.jsonInt())
        assertEquals("paper towels", writes[0].bodyJson()["name"]?.jsonText())
        assertNull(writes[0].bodyJson()["quantityText"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
        assertEquals(TestFixture.PLAN.planId, writes[1].bodyJson()["planId"]?.jsonInt())
        assertEquals("2 whole", writes[1].bodyJson()["quantityText"]?.jsonText())
        assertEquals("produce", writes[1].bodyJson()["section"]?.jsonText())

        val reads = server.gets("grocery.itemsForPlan")
        assertTrue(reads.isNotEmpty())
        assertTrue(reads.all { it.inputJson()?.get("planId")?.jsonInt() == TestFixture.PLAN.planId })
    }

    private fun groceryTest(body: suspend CoroutineScope.(GroceryTestServer, GroceryStore) -> Unit) =
        runBlocking {
            GroceryTestServer().use { server ->
                val client = MealforgeClient(server.baseUrl)
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val store = GroceryStore(client, scope)
                try {
                    body(server, store)
                } finally {
                    store.stop()
                    scope.cancel()
                    client.close()
                }
            }
        }

    private suspend fun GroceryStore.awaitState(
        timeoutMs: Long = 5_000,
        predicate: (GroceryState) -> Boolean,
    ): GroceryState = withTimeout(timeoutMs) { state.first(predicate) }

    private object TestFixture {
        val PLAN = GroceryTestServer.TestPlan(planId = 42, displayName = "Tracer Kitchen", status = "active")

        fun item(
            id: Int,
            name: String,
            quantityText: String = "",
            section: String = "pantry",
            checked: Boolean = false,
            isManual: Boolean = false,
            sortOrder: Int = 0,
        ) = GroceryTestServer.TestItem(
            id = id,
            planId = PLAN.planId,
            name = name,
            quantityText = quantityText,
            section = section,
            checked = checked,
            isManual = isManual,
            sortOrder = sortOrder,
        )
    }
}

private fun JsonElement?.jsonInt(): Int? =
    (this as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonElement?.jsonBoolean(): Boolean? =
    (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun JsonElement?.jsonText(): String? =
    (this as? JsonPrimitive)?.content
