# Native web-view recreation

Starting source: `69b5ee41c1a32811dc27d9ac3f9cdb1776e6e2c8`, branch `feat/light-phone`.
Owner revised `light-phone-client-spec.md`; the initial tracked diff is that file only.
SDK HEAD verified `251237ef3778c907c5dbd89e84685532c9d99a28`, status empty.
Experimental/local composite build; no distribution or physical-device acceptance.

## Reference inspection

Read all ten current web route files: root layout, redirect, four plan lists, plan detail, recipe list/detail and grocery. Read PlanView, PlanCard, planTypes, RecipeCard and FavoriteButton. Root redirects to active plans; three persistent main-section links, four plan controls, inline recipe favorite/cooked actions, search before recipe controls and open groceries/form/cart ordering are the correction's core presentation requirements. Existing protocol/store safeguards stay intact. The TS feature generator does not support this independent Kotlin application; no TS feature directories or backend wiring will be generated.

## Integration contract for implementation slices

One SDK initial screen owns the accepted server and three retained panels, not a menu or a growing stack of tab screens. Main-section changes call panel hide/show. Details use the SDK screen stack and return an optional MainSection result; selecting a main section from a detail propagates that result to the initial screen. Ordinary back returns null and preserves panel filters/drafts. Cook mode remains a separate screen without main navigation.

Shared parent-owned declarations (package com.mealforge.lightphone):
- `enum class MainSection { Plans, Recipes, Grocery }`
- `data class ViewRoute(val section: MainSection = MainSection.Plans, val plans: PlanListKind = PlanListKind.Active)`
- `class WebViewNavigation`: observable `state: StateFlow<ViewRoute>`, `selectSection(MainSection)`, `selectPlans(PlanListKind)`; selecting another section retains the plan filter.
- `interface MealforgePanel`: `@Composable fun Content()`, `fun show()`, `fun hide()`.
- `typealias EditNativeText = (String, String, (String) -> Unit) -> Unit` (title, initial value, submit callback; cancellation never calls submit).
- `NativeScreen.Frame(title, modifier = Modifier, backLabel = "Back", main: MainSection? = null, onMain: ((MainSection) -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)` uses LightTheme, LightTopBar, LightScrollView, wheel scrolling and optional SDK LightBottomBar.
- Home calls `Frame(main = selected, onMain = ::switchSection) { selectedPanel.Content() }`; panels do not call Frame and need no navigation callback. This is their one scroll container, not nested vertical scrolling. Details call their inherited protected Frame with `onMain = { goBack(it) }`. UI uses SDK grid spacing and text variants.
- Shared composables: `ActionButton(label: String, enabled: Boolean = true, selected: Boolean = false, modifier: Modifier = Modifier, action: () -> Unit)`, `BodyText(text: String, secondary: Boolean = false, modifier: Modifier = Modifier)`, `SectionHeading(text: String)`, `Separator()`.
- `formatRecipeTime(prep: Int?, cook: Int?): String?` matches web totalTime: zero absent, <90 minutes, >=90 hours with one decimal unless integral.
- `RecipeCardActions(client, scope, onConfirmed: (RecipeSummary) -> Unit)`: state with pendingIds/error, resume(), stop(), toggle(id): Job?; per-id pending gate, visible ambiguous errors, no retry, lifecycle cancellation.

Recipe slice owns RecipeListScreen.kt (replaced with RecipesPanel), RecipeDetailScreen.kt, and recipeCardView.kt. `RecipesPanel(client: MealforgeClient, scope: CoroutineScope, openRecipe: (Int) -> Unit, editText: EditNativeText)`. Detail `RecipeDetailScreen(activity, serverUrl, recipeId): NativeScreen<MainSection>`; plan links navigate to PlanDetailScreen and propagate main-section return results. Shared `RecipeCardView(title: String, prepMinutes: Int?, cookMinutes: Int?, tags: List<String>, isFavorite: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit, pending: Boolean = false, enabled: Boolean = true, cooked: Boolean? = null, onCooked: (() -> Unit)? = null)`.

Grocery slice owns GroceryScreen.kt (replaced with GroceryPanel). `GroceryPanel(serverUrl: String, scope: CoroutineScope, dataStore: DataStore<Preferences>, editText: EditNativeText)`. Use existing AppModule/GroceryStore and bounded server-keyed preference cache; add close() to release its owned client. Open sections, inline native name and section controls, add button, checked items last. Do not add quantity input absent from web form; existing returned quantities remain visible. Generated or checked items have no remove control.

Parent owns Home/nativeScreen, plan screens, navigation/formatting/card-action production, docs and integration. Test author owns new behavior tests only; runtime author owns guarded emulator scripts and fixture-only UI evidence. Neither UI implementation slice changes API/web/SDK, tests, fixtures or shared declarations without asking the integration owner.

## Verification log

`d1dd520918c50962eb858bdc7255714592bfd578` preserves the owner's revised acceptance contract.

Separate test author froze 5 navigation/time tests and 6 real-loopback inline-favorite tests before their production implementation. Initial command (with the documented JDK17/AndroidSDK environment):

```sh
apps/light-phone/gradlew :app:testDebugUnitTest --tests '*ViewBehaviorTest' --tests '*RecipeCardActionsTest' --console=plain
```

RED: BUILD FAILED in 15s at compileDebugUnitTestKotlin, unresolved expected WebViewNavigation/MainSection/formatRecipeTime/RecipeCardActions. Zero tests executed; this is missing implementation, not a behavioral assertion failure. Initial UI is still the generic-menu implementation; its independent emulator RED/capture is pending. Only pure navigation/card-action production has been added so far. Existing 56 Kotlin and 10 protocol tests remain unchanged.

No Kotlin language server is available (`kotlin-lsp` not found); compiler/lint/runtime checks, not LSP cleanliness, will provide verification.

GREEN for the same targeted command: BUILD SUCCESSFUL in 6s, 62 tasks (10 executed, 52 up-to-date). Actual XML: RecipeCardActionsTest 6 and ViewBehaviorTest 5, all 11 passed, zero failures/errors/skips. These prove route defaults/retention, web time formatting, per-row favorite gating, loopback-confirmed results, missing-record failure and interruption safety; not a UI pass. Keeping the chosen plan filter across native section changes is an explicit native state-preservation adaptation, not a claim that web route links preserve React state.
