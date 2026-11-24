package com.mealforge.lightphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class PlanRead(val plan: ActivePlan? = null, val loading: Boolean = true, val error: String? = null)

internal class ActivePlanScreen(activity: SealedLightActivity, serverUrl: String) : NativeScreen<Unit>(activity) {
    private val client = MealforgeClient(serverUrl)
    private val read = MutableStateFlow(PlanRead())
    private var generation = 0

    override fun willShow() {
        val request = ++generation
        read.value = read.value.copy(loading = true, error = null)
        scope.launch {
            try {
                val plan = client.activePlan()
                if (request == generation) read.value = PlanRead(plan = plan, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation) read.value = read.value.copy(loading = false, error = error.message ?: "Could not read plan.")
            }
        }
    }

    override fun willHide() {
        generation++
        super.willHide()
    }

    override fun onScreenDestroy() {
        client.close()
        super.onScreenDestroy()
    }

    @Composable
    override fun Content() {
        val state by read.collectAsState()
        val groups = remember(state.plan) { state.plan?.meals?.groupBy { it.mealType }.orEmpty() }
        Page("Active plan") {
            if (state.loading) Detail("Loading…")
            state.error?.let {
                Detail(it)
                if (state.plan != null) Detail("STALE — showing the previous read.")
            }
            val plan = state.plan
            if (plan != null) {
                LightText(plan.displayName, LightTextVariant.Title)
                Detail("${plan.status} · ${if (plan.isFavorite) "Favorite" else "Not a favorite"}")
                groups.forEach { (kind, meals) ->
                    LightText(kind.replaceFirstChar { it.uppercase() }, LightTextVariant.Subheading)
                    meals.forEach { meal ->
                        LightText(meal.title, LightTextVariant.Copy)
                        Detail(if (meal.cookedAt == null) "Not cooked" else "Done")
                    }
                }
            } else if (!state.loading && state.error == null) {
                Detail("No active meal plan.")
            }
            Choice("Refresh", !state.loading) { willShow() }
        }
    }
}
