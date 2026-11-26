package com.mealforge.lightphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class RecipeFilters(val query: String = "", val favorites: Boolean = true, val mealType: String? = null)
private data class RecipeListRead(
    val items: List<RecipeSummary> = emptyList(), val loading: Boolean = true, val error: String? = null,
)

internal class RecipesPanel(
    private val client: MealforgeClient, private val scope: CoroutineScope,
    private val openRecipe: (Int) -> Unit, private val editText: EditNativeText,
) : MealforgePanel {
    private val filters = MutableStateFlow(RecipeFilters())
    private val read = MutableStateFlow(RecipeListRead())
    private val favorites = RecipeCardActions(client, scope) { recipe ->
        val selected = filters.value
        read.value = read.value.copy(items = read.value.items.mapNotNull {
            if (it.id != recipe.id) it else if (selected.favorites && !recipe.isFavorite) null else recipe
        })
    }
    private var visible = false
    private var generation = 0
    private var request: Job? = null

    override fun show() { visible = true; reload() }
    override fun hide() {
        visible = false
        generation++
        request?.cancel()
        favorites.stop()
    }

    private fun reload(clear: Boolean = false) {
        if (!visible) return
        val token = ++generation
        request?.cancel()
        val selected = filters.value
        read.value = if (clear) RecipeListRead() else read.value.copy(loading = true, error = null)
        request = scope.launch {
            try {
                val items = client.recipes(selected.query.takeIf { it.isNotEmpty() }, selected.favorites, selected.mealType)
                if (token == generation && visible) {
                    read.value = RecipeListRead(items, loading = false)
                    favorites.resume()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation && visible) read.value = read.value.copy(
                    loading = false, error = error.message ?: "Could not read recipes.")
            }
        }
    }

    private fun choose(selected: RecipeFilters) {
        if (selected == filters.value) return
        favorites.stop()
        filters.value = selected
        reload(clear = true)
    }

    private fun toggle(id: Int) {
        if (!visible || read.value.loading || read.value.error != null) return
        val job = favorites.toggle(id) ?: return
        scope.launch {
            job.join()
            if (visible && !job.isCancelled) reload()
        }
    }

    @Composable
    override fun Content() {
        val selected by filters.collectAsState()
        val state by read.collectAsState()
        val actions by favorites.state.collectAsState()
        LightTextField("Search title, tag, or ingredient", selected.query, "Search recipes",
            onClick = { editText("Search recipes", selected.query) { choose(filters.value.copy(query = it.trim())) } })
        if (selected.query.isNotEmpty()) ActionButton("Clear search") { choose(selected.copy(query = "")) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.3f.gridUnitsAsDp())) {
            ActionButton("Favorites", selected = selected.favorites, modifier = Modifier.weight(1f)) { choose(selected.copy(favorites = true)) }
            ActionButton("History", selected = !selected.favorites, modifier = Modifier.weight(1f)) { choose(selected.copy(favorites = false)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.3f.gridUnitsAsDp())) {
            ActionButton("All", selected = selected.mealType == null, modifier = Modifier.weight(1f)) {
                choose(selected.copy(mealType = null))
            }
            MEAL_LABELS.entries.take(2).forEach { (key, label) ->
                ActionButton(label, selected = selected.mealType == key, modifier = Modifier.weight(1f)) {
                    choose(selected.copy(mealType = if (selected.mealType == key) null else key))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.3f.gridUnitsAsDp())) {
            MEAL_LABELS.entries.drop(2).forEach { (key, label) ->
                ActionButton(label, selected = selected.mealType == key, modifier = Modifier.weight(1f)) {
                    choose(selected.copy(mealType = if (selected.mealType == key) null else key))
                }
            }
        }
        if (state.loading) BodyText("Loading recipes...", true)
        state.error?.let { BodyText(it); BodyText("Last read only. Refresh before changing favorites.", true) }
        actions.error?.let { BodyText(it) }
        if (!state.loading && state.error == null && state.items.isEmpty()) {
            SectionHeading(when {
                selected.query.isNotEmpty() -> "No matches"
                selected.favorites -> "No favorites yet"
                else -> "No recipes yet"
            })
            BodyText(when {
                selected.query.isNotEmpty() -> "Nothing in ${if (selected.favorites) "favorites" else "history"} matches \"${selected.query}\". Try a title, tag or ingredient, or clear the search."
                selected.favorites -> "Favorite a recipe you'd cook again. Your assistant can bring it back any time."
                else -> "Every recipe from your meal plans collects here automatically."
            }, true)
        }
        state.items.forEach { recipe ->
            RecipeCardView(recipe.title, recipe.prepMinutes, recipe.cookMinutes, recipe.tags, recipe.isFavorite,
                onOpen = { openRecipe(recipe.id) }, onFavorite = { toggle(recipe.id) },
                pending = recipe.id in actions.pendingIds, enabled = !state.loading && state.error == null)
        }
        ActionButton("Refresh", !state.loading && actions.pendingIds.isEmpty()) { reload() }
    }
}
