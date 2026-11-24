package com.mealforge.lightphone

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrpcProtocolTest {
    @Test
    fun noInputQueryDoesNotInventAnInputEnvelope() {
        assertEquals("/trpc/plans.active", TrpcProtocol.queryPath("plans.active", null))
    }

    @Test
    fun groceryQueryEncodesPlainJsonInputWithoutBatchOrTransformerWrappers() {
        val path = TrpcProtocol.queryPath("grocery.itemsForPlan", "{\"planId\":7}")
        val uri = URI(path)
        assertEquals("/trpc/grocery.itemsForPlan", uri.path)
        val parts = uri.rawQuery.split("=")
        assertEquals(2, parts.size)
        assertEquals("input", parts[0])
        assertEquals(Json.parseToJsonElement("{\"planId\":7}"), Json.parseToJsonElement(URLDecoder.decode(parts[1], "UTF-8")))
    }

    @Test
    fun recipeSearchQueryKeepsUnicodeAndReservedCharactersInsideInput() {
        val input = "{\"query\":\"crème & oats + lemon?\"}"
        val path = TrpcProtocol.queryPath("recipes.list", input)
        val uri = URI(path)
        assertEquals(null, uri.fragment)
        assertEquals(Json.parseToJsonElement(input), Json.parseToJsonElement(URLDecoder.decode(uri.rawQuery.substringAfter("input="), "UTF-8")))
    }

    @Test
    fun groceryResponsePreservesServerIdsBooleansAndFormattedQuantity() {
        val data = """[{"id":1,"planId":1,"name":"Spinach","quantityText":"200 g","section":"produce","checked":false,"isManual":false,"sortOrder":0}]"""
        assertEquals(Json.parseToJsonElement(data), resultData("""{"result":{"data":$data}}"""))
    }

    @Test
    fun absentPlanAndEmptyGroceryListRemainDifferentStates() {
        assertEquals(Json.parseToJsonElement("null"), resultData("""{"result":{"data":null}}"""))
        assertEquals(Json.parseToJsonElement("[]"), resultData("""{"result":{"data":[]}}"""))
    }

    @Test
    fun mutationResponseUnwrapsTheFullReturnedItem() {
        val data = """{"id":9,"planId":7,"checked":false,"name":"Milk","quantityText":"1 tub","section":"dairy-eggs","isManual":true,"sortOrder":10000}"""
        assertEquals(Json.parseToJsonElement(data), resultData("""{"result":{"data":$data}}"""))
    }

    @Test
    fun serverErrorsDoNotDecodeAsSuccessfulWrites() {
        val body = """{"error":{"message":"Only manually added items can be removed from the list.","code":-32603,"data":{"code":"INTERNAL_SERVER_ERROR","httpStatus":500,"path":"grocery.removeManualItem"}}}"""
        val error = assertThrows(IllegalStateException::class.java) { resultData(body) }
        assertTrue(error.message.orEmpty().contains("Only manually added items can be removed"))
    }

    @Test
    fun missingResultDataDoesNotMasqueradeAsAnAbsentPlan() {
        assertThrows(IllegalStateException::class.java) { resultData("""{"result":{}}""") }
    }

    private fun resultData(body: String): JsonElement = TrpcProtocol.resultData(body)
}
