package com.mealforge.lightphone

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Shared with SDK screen results so section links can return to the root.
enum class MainSection { Plans, Recipes, Grocery }

internal data class ViewRoute(
    val section: MainSection = MainSection.Plans,
    val plans: PlanListKind = PlanListKind.Active,
)

internal class WebViewNavigation {
    private val mutable = MutableStateFlow(ViewRoute())
    val state: StateFlow<ViewRoute> = mutable.asStateFlow()

    fun selectSection(section: MainSection): Unit {
        mutable.value = state.value.copy(section = section)
    }

    fun selectPlans(plans: PlanListKind): Unit {
        mutable.value = state.value.copy(section = MainSection.Plans, plans = plans)
    }
}

internal fun formatRecipeTime(prep: Int?, cook: Int?): String? {
    val total = (prep ?: 0) + (cook ?: 0)
    if (total == 0) return null
    if (total < 90) return "$total min"
    val hours = if (total % 60 == 0) (total / 60).toString()
        else String.format(Locale.ROOT, "%.1f", total / 60.0)
    return "$hours hr"
}
