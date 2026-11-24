# MealForge Light Phone III port

Status: Owner-scoped; implementation has not started. Supersedes the assistant's earlier read-only-first proposal and cooking-session question. This scope does not authorize deployment, merge, release or device installation.

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

Black background, white primary text and secondary gray text only. Translate tabs, chips, cards and touch controls into plain wheel-scrolled lists, text choices, simple manual text-entry screens and minimal separators. No colored accents, decorative card fills, grid/card navigation, persistent tab bar or app-drawer assumptions.

Plan content around the available display and the Light SDK's screen/navigation/input behavior, verified against its current examples and documentation rather than guessed key mappings. Recipe ingredients, steps and grocery names must remain readable with long content. Entering/editing manual grocery data and a plan name must be practical on the device; don't silently drop these workflows because the phone has limited input. Do not automatically depend on or consume the uncommitted text-input work in the shared checkout; use a committed SDK revision and request a separate decision if that in-flight work becomes required.

Use small last-read caches only to make ordinary Android reopening/network interruption useful; cached data is visibly stale and must never fabricate an offline write. No background polling while the screen is dark and no offline edit queue/sync framework is part of the port.

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
