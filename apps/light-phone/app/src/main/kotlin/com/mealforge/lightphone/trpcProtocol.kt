package com.mealforge.lightphone

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object TrpcProtocol {
    fun queryPath(procedure: String, input: String?): String {
        val path = "/trpc/$procedure"
        if (input == null) return path
        return "$path?input=${URLEncoder.encode(input, "UTF-8").replace("+", "%20")}"
    }

    fun resultData(body: String): JsonElement {
        val envelope = Json.parseToJsonElement(body) as? JsonObject
            ?: error("MealForge returned an invalid response.")
        val failure = envelope["error"] as? JsonObject
        if (failure != null) {
            error(failure["message"]?.jsonPrimitive?.contentOrNull ?: "MealForge request failed.")
        }
        val result = envelope["result"] as? JsonObject
            ?: error("MealForge returned no result.")
        return result["data"] ?: error("MealForge returned no result data.")
    }
}
