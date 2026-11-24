package com.mealforge.lightphone

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal val MEAL_LABELS = linkedMapOf(
    "breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "snack" to "Snacks",
)
internal enum class PlanListKind(val title: String, val status: String?) {
    Active("Active", "active"), Upcoming("Upcoming", "upcoming"),
    Completed("Completed", "completed"), Favorites("Favorites", null),
}
private data class PlanListRead(
    val plans: List<ActivePlan> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val hasActive: Boolean = false, val activating: Int? = null,
)

internal class PlansPanel(
    private val client: MealforgeClient, private val scope: CoroutineScope,
    private val openPlan: (Int) -> Unit, private val openRecipe: (Int) -> Unit,
    private val editText: EditNativeText, private val onFilter: (PlanListKind) -> Unit,
) : MealforgePanel {
    private val kind = MutableStateFlow(PlanListKind.Active)
    private val read = MutableStateFlow(PlanListRead())
    private var generation = 0
    private var request: Job? = null
    private var activation: Job? = null
    private var activationStore: PlanStore? = null
    private var activeContent: PlanContent? = null
    private var visible = false

    override fun show() { visible = true; refresh() }
    override fun hide() {
        visible = false
        generation++
        request?.cancel()
        activation?.cancel()
        activationStore?.stop()
        activeContent?.hide()
        if (read.value.activating != null) read.value = read.value.copy(
            activating = null, error = "Activation interrupted. Check the active plan before trying again.")
    }

    private fun refresh() {
        if (!visible) return
        val token = ++generation
        request?.cancel()
        activeContent?.hide()
        val selected = kind.value
        read.value = read.value.copy(loading = true, activating = null)
        request = scope.launch {
            try {
                val active = if (selected == PlanListKind.Active) client.activePlan() else null
                val plans = when {
                    active != null -> listOf(active)
                    selected == PlanListKind.Active -> client.plans(status = "upcoming", limit = 20)
                    else -> client.plans(status = selected.status, favoritesOnly = selected == PlanListKind.Favorites)
                }
                if (token != generation || !visible) return@launch
                if (active != null) {
                    if (activeContent?.planId != active.planId) {
                        activeContent = PlanContent(client, scope, active.planId, openRecipe, editText, ::refresh)
                    }
                    activeContent?.show()
                }
                read.value = PlanListRead(plans, loading = false, hasActive = active != null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation && visible) read.value = read.value.copy(
                    loading = false, error = error.message ?: "Could not read plans.")
            }
        }
    }

    private fun activate(plan: ActivePlan) {
        if (read.value.loading || read.value.activating != null || read.value.error != null) return
        val token = generation
        val store = PlanStore(client, scope, plan.planId)
        activationStore = store
        read.value = read.value.copy(activating = plan.planId, error = null)
        activation = scope.launch {
            store.refresh().join()
            if (!visible || token != generation) return@launch
            store.activate()?.join()
            if (!visible || token != generation) return@launch
            val error = store.state.value.error
            store.stop()
            if (error == null) refresh()
            else read.value = read.value.copy(activating = null, error = error)
        }
    }

    @Composable
    override fun Content() {
        val selected by kind.collectAsState()
        val state by read.collectAsState()
        val activeState = activeContent?.state?.collectAsState()?.value
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.2f.gridUnitsAsDp())) {
            PlanListKind.entries.forEach { option ->
                ActionButton(option.title, selected = selected == option, modifier = Modifier.weight(1f)) {
                    if (kind.value != option) {
                        hide()
                        kind.value = option
                        onFilter(option)
                        read.value = PlanListRead()
                        show()
                    }
                }
            }
        }
        if (state.loading) BodyText("Loading your plan...", secondary = true)
        state.error?.let { BodyText(it); BodyText("Last read only. Refresh before changing plans.", true) }
        if (!state.loading && state.error == null && selected == PlanListKind.Active && state.hasActive) {
            activeContent?.Content()
        } else if (!state.loading && (state.error == null || state.plans.isNotEmpty())) {
            if (selected == PlanListKind.Active && !state.hasActive) {
                SectionHeading("No active meal plan")
                BodyText(if (state.plans.isEmpty()) "Plan meals with your assistant in chat. Push a plan and it lands here, grocery list included."
                    else "Pick an upcoming plan below to start cooking, or plan something new with your assistant in chat.", true)
            } else if (state.plans.isEmpty()) {
                SectionHeading(when (selected) {
                    PlanListKind.Completed -> "Nothing completed yet"
                    PlanListKind.Favorites -> "No favorite plans yet"
                    else -> "Nothing queued up"
                })
                BodyText(when (selected) {
                    PlanListKind.Completed -> "Complete a plan when you finish cooking through it. Your cooking history collects here."
                    PlanListKind.Favorites -> "Name a plan and favorite it to keep it here."
                    else -> "Plans pushed from chat wait here until you set one active."
                }, true)
            }
            state.plans.forEach { plan ->
                PlanCardView(plan, onOpen = { openPlan(plan.planId) })
                if (selected == PlanListKind.Active && !state.hasActive) ActionButton(
                    if (state.activating == plan.planId) "Activating..." else "Set active",
                    state.activating == null && state.error == null, modifier = Modifier.fillMaxWidth(),
                ) { activate(plan) }
            }
        }
        ActionButton("Refresh", !state.loading && state.activating == null && activeState?.pending != true) { refresh() }
    }
}

@Composable
private fun PlanCardView(plan: ActivePlan, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, LightThemeTokens.colors.contentSecondary)
        .lightClickable(onClick = onOpen).padding(0.75f.gridUnitsAsDp())) {
        LightText(plan.displayName, LightTextVariant.ParagraphWide)
        if (plan.isFavorite) BodyText("Favorite", true)
        val date = if (plan.status == "completed") plan.completedAt else plan.createdAt
        BodyText("${plan.meals.size} ${if (plan.meals.size == 1) "meal" else "meals"} • " +
            when (plan.status) {
                "active" -> "active now"
                "completed" -> "completed ${formatPlanDate(date)}"
                else -> "added ${formatPlanDate(date)}"
            }, true)
        plan.meals.take(4).forEach { BodyText("• ${it.title}", true) }
        if (plan.meals.size > 4) BodyText("+ ${plan.meals.size - 4} more meals", true)
    }
}

private val PLAN_DATE = DateTimeFormatter.ofPattern("MMM d")
internal fun formatPlanDate(value: String?): String =
    if (value == null) "" else PLAN_DATE.format(Instant.parse(value).atZone(ZoneId.systemDefault()))

internal class PlanDetailScreen(
    activity: SealedLightActivity, private val url: String, planId: Int,
) : NativeScreen<MainSection>(activity) {
    private val client = MealforgeClient(url)
    private val content = PlanContent(client, scope, planId,
        openRecipe = { id -> navigateTo({ RecipeDetailScreen(it, url, id) }) { goBack(it) } },
        editText = { title, initial, onSubmit -> navigateTo({ TextEditorScreen(it, title, initial) }, onSubmit) })

    override fun willShow() { content.show() }
    override fun willHide() { content.hide(); super.willHide() }
    override fun onScreenDestroy() { client.close(); super.onScreenDestroy() }
    @Composable override fun Content() {
        Frame("Meal plan", main = MainSection.Plans, onMain = { goBack(it) }) { content.Content() }
    }
}

private data class PlanEdit(val editing: Boolean = false, val draft: String = "", val hint: String? = null)

internal class PlanContent(
    private val client: MealforgeClient, private val scope: CoroutineScope, val planId: Int,
    private val openRecipe: (Int) -> Unit, private val editText: EditNativeText,
    private val onStatusChanged: (() -> Unit)? = null,
) : MealforgePanel {
    private val store = PlanStore(client, scope, planId)
    val state get() = store.state
    private val edit = MutableStateFlow(PlanEdit())
    private val metadata = MutableStateFlow<Map<Int, RecipeSummary>>(emptyMap())
    private val metadataError = MutableStateFlow<String?>(null)
    private val favorites = RecipeCardActions(client, scope) { recipe ->
        metadata.value = metadata.value + (recipe.id to recipe)
    }
    private var metadataRequest: Job? = null
    private var generation = 0

    override fun show() {
        val token = ++generation
        val load = store.refresh()
        metadataRequest?.cancel()
        metadataRequest = scope.launch {
            load.join()
            try {
                val rows = if (store.state.value.plan?.meals?.isNotEmpty() == true)
                    client.recipes(limit = 200).associateBy { it.id } else emptyMap()
                if (token == generation) {
                    metadata.value = rows
                    metadataError.value = null
                    favorites.resume()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (token == generation) metadataError.value = "Recipe metadata unavailable. Refresh to use card favorites."
            }
        }
    }
    override fun hide() {
        generation++
        metadataRequest?.cancel()
        favorites.stop()
        store.stop()
    }

    private fun startEditing(plan: ActivePlan, hint: String? = null) {
        edit.value = PlanEdit(editing = true, draft = plan.name.orEmpty(), hint = hint)
    }

    private fun saveName() {
        val name = edit.value.draft.trim()
        if (name.length > 80) {
            edit.value = edit.value.copy(hint = "Use no more than 80 characters.")
            return
        }
        val job = store.rename(name.takeIf { it.isNotEmpty() }) ?: return
        scope.launch {
            job.join()
            if (!job.isCancelled && store.state.value.error == null) edit.value = PlanEdit()
        }
    }

    @Composable
    override fun Content() {
        val state by store.state.collectAsState()
        val recipes by metadata.collectAsState()
        val error by metadataError.collectAsState()
        val cards by favorites.state.collectAsState()
        val editor by edit.collectAsState()
        val plan = state.plan
        val enabled = !state.loading && !state.pending && !state.isStale
        if (state.loading) BodyText("Loading plan...", true)
        if (state.isStale) BodyText("Last read only. Refresh before making changes.", true)
        state.error?.let { BodyText(it) }
        cards.error?.let { BodyText(it) }
        error?.let { BodyText(it, true) }
        if (plan == null) {
            if (!state.loading && state.error == null) BodyText("This meal plan doesn't exist.")
            ActionButton("Refresh plan", !state.loading) { show() }
            return
        }
        if (editor.editing) {
            LightTextField("Plan name", editor.draft, "Meal Plan ${plan.planId}",
                onClick = { if (enabled) editText("Plan name", editor.draft) { edit.value = edit.value.copy(draft = it) } })
            Row(horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp())) {
                ActionButton(if (state.pending) "Saving..." else "Save", enabled) { saveName() }
                ActionButton("Cancel", !state.pending) { edit.value = PlanEdit() }
            }
            editor.hint?.let { BodyText(it) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp())) {
                Column(Modifier.weight(1f)) {
                    LightText(plan.displayName, LightTextVariant.Heading)
                    val count = plan.meals.count { it.cookedAt != null }
                    BodyText("${plan.meals.size} ${if (plan.meals.size == 1) "meal" else "meals"}" +
                        (if (count > 0) " • $count cooked" else "") + " • " +
                        (if (plan.status == "completed" && plan.completedAt != null)
                            "completed ${formatPlanDate(plan.completedAt)}" else plan.status), true)
                }
                FavoriteControl("plan", plan.isFavorite, enabled, state.pending) {
                    if (!plan.isFavorite && plan.name == null) startEditing(plan, "Name this plan to favorite it.")
                    else store.toggleFavorite()
                }
            }
            ActionButton("Rename", enabled, modifier = Modifier.semantics { contentDescription = "Rename plan" }) { startEditing(plan) }
        }
        MEAL_LABELS.forEach { (kind, label) ->
            val meals = plan.meals.filter { it.mealType == kind }
            if (meals.isNotEmpty()) SectionHeading(label)
            meals.forEach { meal ->
                val recipe = recipes[meal.recipeId]
                RecipeCardView(meal.title, recipe?.prepMinutes, recipe?.cookMinutes,
                    recipe?.tags.orEmpty(), recipe?.isFavorite ?: false,
                    onOpen = { openRecipe(meal.recipeId) }, onFavorite = { favorites.toggle(meal.recipeId) },
                    pending = meal.recipeId in cards.pendingIds,
                    enabled = enabled && recipe != null && error == null,
                    cooked = meal.cookedAt != null,
                    onCooked = if (plan.status == "upcoming") null else {
                        { store.setMealCooked(meal.mealId, meal.cookedAt == null); Unit }
                    })
            }
        }
        ActionButton(when (plan.status) {
            "active" -> "Complete this plan"
            "completed" -> "Cook it again: make active"
            else -> "Make this the active plan"
        }, enabled, modifier = Modifier.fillMaxWidth()) {
            val job = if (plan.status == "active") store.complete() else store.activate()
            if (job != null) scope.launch {
                job.join()
                if (!job.isCancelled && store.state.value.error == null) onStatusChanged?.invoke()
            }
        }
    }
}
