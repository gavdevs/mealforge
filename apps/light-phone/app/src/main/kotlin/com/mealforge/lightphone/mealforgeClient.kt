package com.mealforge.lightphone

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Serializable
data class PlanMeal(
    val mealId: Int,
    val mealType: String,
    val recipeId: Int,
    val title: String,
    val cookedAt: String? = null,
)

@Serializable
data class ActivePlan(
    val planId: Int,
    val displayName: String,
    val status: String,
    val meals: List<PlanMeal> = emptyList(),
    val isFavorite: Boolean = false,
    val createdAt: String? = null,
    val name: String? = null,
    val completedAt: String? = null,
)

@Serializable
data class GroceryItem(
    val id: Int,
    val planId: Int,
    val name: String,
    val quantityText: String,
    val section: String,
    val checked: Boolean,
    val isManual: Boolean,
    val sortOrder: Int,
)

fun normalizeServerUrl(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
        "Enter an http:// or https:// server origin."
    }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Server URL must not contain credentials, a query or a fragment."
    }
    require(uri.path.isNullOrEmpty() || uri.path == "/") { "Enter the server origin, without /trpc or another path." }
    return uri.toString().trimEnd('/')
}

class MealforgeClient(baseUrl: String) : AutoCloseable {
    private val base = normalizeServerUrl(baseUrl)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        engine { config { retryOnConnectionFailure(false) } }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    suspend fun activePlan(): ActivePlan? {
        val data = query("plans.active")
        return if (data == JsonNull) null else json.decodeFromJsonElement<ActivePlan>(data)
    }

    suspend fun plans(
        status: String? = null,
        favoritesOnly: Boolean = false,
        limit: Int = 50,
    ): List<ActivePlan> = json.decodeFromJsonElement(query("plans.list", buildJsonObject {
        if (status != null) put("status", status)
        put("favoritesOnly", favoritesOnly)
        put("limit", limit)
    }))

    suspend fun plan(planId: Int): ActivePlan? {
        val data = query("plans.byId", buildJsonObject { put("planId", planId) })
        return if (data == JsonNull) null else json.decodeFromJsonElement<ActivePlan>(data)
    }

    suspend fun activatePlan(planId: Int): ActivePlan =
        json.decodeFromJsonElement(mutate("plans.activate", buildJsonObject { put("planId", planId) }))

    suspend fun completePlan(planId: Int): ActivePlan =
        json.decodeFromJsonElement(mutate("plans.complete", buildJsonObject { put("planId", planId) }))

    suspend fun renamePlan(planId: Int, name: String?): ActivePlan =
        json.decodeFromJsonElement(mutate("plans.rename", buildJsonObject {
            put("planId", planId)
            put("name", name)
        }))

    suspend fun togglePlanFavorite(planId: Int): ActivePlan =
        json.decodeFromJsonElement(mutate("plans.toggleFavorite", buildJsonObject { put("planId", planId) }))

    suspend fun setMealCooked(mealId: Int, cooked: Boolean): ActivePlan =
        json.decodeFromJsonElement(mutate("plans.setMealCooked", buildJsonObject {
            put("mealId", mealId)
            put("cooked", cooked)
        }))

    suspend fun groceryItems(planId: Int): List<GroceryItem> =
        json.decodeFromJsonElement(query("grocery.itemsForPlan", buildJsonObject { put("planId", planId) }))

    suspend fun setChecked(itemId: Int, checked: Boolean): GroceryItem =
        json.decodeFromJsonElement(mutate("grocery.setChecked", buildJsonObject {
            put("itemId", itemId)
            put("checked", checked)
        }))

    suspend fun addManualItem(
        planId: Int,
        name: String,
        quantityText: String?,
        section: String?,
    ): GroceryItem = json.decodeFromJsonElement(mutate("grocery.addManualItem", buildJsonObject {
        put("planId", planId)
        put("name", name)
        if (quantityText != null) put("quantityText", quantityText)
        if (section != null) put("section", section)
    }))

    suspend fun removeManualItem(itemId: Int) {
        val data = mutate("grocery.removeManualItem", buildJsonObject { put("itemId", itemId) })
        check((data as? JsonObject)?.get("ok") == JsonPrimitive(true)) { "MealForge did not confirm removal." }
    }

    suspend fun recipes(
        query: String? = null,
        favoritesOnly: Boolean = false,
        mealType: String? = null,
        limit: Int = 100,
    ): List<RecipeSummary> = json.decodeFromJsonElement(this.query("recipes.list", buildJsonObject {
        if (query != null) put("query", query.trim())
        put("favoritesOnly", favoritesOnly)
        if (mealType != null) put("mealType", mealType)
        put("limit", limit)
    }))

    suspend fun recipe(id: Int): RecipeDetail? {
        val data = query("recipes.byId", buildJsonObject { put("id", id) })
        return if (data == JsonNull) null else json.decodeFromJsonElement<RecipeDetail>(data)
    }

    suspend fun toggleRecipeFavorite(id: Int): RecipeSummary =
        json.decodeFromJsonElement(mutate("recipes.toggleFavorite", buildJsonObject { put("id", id) }))

    private suspend fun query(procedure: String, input: JsonObject? = null): JsonElement {
        val response = http.get(base + TrpcProtocol.queryPath(procedure, input?.toString()))
        val data = TrpcProtocol.resultData(response.bodyAsText())
        check(response.status.value in 200..299) { "MealForge HTTP ${response.status.value}" }
        return data
    }

    private suspend fun mutate(procedure: String, input: JsonObject): JsonElement {
        val response = http.post("$base/trpc/$procedure") {
            contentType(ContentType.Application.Json)
            setBody(input.toString())
        }
        val data = TrpcProtocol.resultData(response.bodyAsText())
        check(response.status.value in 200..299) { "MealForge HTTP ${response.status.value}" }
        return data
    }

    override fun close() {
        http.close()
    }
}
