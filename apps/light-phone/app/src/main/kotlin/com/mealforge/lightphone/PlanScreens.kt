package com.mealforge.lightphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal val MEAL_LABELS = linkedMapOf("breakfast" to "Breakfast", "lunch" to "Lunch", "dinner" to "Dinner", "snack" to "Snacks")
internal enum class PlanListKind(val title: String, val status: String?) {
    Active("Active plan", "active"), Upcoming("Upcoming plans", "upcoming"),
    Completed("Completed plans", "completed"), Favorites("Favorite plans", null),
}
private data class PlanListRead(val plans: List<ActivePlan> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val hasActive: Boolean = false)

internal class PlanListScreen(activity: SealedLightActivity, private val url: String, private val kind: PlanListKind) : NativeScreen<Unit>(activity) {
    private val client = MealforgeClient(url)
    private val read = MutableStateFlow(PlanListRead())
    private var generation = 0
    private var request: Job? = null

    override fun willShow() {
        val token = ++generation
        request?.cancel()
        read.value = read.value.copy(loading = true, error = null)
        request = scope.launch {
            try {
                val active = if (kind == PlanListKind.Active) client.activePlan() else null
                val plans = when {
                    active != null -> listOf(active)
                    kind == PlanListKind.Active -> client.plans(status = "upcoming", limit = 20)
                    else -> client.plans(status = kind.status, favoritesOnly = kind == PlanListKind.Favorites)
                }
                if (token == generation) read.value = PlanListRead(plans, loading = false, hasActive = active != null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (token == generation) read.value = read.value.copy(loading = false, error = error.message ?: "Could not read plans.")
            }
        }
    }

    override fun willHide() { generation++; super.willHide() }
    override fun onScreenDestroy() { client.close(); super.onScreenDestroy() }

    @Composable
    override fun Content() {
        val state by read.collectAsState()
        Page(kind.title) {
            if (state.loading) Detail("Loading plans...")
            state.error?.let { Detail(it); if (state.plans.isNotEmpty()) Detail("STALE: showing the last read.") }
            if (!state.loading && state.error == null) {
                if (kind == PlanListKind.Active && !state.hasActive) Detail("No active meal plan. Open an upcoming plan to make it active.")
                if (state.plans.isEmpty()) Detail(when (kind) {
                    PlanListKind.Active, PlanListKind.Upcoming -> "Nothing queued up."
                    PlanListKind.Completed -> "Nothing completed yet."
                    PlanListKind.Favorites -> "No favorite plans yet."
                })
            }
            state.plans.forEach { plan ->
                Choice(plan.displayName) { navigateTo({ PlanDetailScreen(it, url, plan.planId) }) }
                Detail("${plan.meals.size} meals • ${plan.status}${if (plan.isFavorite) " • Favorite" else ""}")
                val date = if (plan.status == "completed") plan.completedAt else plan.createdAt
                date?.let { Detail("${if (plan.status == "completed") "Completed" else "Added"} ${it.substringBefore('T')}") }
                plan.meals.take(4).forEach { LightText("• ${it.title}", LightTextVariant.Copy) }
                if (plan.meals.size > 4) Detail("${plan.meals.size - 4} more meals")
                Separator()
            }
            Choice("Refresh", !state.loading) { willShow() }
        }
    }
}

internal class PlanDetailScreen(activity: SealedLightActivity, private val url: String, private val planId: Int) : NativeScreen<Unit>(activity) {
    private val client = MealforgeClient(url)
    private val store = PlanStore(client, scope, planId)
    private val planState = MutableStateFlow(PlanState(loading = true))
    private val metadata = MutableStateFlow<Map<Int, RecipeSummary>>(emptyMap())
    private val metadataError = MutableStateFlow<String?>(null)
    private var observer: Job? = null

    override fun willShow() {
        if (observer?.isActive == true) return
        val load = store.refresh()
        observer = scope.launch { store.state.collect { planState.value = it } }
        metadata.value = emptyMap()
        metadataError.value = null
        scope.launch {
            try {
                load.join()
                if (store.state.value.plan?.meals?.isNotEmpty() == true) metadata.value = client.recipes(limit = 200).associateBy { it.id }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { metadataError.value = "Recipe metadata unavailable; showing meal titles." }
        }
    }

    override fun willHide() { store.stop(); super.willHide() }
    override fun onScreenDestroy() { client.close(); super.onScreenDestroy() }

    @Composable
    override fun Content() {
        val state by planState.collectAsState()
        val recipes by metadata.collectAsState()
        val metaError by metadataError.collectAsState()
        val groups = remember(state.plan) {
            val all = state.plan?.meals?.groupBy { it.mealType }.orEmpty()
            (MEAL_LABELS.keys + all.keys).distinct().mapNotNull { key -> all[key]?.let { key to it } }
        }
        val enabled = !state.loading && !state.pending && !state.isStale
        Page("Meal plan") {
            if (state.loading) Detail("Loading plan...")
            if (state.isStale) Detail("STALE: refresh required before making changes.")
            if (state.pending) Detail("Saving...")
            state.error?.let { Detail(it) }
            metaError?.let { Detail(it) }
            val plan = state.plan
            if (plan == null) {
                if (!state.loading && state.error == null) Detail("This meal plan doesn't exist.")
            } else {
                LightText(plan.displayName, LightTextVariant.Subtitle)
                val cooked = plan.meals.count { it.cookedAt != null }
                Detail("${plan.meals.size} meals${if (cooked > 0) " • $cooked cooked" else ""} • ${plan.status}")
                plan.createdAt?.let { Detail("Added ${it.substringBefore('T')}") }
                plan.completedAt?.let { Detail("Completed ${it.substringBefore('T')}") }
                Choice("Rename plan", enabled) { openName(plan, false) }
                Choice(if (plan.isFavorite) "Remove plan from favorites" else "Favorite plan", enabled) {
                    if (plan.name == null && !plan.isFavorite) openName(plan, true) else store.toggleFavorite()
                }
                Separator()
                groups.forEach { (kind, meals) ->
                    LightText(MEAL_LABELS[kind] ?: kind, LightTextVariant.Subheading)
                    meals.forEach { meal ->
                        Choice("• ${meal.title}", !state.pending) { navigateTo({ RecipeDetailScreen(it, url, meal.recipeId) }) }
                        recipes[meal.recipeId]?.let { recipe -> RecipeMetadata(recipe) }
                        if (plan.status != "upcoming") {
                            Choice(if (meal.cookedAt == null) "Complete meal" else "Completed: mark not cooked", enabled) {
                                store.setMealCooked(meal.mealId, meal.cookedAt == null)
                            }
                        }
                        Separator()
                    }
                }
                Choice(when (plan.status) {
                    "active" -> "Complete this plan"
                    "completed" -> "Cook it again: make active"
                    else -> "Make this the active plan"
                }, enabled) { if (plan.status == "active") store.complete() else store.activate() }
            }
            Choice("Refresh", !state.loading && !state.pending) { willHide(); willShow() }
        }
    }

    private fun openName(plan: ActivePlan, favoriteHint: Boolean) {
        navigateTo({ PlanNameScreen(it, url, planId, plan.name.orEmpty(), favoriteHint) })
    }
}

private class PlanNameScreen(activity: SealedLightActivity, url: String, planId: Int,
    initial: String, private val favoriteHint: Boolean) : NativeScreen<Unit>(activity) {
    private val client = MealforgeClient(url)
    private val store = PlanStore(client, scope, planId)
    private val current = MutableStateFlow(PlanState(loading = true))
    private val draft = MutableStateFlow(initial)
    private val notice = MutableStateFlow<String?>(null)
    private var observer: Job? = null
    private var interrupted = false

    override fun willShow() {
        if (observer?.isActive == true) return
        store.refresh()
        observer = scope.launch { store.state.collect { current.value = it } }
    }
    override fun willHide() {
        if (store.state.value.pending) {
            interrupted = true
            notice.value = "Save interrupted. Check the plan's current name before trying again."
        }
        store.stop()
        super.willHide()
    }
    override fun onScreenDestroy() { client.close(); super.onScreenDestroy() }

    @Composable
    override fun Content() {
        val state by current.collectAsState()
        val name by draft.collectAsState()
        val message by notice.collectAsState()
        Page("Plan name", backLabel = "CANCEL") {
            if (favoriteHint) Detail("Name this plan to favorite it. Save the name, then choose Favorite plan.")
            Detail("Optional, up to 80 characters. Leave empty to clear a non-favorite plan's name.")
            if (state.loading) Detail("Loading...")
            state.error?.let { Detail(it) }
            message?.let { Detail(it) }
            Choice(name.ifEmpty { "Enter a name" }, !state.pending) {
                navigateTo({ TextEditorScreen(it, "Plan name", name) }) { value -> draft.value = value }
            }
            Choice(if (state.pending) "Saving..." else "Save name", !state.loading && !state.pending && !state.isStale && state.plan != null && !interrupted) {
                val trimmed = name.trim()
                if (trimmed.length > 80) { notice.value = "Use no more than 80 characters."; return@Choice }
                val job = store.rename(trimmed.takeIf { it.isNotEmpty() }) ?: return@Choice
                scope.launch { job.join(); if (!job.isCancelled && store.state.value.error == null) goBack(Unit) }
            }
        }
    }
}
