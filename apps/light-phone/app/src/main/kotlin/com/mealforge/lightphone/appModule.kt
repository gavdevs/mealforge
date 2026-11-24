package com.mealforge.lightphone

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

@Serializable
data class CachedGroceries(
    val serverUrl: String,
    val savedAt: Long,
    val plan: ActivePlan?,
    val items: List<GroceryItem>,
)

class AppModule(serverUrl: String, scope: CoroutineScope, cached: CachedGroceries? = null) : AutoCloseable {
    val client = MealforgeClient(serverUrl)
    val groceries = GroceryStore(
        client,
        scope,
        GroceryState(plan = cached?.plan, items = cached?.items ?: emptyList(), isStale = cached != null),
    )

    override fun close() {
        groceries.stop()
        client.close()
    }
}
