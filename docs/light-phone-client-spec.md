# MealForge Light Phone III port

Status: The full web workflow candidate was published as experimental `light-phone-0.1.0-alpha.1` at `69b5ee41c1a32811dc27d9ac3f9cdb1776e6e2c8`. The owner then rejected its presentation: the port must reproduce the web application's screens, information hierarchy and actions, not merely their API workflows. The current correction explicitly includes setting up the LightSDK emulator and an emulator-driven test agent. Do not claim UI completion from protocol tests, compilation or a design concept. This correction does not authorize a new release, merge, deployment or physical-phone installation.

## Outcome

Port the existing MealForge web application's user-facing functionality to an installable native Kotlin/Jetpack Compose Light Phone III client inside the existing owner-controlled MealForge monorepo at https://github.com/gavdevs/mealforge. Use a new application directory `apps/light-phone` alongside `apps/api` and `apps/web`. Do not develop it inside a LightSDK branch, make it a library's `tool` module, or create another application repository.

This is a client and presentation port, not a new product feature stream. The current web routes, components and API procedures are the functional reference. Preserve their workflows while adapting the visual/navigation idiom to the Light Phone. No replacement backend, database, orchestrator, model provider, new meal-planning service, new cooking-session model, new product capabilities or WebView main UI.

Reuse the existing MealForge Hono/tRPC API and consume the owner's https://github.com/gavdevs/light-sdk fork as a separately pinned dependency, not as the application repository. Use the SDK's client/UI/build facilities from an independent Android project in `apps/light-phone`; establish a reproducible integration such as a pinned Git revision plus composite build/submodule during scaffold investigation instead of guessing module coordinates. Initially assess commit `251237e` from the current local SDK main, resolving and recording its full SHA. Do not include the checkout's unrelated uncommitted text-input edits, and do not modify the existing SDK checkout. Follow its README, CONTRIBUTING.md, TOOL_GUIDELINES.md and current examples; check the SDK plugin's allowed API/dependency list before choosing transport or persistence libraries. Keep application code in `apps/light-phone`. Any independently justified SDK fix must remain a separate owner-reviewed SDK change rather than hidden app work. No backend/API modifications are needed for the identified workflows; the OMP investigator must report any discovered mismatch rather than inventing or silently changing an endpoint.

The current TypeScript API/web apps remain unchanged. Treat the Kotlin client as its own Gradle build subsystem inside `apps/*`; wire minimal Nx task wrappers only where useful and ensure Android code does not break existing pnpm/Nx or Biome targets. Do not treat this app as an `apps/api` TypeScript feature or require the TypeScript feature generator to generate it. Do not treat inclusion within `apps/*` as proof that Gradle/Nx autodiscovery works.

## Full web functionality to port

- Plans: active, upcoming, completed and favorite views as offered by the web app; plan details and meal-type groups; timestamps and cooked counts; navigate to recipes.
- Plan actions: set an upcoming plan active; complete an active plan; rename/save/cancel a plan name, including clearing the name; favorite/unfavorite a plan, preserving the existing requirement that a favorite unnamed plan first receive a name.
- Meals: mark a meal cooked or not cooked on active and completed plans where the current web UI provides that control; upcoming plans retain the existing absence of the meal completion control.
- Recipes: favorites/history lists, text search over titles/tags/ingredients, meal-type filters, detail view and favorite/unfavorite.
- Recipe detail: title, servings, preparation/cooking minutes, description, meal types, ingredients with quantities/units or "to taste", store-section groups, steps and usage-in-plans summary.
- Cook mode: the web app's Start cooking view, readable one-step-at-a-time content, existing previous/next/done or close navigation and best-effort screen-wake behavior. Start/stop means entering and leaving this existing view, not timers, remote cooking sessions or a new API.
- Groceries: the active plan's list grouped by store section, checked/unchecked state, manual item entry with the same name/optional quantity/optional section inputs as the web UI, and removal of eligible manual items exactly as allowed by current behavior. Preserve generated-item restrictions and duplicate/no-op handling rather than guessing at them.
- Loading, empty, absent and server-error states corresponding to the existing workflows. Require the returned server state before presenting a write as confirmed; guard repeated taps, stale responses and screen exit. Optimistic presentation is permitted only when existing web behavior calls for it and failures reconcile visibly.
- Configurable server URL, initially https://meals.loosewire.dev. Reuse the web client's existing access model. No additional authentication/public-endpoint/deployment change is included in this port and no credentials belong in source or logs. Installer setup must say whether the server is trusted.

## Light Phone presentation

Black background, white primary text and secondary gray text only. Recreate essentially all web views and their interaction patterns with LightSDK-native components, not a generic menu interpretation of them. The current web routes and components are the presentation references; the earlier monolithic concept PNG is not authoritative. Reproduce the web Plans/Recipes/Grocery navigation and in-page controls with the SDK's available bottom bar, top bar, tabs, text fields, inputs, scrolling and hardware-key behavior. Adapt colors, typography, sizing and input to Light Phone rather than copying CSS or using a WebView. Keep all important actions discoverable without an arbitrary extra menu hierarchy.

The SDK bottom bar uses Home, Large List, and Accept icons for Plans, Recipes, and Grocery, with accessible section labels.

A fresh installation asks for the trusted server in a focused setup/settings flow. Once accepted, launching the app opens the active plan content, not a page of legal text or category links. The server origin is reachable from a small settings action; it must not displace the meal plan on every launch. Plans, grocery and recipes remain directly discoverable from every main section without manual server re-entry.

### Screen correspondence

- **Meal plans:** reproduce `/plans` and its Active, Upcoming, Completed and Favorites views. Show the plan name, rename and favorite controls, status/counts, meal-type groups and recipe cards in the same information order as the web `PlanView`. Recipe-card time/tags/favorite controls and per-meal cooked actions remain available; no mandatory trip to a separate recipe screen merely to perform an existing card action. Complete/activate actions have the same scope and status restrictions.
  The borderless `SHOW` row opens the four plan filters inline in the same wheel-scrolled pane. The current filter is marked and retained across section changes.
- **Recipes:** put search before the Favorites/History selection and meal-category filter, as the web route does. Show distinct recipe cards whose title, time, first three tags and favorite state are understandable without separate `Not a favorite` filler. Provide the web card's direct favorite action as well as opening its detail. Carry existing empty/no-match guidance and don't make a second arbitrary filter menu the default presentation.
- **Recipe detail:** keep the title, favorite control, servings/preparation/cooking facts, meal types, description and Start cooking entry together at the top. Put sectioned ingredients and all steps below them. Preserve quantity/unit alignment and the existing used-in-plan summary. Do not silently replace the source instructions with a read-only number count.
- **Cook view:** retain the large step index/current instruction, previous/next/done actions and explicit Exit. Make current-step navigation reachable on Light hardware and verify actual SDK/Android key delivery, wheel movement, back navigation and best-effort wake behavior rather than inferring it from code. No timer or cooking-session backend.
- **Grocery:** keep open store-section groups first, then the manual-add form, then checked items. Use the current web form's name and store-section inputs rather than adding a new product feature merely because the backend may support more fields. Keep checkbox/quantity state visible and use the existing manual-item removal rule. Pending, failure and server-confirmation states must remain understandable per row.

Long text may require a focused detail/edit screen, but expanding an item must not erase the web workflow's expected action or selection. Preserve back navigation, search/filter results and draft state across ordinary screen transitions. Avoid a second nested menu of labels when the corresponding web control can operate in place. Use small screen/regression tests for behavior and actual emulator captures for presentation, focus, clipping, line wrapping and hit-target size.

### Emulator acceptance and safety

Build and run against the pinned SDK's actual `sdk/emulator` system app on a dedicated API 34 test-device configuration matching the documented 1080×1240 LPIII display. Verify that this is the supported emulator (`test-keys`, system UID/package, and intended server package) before trusting the result. Building the production `com.lightos` variant remains a separate gate; a host JVM test or stock Android screenshot is not a LightOS interaction test.

Use a disposable loopback fixture server with ADB reverse forwarding, not the real household backend, for every mutation workflow. The test agent must refuse an unexpected physical device/server and an unapproved fixture origin. It should capture screenshots and accessibility/UI traces for each main screen, core mutation and failure path; name the tested source revision and emulator-variant metadata; and exercise wheel/center/back operations plus practical text entry. Store fixture-only evidence separately from source. Do not claim physical-wheel, LP3 panel, battery or hardware timing equivalence from a desktop emulator.

The test agent must fail initially against the generic-menu UI, then cover the corrected flows: active plan first, main-section navigation, all plan lists, card favorite/cooked/plan actions, recipe search/filter/detail/favorite, cook navigation, grocery checklist/add/remove, text cancellation, long content, loading/error/stale states and useful local reopening. No production data mutation, new authentication/deployment or remote job engine is part of this correction.

## Implementation and compatibility constraints

Read all current web routes/components and the exact tRPC service/response contracts before locking the feature matrix. Keep the native model tolerant of representation details already observed in the deployed tRPC response rather than coupling to server internals. Do not assume the endpoint input/response envelope solely from general tRPC examples.

Grocery operations must bind to the currently displayed plan and item IDs. Plan activation/completion and cooked/favorite controls must follow the existing status rules and invalidation relationship between plans and groceries. A plan or recipe action must never silently affect a different selected record.

Do not mutate the owner's real meal data to probe behavior. Protocol reads may be tested separately; mutation tests require a disposable/local server with explicit test fixtures or separately designated test data. Use the existing TypeScript monorepo unchanged as evidence/API reference unless the owner approves a separately scoped backend change.

Follow tests-before-production implementation. Tests derive from current user-visible web behavior and this approved scope, not from an invented redesign. Stage work internally as API/navigation/parcels of the full port if useful; internal stages do not redefine version 1 as read-only or defer required web workflows.

## Acceptance

- All web workflows listed above have working native counterparts or a specifically owner-approved and documented presentation adaptation. No silent omission of utilities added to the current web client.
- Contract tests cover the real request/response format, server validation/errors, empty/absent states, ingredient units, recipe sections, grocery status and manual items, status restrictions, rename/favorite rules and successful mutation responses.
- Unit/UI tests cover wheel and back navigation, text entry, cook-step navigation/wake behavior, long text, checked/cooked/favorite state, duplicate-tap prevention, failures and cache labeling/no background polling after stop.
- Read-only and mutation integration checks run against designated test data/instance. A mock-only result is not protocol proof.
- Independent read-only review finds no correctness/security blocker. Establish the pinned SDK dependency baseline separately, then run the applicable Android application tests and static checks and build the APK using the exact task verified from the new `apps/light-phone` Gradle scaffold with a compatible local JDK/Android SDK toolchain. Verify any Nx wrapper targets against real execution rather than assuming that `apps/*` automatically supports Android. Do not silently substitute BrightCommon or claim that building the SDK's own `:tool` module is evidence of the MealForge app build. Record any pre-existing SDK failures separately from application regressions.
- Produce a debug APK with its exact candidate commit SHA and SHA-256 checksum. No claim of device behavior or web parity without the corresponding evidence.

Owner separately decides external repository creation, any hosted CI/PR/merge/release, device installation/access and Cloudflare/server changes. Phone installation is not part of code acceptance.
