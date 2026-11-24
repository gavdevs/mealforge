package com.mealforge.lightphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class RecipeFilters(val query: String = "", val favorites: Boolean = true, val mealType: String? = null)
private data class RecipeListRead(val items: List<RecipeSummary> = emptyList(), val loading: Boolean = true, val error: String? = null)

internal class RecipeListScreen(activity: SealedLightActivity, private val url: String) : NativeScreen<Unit>(activity) {
    private val client = MealforgeClient(url)
    private val filters = MutableStateFlow(RecipeFilters())
    private val read = MutableStateFlow(RecipeListRead())
    private var generation = 0
    private var request: Job? = null

    override fun willShow() {
        val token = ++generation
        request?.cancel()
        val selected = filters.value
        read.value = RecipeListRead()
        request = scope.launch {
            try {
                val items = client.recipes(query = selected.query.takeIf { it.isNotEmpty() },
                    favoritesOnly = selected.favorites, mealType = selected.mealType)
                if (token == generation) read.value = RecipeListRead(items, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation) read.value = read.value.copy(loading = false, error = error.message ?: "Could not read recipes.")
            }
        }
    }
    override fun willHide() { generation++; super.willHide() }
    override fun onScreenDestroy() { client.close(); super.onScreenDestroy() }

    @Composable
    override fun Content() {
        val state by read.collectAsState()
        val selected by filters.collectAsState()
        Page("Recipes") {
            Choice(if (selected.favorites) "Favorites (selected)" else "Favorites") {
                filters.value = selected.copy(favorites = true); willShow()
            }
            Choice(if (!selected.favorites) "History (selected)" else "History") {
                filters.value = selected.copy(favorites = false); willShow()
            }
            Choice("Search: ${selected.query.ifEmpty { "all recipes" }}") {
                navigateTo({ TextEditorScreen(it, "Search recipes", selected.query) }) { value ->
                    filters.value = filters.value.copy(query = value.trim()); willShow()
                }
            }
            if (selected.query.isNotEmpty()) Choice("Clear search") { filters.value = selected.copy(query = ""); willShow() }
            Choice("Meal category: ${MEAL_LABELS[selected.mealType] ?: "All"}") {
                navigateTo({ MealCategoryScreen(it, selected.mealType) }) { value ->
                    filters.value = filters.value.copy(mealType = value.takeIf { it.isNotEmpty() }); willShow()
                }
            }
            Separator()
            if (state.loading) Detail("Loading recipes...")
            state.error?.let { Detail(it); if (state.items.isNotEmpty()) Detail("STALE: showing the last read.") }
            if (!state.loading && state.error == null && state.items.isEmpty()) Detail(when {
                selected.query.isNotEmpty() || selected.mealType != null -> "No matches."
                selected.favorites -> "No favorites yet."
                else -> "No recipes yet."
            })
            state.items.forEach { recipe ->
                Choice(recipe.title) { navigateTo({ RecipeDetailScreen(it, url, recipe.id) }) }
                RecipeMetadata(recipe)
                Separator()
            }
            Choice("Refresh", !state.loading) { willShow() }
        }
    }
}

private class MealCategoryScreen(activity: SealedLightActivity, private val selected: String?) : NativeScreen<String>(activity) {
    @Composable
    override fun Content() {
        Page("Meal category") {
            Choice("All categories") { goBack("") }
            MEAL_LABELS.forEach { (key, label) ->
                Choice(if (key == selected) "$label (selected, clear)" else label) { goBack(if (key == selected) "" else key) }
            }
        }
    }
}

@Composable
internal fun RecipeMetadata(recipe: RecipeSummary) {
    val minutes = (recipe.prepMinutes ?: 0) + (recipe.cookMinutes ?: 0)
    if (minutes > 0) Detail(if (minutes >= 90) String.format(Locale.ROOT, "%.1f hr", minutes / 60.0) else "$minutes min")
    if (recipe.tags.isNotEmpty()) Detail(recipe.tags.take(3).joinToString(" • "))
    Detail(if (recipe.isFavorite) "Favorite recipe" else "Not a favorite")
}
