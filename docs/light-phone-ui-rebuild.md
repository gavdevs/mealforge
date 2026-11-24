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
- Root panel content gets its one scroll container from Frame, not nested vertical scrolling. UI uses SDK grid spacing and text variants.
- Shared composables: `ActionButton(label: String, enabled: Boolean = true, selected: Boolean = false, modifier: Modifier = Modifier, action: () -> Unit)`, `BodyText(text: String, secondary: Boolean = false, modifier: Modifier = Modifier)`, `SectionHeading(text: String)`, `Separator()`.
- `formatRecipeTime(prep: Int?, cook: Int?): String?` matches web totalTime: zero absent, <90 minutes, >=90 hours with one decimal unless integral.
- `RecipeCardActions(client, scope, onConfirmed: (RecipeSummary) -> Unit)`: state with pendingIds/error, resume(), stop(), toggle(id): Job?; per-id pending gate, visible ambiguous errors, no retry, lifecycle cancellation.

Recipe slice owns RecipeListScreen.kt (replaced with RecipesPanel), RecipeDetailScreen.kt, and recipeCardView.kt. `RecipesPanel(client: MealforgeClient, scope: CoroutineScope, openRecipe: (Int) -> Unit, editText: EditNativeText)`. Detail `RecipeDetailScreen(activity, serverUrl, recipeId): NativeScreen<MainSection>`; plan links navigate to PlanDetailScreen and propagate main-section return results. Shared `RecipeCardView(title: String, prepMinutes: Int?, cookMinutes: Int?, tags: List<String>, isFavorite: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit, pending: Boolean = false, enabled: Boolean = true, cooked: Boolean? = null, onCooked: (() -> Unit)? = null)`.

Grocery slice owns GroceryScreen.kt (replaced with GroceryPanel). `GroceryPanel(serverUrl: String, scope: CoroutineScope, dataStore: DataStore<Preferences>, editText: EditNativeText)`. Use existing AppModule/GroceryStore and bounded server-keyed preference cache; add close() to release its owned client. Open sections, inline native name and section controls, add button, checked items last. Do not add quantity input absent from web form; existing returned quantities remain visible. Generated or checked items have no remove control.

Parent owns Home/nativeScreen, plan screens, navigation/formatting/card-action production, docs and integration. Test author owns new behavior tests only; runtime author owns guarded emulator scripts and fixture-only UI evidence. Neither UI implementation slice changes API/web/SDK, tests, fixtures or shared declarations without asking the integration owner.

## Verification log

Pending: baseline emulator assertion must actually fail on the generic-menu UI before native view production edits. Pure behavior tests authored separately before implementation. Existing 56 Kotlin and 10 protocol tests are retained; no UI pass inferred from those checks.
