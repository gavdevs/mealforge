package com.mealforge.lightphone

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * User-visible behavior of the main-section shell and the recipe card time line.
 *
 * The web client opens the active plan on launch and keeps the
 * Plans/Recipes/Grocery links persistent. Retaining the chosen plan list
 * (Active, Upcoming, Completed, Favorites) across main-section changes is an
 * explicit requirement of this native port, not observed web behavior: on the
 * web, each section link is a fresh navigation. `RecipeCard.totalTime` is the
 * reference for the card's single time line.
 */
class ViewBehaviorTest {

    @Test
    fun navigationStartsOnTheActivePlanList() {
        val initial = WebViewNavigation().state.value

        assertEquals("launch opens the plan content", MainSection.Plans, initial.section)
        assertEquals("the plan content starts on the active plan", PlanListKind.Active, initial.plans)
    }

    @Test
    fun changingMainSectionKeepsThePlanListTheUserChose() {
        val navigation = WebViewNavigation()

        navigation.selectPlans(PlanListKind.Favorites)
        assertEquals(
            "choosing a plan list shows that list",
            PlanListKind.Favorites,
            navigation.state.value.plans,
        )

        navigation.selectSection(MainSection.Recipes)
        assertEquals(MainSection.Recipes, navigation.state.value.section)
        assertEquals(
            "opening Recipes must not reset the plan list",
            PlanListKind.Favorites,
            navigation.state.value.plans,
        )

        navigation.selectSection(MainSection.Grocery)
        assertEquals(MainSection.Grocery, navigation.state.value.section)
        assertEquals(
            "opening Grocery must not reset the plan list",
            PlanListKind.Favorites,
            navigation.state.value.plans,
        )

        navigation.selectSection(MainSection.Plans)
        assertEquals(MainSection.Plans, navigation.state.value.section)
        assertEquals(
            "coming back to Plans shows the list the user left",
            PlanListKind.Favorites,
            navigation.state.value.plans,
        )

        // Selecting the same section again is not a reset either.
        navigation.selectSection(MainSection.Plans)
        assertEquals(PlanListKind.Favorites, navigation.state.value.plans)

        // A different plan list survives the same round trip.
        navigation.selectPlans(PlanListKind.Completed)
        assertEquals(PlanListKind.Completed, navigation.state.value.plans)

        navigation.selectSection(MainSection.Recipes)
        navigation.selectSection(MainSection.Plans)
        assertEquals(PlanListKind.Completed, navigation.state.value.plans)

        navigation.selectPlans(PlanListKind.Upcoming)
        navigation.selectSection(MainSection.Grocery)
        navigation.selectSection(MainSection.Plans)
        assertEquals(PlanListKind.Upcoming, navigation.state.value.plans)
    }

    @Test
    fun recipeTimeIsAbsentAtZeroAndCountsMinutesUnderNinety() {
        assertNull("no prep or cook time has no time line", formatRecipeTime(null, null))
        assertNull("zero total time has no time line", formatRecipeTime(0, 0))
        assertNull("a missing prep with zero cook is still absent", formatRecipeTime(null, 0))
        assertNull("a zero prep with missing cook is still absent", formatRecipeTime(0, null))

        assertEquals("10 min", formatRecipeTime(10, null))
        assertEquals("15 min", formatRecipeTime(null, 15))
        assertEquals("35 min", formatRecipeTime(10, 25))
        assertEquals("45 min", formatRecipeTime(0, 45))
        assertEquals("89 min", formatRecipeTime(89, null))
        assertEquals("89 min", formatRecipeTime(30, 59))
    }

    @Test
    fun recipeTimeSwitchesToHoursAtNinetyMinutes() {
        assertEquals("90 total minutes is the hour threshold", "1.5 hr", formatRecipeTime(90, null))
        assertEquals("1.5 hr", formatRecipeTime(30, 60))
        assertEquals("1.5 hr", formatRecipeTime(60, 30))

        assertEquals("whole hours drop the decimal", "2 hr", formatRecipeTime(120, null))
        assertEquals("2 hr", formatRecipeTime(60, 60))
        assertEquals("3 hr", formatRecipeTime(180, null))

        assertEquals("hours keep one decimal otherwise", "1.6 hr", formatRecipeTime(95, null))
        assertEquals("1.7 hr", formatRecipeTime(100, null))
        assertEquals("2.1 hr", formatRecipeTime(125, null))
        assertEquals("16.7 hr", formatRecipeTime(1000, null))
    }

    @Test
    fun recipeTimeKeepsTheWebDecimalPointUnderAnotherDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)

            assertEquals(
                "the card matches the web's '.' decimal separator, not the device locale",
                "1.5 hr",
                formatRecipeTime(90, null),
            )
            assertEquals("1.7 hr", formatRecipeTime(100, null))
            assertEquals("35 min", formatRecipeTime(10, 25))
        } finally {
            Locale.setDefault(original)
        }
    }
}
