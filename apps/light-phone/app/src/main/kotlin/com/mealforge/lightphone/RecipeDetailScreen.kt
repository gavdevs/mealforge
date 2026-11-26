package com.mealforge.lightphone

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.math.BigDecimal

internal class RecipeDetailScreen(
    activity: SealedLightActivity, private val url: String, private val recipeId: Int,
) : NativeScreen<MainSection>(activity) {
    private val client = MealforgeClient(url)
    private val store = RecipeStore(client, scope, recipeId)
    override fun willShow() { store.refresh() }
    override fun willHide() { store.stop(); super.willHide() }
    override fun onScreenDestroy() { client.close(); super.onScreenDestroy() }

    @Composable
    override fun Content() {
        val state by store.state.collectAsState()
        val recipe = state.recipe
        val enabled = !state.loading && !state.pending && !state.isStale
        val groups = remember(recipe?.ingredients) {
            val all = recipe?.ingredients?.groupBy { it.section }.orEmpty()
            (STORE_SECTIONS.keys + all.keys).distinct().mapNotNull { section -> all[section]?.let { section to it } }
        }
        Frame("Recipe", main = MainSection.Recipes, onMain = { goBack(it) }) {
            if (state.loading) BodyText("Loading recipe...", true)
            if (state.isStale) BodyText("Last read only. Refresh before making changes.", true)
            state.error?.let { BodyText(it) }
            if (recipe == null) {
                if (!state.loading && state.error == null) BodyText("This recipe doesn't exist. It may have been replaced in a plan revision.")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp())) {
                    Column(Modifier.weight(1f)) {
                        LightText(recipe.title, LightTextVariant.Heading)
                        BodyText(buildString {
                            append("Serves ${recipe.servings}")
                            recipe.prepMinutes?.let { append(" • prep ${it}m") }
                            recipe.cookMinutes?.let { append(" • cook ${it}m") }
                        }, true)
                        if (recipe.mealTypes.isNotEmpty()) BodyText(
                            recipe.mealTypes.joinToString(" • ") { MEAL_LABELS[it] ?: it }, true)
                    }
                    FavoriteControl(recipe.title, recipe.isFavorite, enabled, state.pending) { store.toggleFavorite() }
                }
                if (recipe.description.isNotEmpty()) BodyText(recipe.description, true)
                ActionButton("Start cooking", enabled, modifier = Modifier.fillMaxWidth()) {
                    navigateTo({ CookScreen(it, recipe) })
                }
                SectionHeading("Ingredients")
                groups.forEach { (section, ingredients) ->
                    if (groups.size > 1) SectionHeading(STORE_SECTIONS[section] ?: section)
                    ingredients.forEach { ingredient ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 0.25f.gridUnitsAsDp()),
                            horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp())) {
                            BodyText("- ${ingredient.name}", modifier = Modifier.weight(1f))
                            val amount = ingredient.quantity?.let {
                                BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() +
                                    (ingredient.unit?.takeIf { unit -> unit.isNotEmpty() }?.let { unit -> " $unit" } ?: "")
                            } ?: "to taste"
                            BodyText(amount, true, Modifier.widthIn(max = 8f.gridUnitsAsDp()))
                        }
                        Separator()
                    }
                }
                SectionHeading("Steps")
                NativeInstructions(recipe.stepsMarkdown)
                if (recipe.usedInPlans.isNotEmpty()) {
                    SectionHeading("Used in ${recipe.usedInPlans.size} ${if (recipe.usedInPlans.size == 1) "plan" else "plans"}")
                    BodyText("Last in ${recipe.usedInPlans.first().displayName}", true)
                    recipe.usedInPlans.forEach { use ->
                        ActionButton(use.displayName) {
                            navigateTo({ PlanDetailScreen(it, url, use.planId) }) { goBack(it) }
                        }
                    }
                }
            }
            ActionButton("Refresh", !state.pending && !state.loading) { store.refresh() }
        }
    }
}

private val INLINE_MARKDOWN = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\*([^*]+)\*|_([^_]+)_""")
private val MARKDOWN_HEADING = Regex("""^#{1,6}\s+""")
private val MARKDOWN_BULLET = Regex("""^\s*[-*+]\s+""")

@Composable
private fun NativeInstructions(markdown: String) {
    val lines = remember(markdown) { markdown.lines() }
    lines.forEach { original ->
        if (original.isBlank()) {
            Separator()
        } else if (MARKDOWN_HEADING.containsMatchIn(original)) {
            SectionHeading(original.replaceFirst(MARKDOWN_HEADING, ""))
        } else {
            val line = original.replaceFirst(MARKDOWN_BULLET, "• ")
            val text = remember(line) {
                buildAnnotatedString {
                    var cursor = 0
                    INLINE_MARKDOWN.findAll(line).forEach { match ->
                        append(line.substring(cursor, match.range.first))
                        val group = (1..5).first { match.groups[it] != null }
                        val style = when (group) {
                            1, 2 -> SpanStyle(fontWeight = FontWeight.Bold)
                            3 -> SpanStyle(fontFamily = FontFamily.Monospace)
                            else -> SpanStyle(fontStyle = FontStyle.Italic)
                        }
                        pushStyle(style)
                        append(match.groupValues[group])
                        pop()
                        cursor = match.range.last + 1
                    }
                    append(line.substring(cursor))
                }
            }
            // Native text only: source HTML and URLs never execute or load resources.
            Text(text, color = LightThemeTokens.colors.content,
                style = LightThemeTokens.typography.paragraphWide.copy(
                    fontSize = 25f.designVerticalPxToSp(), lineHeight = 32.5f.designVerticalPxToSp(),
                    letterSpacing = 0.5f.designVerticalPxToSp()))
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
        Frame("Cook mode", modifier = if (progress.keepAwake) Modifier.keepScreenOn() else Modifier,
            backLabel = "Exit", backAction = { session.exit(); goBack(Unit) },
            scrollKey = progress.index,
            bottom = {
                LightBottomBar(items = listOf(
                    LightBarButton.Text("Previous", enabled = progress.index > 0 && progress.visible && !progress.finished,
                        onClick = { session.previous() }),
                    LightBarButton.Text(if (progress.index == progress.steps.lastIndex) "Done, serve it" else "Next step",
                        enabled = progress.visible && !progress.finished, onClick = {
                            session.next()
                            if (session.state.value.finished) goBack(Unit)
                        }),
                ))
            }) {
            BodyText(recipe.title, true)
            Column(Modifier.fillMaxWidth().heightIn(min = 13f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center) {
                LightText("${progress.index + 1} / ${progress.steps.size}", LightTextVariant.Heading)
                LightText(progress.current, LightTextVariant.Copy)
            }
        }
    }
}
