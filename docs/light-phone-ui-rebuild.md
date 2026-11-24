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

## Actual emulator baseline and first rewrite

Doctor and direct package inspection confirm only the allocated `emulator-5584`, AVD `mealforge_lpi3_ui_test_v1`, API34/test-keys/qemu, 1080x1240/density420. SDK emulator package is now `/system/priv-app/LightOSEmulator/LightOSEmulator.apk`, UID1000 and `pkgFlags` includes SYSTEM; not merely a data APK with system UID.

The setup worker exited with an external provider HTTP402 quota error. Two view workers ended without producing production edits; implementation continued inline, without changing models/providers or creating another controller. Its transcript also shows prohibited reads of the owner's separate SDK checkout despite explicit instructions; no edits there are shown. All candidate builds continue to use the independently checked clean pinned `.sdk`, never that checkout. This boundary violation is recorded rather than hidden.

First UI driver attempt failed its editor precondition, not product acceptance: the pinned SDK editor is BasicText plus its own touch keyboard, not Android EditText/IME. No SDK workaround was applied. The driver now enters text through the actual keyboard, including numeric/punctuation layout and native backspace.

True initial UI RED, before view production edits:

```sh
python3 apps/light-phone/scripts/emulatorUi.py --baseline --evidence apps/light-phone/build/emulator/evidence/ui-red-keyboard
```

It configured the disposable loopback origin through native UI and failed `Expected native view content: Meal plans` after 132.68s. Inspected `02-accepted-launch.png`: accepted app still displayed Groceries/Active plan/Upcoming plans/Completed plans/Favorite plans/Recipes as a category menu plus repeated server/legal text. That is the rejected behavior, not a fabricated failing state. Identity and trace retained beside the screenshot. Installed baseline APK SHA256 `b826d59aeff6e485e474769d21a3b0a20c1bf4964c0522c9405f6ce1443c8e2f`; retained as ignored `build/emulator/evidence/baseline-menu.apk`.

Replaced that view layer with retained main-section panels, SDK top/bottom bars, four inline plan controls, active PlanView content, bordered recipe cards with independent favorite/cooked actions, inline rename/save/cancel, recipe search/lists/categories, detail facts/cooking before ingredients, plan-usage links, fixed cook navigation and sectioned checkbox groceries with inline name/section/add before checked items. Existing protocol/store production remains unchanged.

First compiled rewrite command:

```sh
apps/light-phone/gradlew :app:testDebugUnitTest -DlightSdk.unsigned=true :app:assembleDebug --console=plain
```

SDK exact pin and empty status checked immediately beforehand. BUILD SUCCESSFUL in19s,90 tasks (15 executed,2 cache,73 up-to-date). Actual XML:67 tests,0 failures/errors/skips (all original56 plus11 new). This is compilation/JVM/unsigned-APK proof, not yet a corrected-UI runtime pass.

Instruction rendering now natively handles headings, lists, bold/emphasis and inline code while keeping HTML inert. Full GFM tables/link activation are not implemented; URLs/source text remain visible, not silently executed. Presentation beyond that supported subset remains a review limitation.

### Recreated views checkpoint

Forced native verification against the rewritten sources:

```sh
apps/light-phone/gradlew -DlightSdk.unsigned=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks --no-build-cache --console=plain
```

Observed BUILD SUCCESSFUL in 49s; all143 tasks executed. XML67 tests,0 failures/errors/skips. Lint0 errors,3 warnings (2 ModifierParameter,1 MissingApplicationIcon). Log: ignored `build/ui-final-native.log`.

Actual emulator captures in `build/emulator/evidence/ui-extended-idle` now show active/upcoming/completed/favorite plans, rename/cancel, recipe favorites/history, native keyboard ingredient search, recipe detail and initial cook view. Inspected active-plan, history, detail and cook images; content is native SDK text/cards/controls, not the rejected menu. The full runtime suite is still incomplete at this checkpoint.

Two initial runtime attempts stopped at Upcoming with a visible `unexpected end of stream`; the disposable fixture logged no server exception. Increasing its keep-alive timeout from5s to120s let all plan/recipe reads progress through cooking. Idle-connection/proxy interaction is an inference, not proof of production transport reliability. Client retry policy and API implementation remain unchanged.

The next runtime failure was actual wheel delivery: API34 `input keyevent 318` left cook step1 unchanged. A bounded test-only raw KeyEvent helper proved `KeyEvent.keyCodeFromString("318") == 0`, while injecting literal318 advanced the real cook view to step2. This is an API34 shell symbolic-table limitation, not a passing wheel test using the original command. The first helper used context-dependent InputManager and failed with NPE; InputManagerGlobal delivered the observed raw event successfully. No SDK source or application state was patched.

