package com.mealforge.lightphone

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import java.math.BigDecimal

internal class RecipeDetailScreen(activity: SealedLightActivity, private val url: String, private val recipeId: Int) : NativeScreen<Unit>(activity) {
    private val client = MealforgeClient(url)
    private val store = RecipeStore(client, scope, recipeId)

    override fun willShow() { store.refresh() }
    override fun willHide() { store.stop(); super.willHide() }
    override fun onScreenDestroy() { client.close(); super.onScreenDestroy() }

    @Composable
    override fun Content() {
        val state by store.state.collectAsState()
        val recipe = state.recipe
        val groups = remember(recipe?.ingredients) {
            val all = recipe?.ingredients?.groupBy { it.section }.orEmpty()
            (STORE_SECTIONS.keys + all.keys).distinct().mapNotNull { section -> all[section]?.let { section to it } }
        }
        Page("Recipe") {
            if (state.loading) Detail("Loading recipe...")
            if (state.isStale) Detail("STALE: changes disabled until refresh succeeds.")
            state.error?.let { Detail(it) }
            if (recipe == null) {
                if (!state.loading && state.error == null) Detail("This recipe doesn't exist. It may have been replaced in a plan revision.")
            } else {
                LightText(recipe.title, LightTextVariant.Subtitle)
                Detail("Serves ${recipe.servings}")
                recipe.prepMinutes?.let { Detail("Prep $it min") }
                recipe.cookMinutes?.let { Detail("Cook $it min") }
                if (recipe.mealTypes.isNotEmpty()) Detail(recipe.mealTypes.joinToString(" • ") { MEAL_LABELS[it] ?: it })
                if (recipe.description.isNotEmpty()) LightText(recipe.description, LightTextVariant.Paragraph)
                Choice(if (state.pending) "Saving favorite..." else if (recipe.isFavorite) "Remove from favorites" else "Favorite recipe",
                    !state.pending && !state.loading && !state.isStale) { store.toggleFavorite() }
                Separator()
                LightText("Ingredients", LightTextVariant.Subheading)
                groups.forEach { (section, ingredients) ->
                    if (groups.size > 1) Detail(STORE_SECTIONS[section] ?: section)
                    ingredients.forEach { ingredient ->
                        val amount = ingredient.quantity?.let {
                            BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() +
                                (ingredient.unit?.takeIf { unit -> unit.isNotEmpty() }?.let { unit -> " $unit" } ?: "")
                        } ?: "to taste"
                        LightText("- ${ingredient.name}: $amount", LightTextVariant.Paragraph)
                    }
                }
                Separator()
                LightText("Instructions", LightTextVariant.Subheading)
                // Literal text keeps untrusted HTML inert and preserves all source instructions.
                LightText(recipe.stepsMarkdown, LightTextVariant.ParagraphWide)
                if (recipe.usedInPlans.isNotEmpty()) {
                    val uses = recipe.usedInPlans.size
                    Detail("${if (uses == 1) "Cooked once" else "Cooked ${uses} times"} • last in ${recipe.usedInPlans.first().displayName}")
                }
                Choice("Start cooking", !state.pending && !state.loading && !state.isStale) {
                    navigateTo({ CookScreen(it, recipe) })
                }
            }
            Choice("Refresh", !state.pending && !state.loading) { store.refresh() }
        }
    }
}

private class CookScreen(activity: SealedLightActivity, private val recipe: RecipeDetail) : NativeScreen<Unit>(activity) {
    private val session = CookSession(recipe.stepsMarkdown)

    override fun willShow() { session.show() }
    override fun willHide() { session.hide(); super.willHide() }
    override fun onScreenDestroy() { session.exit(); super.onScreenDestroy() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != 317 && keyCode != 318) return super.onKeyDown(keyCode, event)
        session.wheel(forward = keyCode == 318)
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyCode == 317 || keyCode == 318 || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        val progress by session.state.collectAsState()
        key(progress.index) {
            Page("Cook mode", modifier = if (progress.keepAwake) Modifier.keepScreenOn() else Modifier, backLabel = "EXIT") {
                Detail("${progress.index + 1} / ${progress.steps.size}")
                LightText(recipe.title, LightTextVariant.Subtitle)
                Separator()
                LightText(progress.current, LightTextVariant.Copy)
                Separator()
                Detail("Wheel: change step")
                Choice("Previous", progress.index > 0 && progress.visible && !progress.finished) { session.previous() }
                Choice(if (progress.index == progress.steps.lastIndex) "Done, serve it" else "Next step", progress.visible && !progress.finished) {
                    session.next()
                    if (session.state.value.finished) goBack(Unit)
                }
            }
        }
    }
}
