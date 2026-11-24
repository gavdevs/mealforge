# MealForge Light Phone III — native port contract matrix

Status: contract reference for `apps/light-phone`. Derived by reading the current web routes/components and
API service/router code at `7841e51`, plus the pinned Light SDK revision
`251237ef3778c907c5dbd89e84685532c9d99a28` (clean clone `/tmp/mealforge-light-sdk-baseline`).
Server claims are read from source: this document does not execute the protocol, the Android build, or a device run,
and makes no parity claim. Section 8 is the shared contract data for the grocery tracer bullet; §9 lists open
blockers. No feature code, tests or checks were produced here.

## 1. Server access model (from executable code, not stale docs)

| Question | Answer | Evidence |
| --- | --- | --- |
| Authentication | **None.** Every tRPC procedure is `publicProcedure`; there is no auth middleware, no auth directory, no session/cookie/token handling. | `apps/api/src/trpcInit.ts`, `apps/api/src/trpc.ts`, `apps/api/src/middleware/` (only `errorHandler.ts`), routers |
| Headers the web client sends | Only `content-type` on non-GET. No credentials, no custom auth header. | `apps/web/src/lib/trpc.ts` (`httpBatchLink({url:'/trpc'})` with no `headers`), `@trpc/client` `fetchHTTPResponse` |
| Access control | Deployment/network placement only: private network (Tailscale/VPN) or an authenticating reverse proxy. | `README.md:56`, `README.md:80`, `docker-compose.yml` comment |
| Server URL as the web sees it | Same origin, relative `/trpc`; Vite dev proxies `/trpc` → `:3000`. | `apps/web/src/lib/trpc.ts`, `apps/web/vite.config.ts` |
| Native equivalent | Configurable base URL + `/trpc` suffix; default `https://meals.loosewire.dev`. No auth headers, no cookies. | Spec line 25; code above |
| Stale claims in repo docs | `apps/api/AGENTS.md` and `apps/web/AGENTS.md` describe Better Auth (`src/auth/`, `protectedProcedure`, `lib/auth.ts`, auth hook, "new protected procedures"). None of it exists: there is no `apps/api/src/auth`, no `lib/auth.ts`, no `protectedProcedure` export, no `users`/`session` table in `apps/api/src/db/schema.ts`. Treat those AGENTS lines as template text, not the current system. | file listings, `trpcInit.ts`, `db/schema.ts` |

Consequences to encode in the port:

- “Reuse the web client’s existing access model” means **no auth UI and no credential storage**. It also means the
  native client inherits whatever protects the deployment. A reverse-proxy login page is *not* reproducible by a
  native client; if the owner later fronts the API with proxy auth, this port has no mechanism for it. (No change is
  requested here.)
- Because no credentials exist, the only security choices a native setup screen can honestly offer are trust
  statements about the transport: HTTPS with a valid certificate vs plain `http://` (unencrypted) or a self-signed
  certificate. The spec requires the installer/setup to say **whether the server is trusted**.
- Plain `http://` is blocked by Android by default. The SDK exposes a `cleartext-http` capability
  (`ALLOWED_CAPABILITIES`), which sets `android:usesCleartextTraffic="true"` via the generated manifest
  (`plugin/.../ManifestGenerator.kt:88`). If the port allows `http://` server URLs, `lighttool.toml` must declare
  `capabilities = ["cleartext-http"]`; without it, cleartext URLs fail at the platform layer.
- URL composition: the web hardcodes origin-relative `/trpc`. The native config stores an **origin/base URL**
  (`https://meals.loosewire.dev`) and appends `/trpc/<path>`; trailing slashes must be normalized exactly once, and a
  stored value that already ends in `/trpc` must be rejected or trimmed (no `/trpc/trpc`). Default server is
  `https://meals.loosewire.dev` per the spec; the setup screen must show the resolved base and whether it is trusted.
- Retries/timeouts: the web `QueryClient` is constructed with no options, so TanStack Query defaults apply — queries
  retry 3× with exponential delay, **mutations retry 0×** (`query-core` `retryer.ts`/`mutation.ts`), and there is no
  request timeout at all (fetch waits indefinitely). The native client should keep “mutations never auto-retry” and
  add explicit short connect/read timeouts with a visible retry action; unlimited hangs are not acceptable on device.
- The deployment is same-origin for the web, so a PWA service worker exists but is configured **not** to cache
  `/trpc` (`apps/web/vite.config.ts` `navigateFallbackDenylist`). No response caching is part of the contract; the
  native “last read” cache is the only cache.

## 2. Wire format (tRPC over HTTP, no transformer)

The web client uses `httpBatchLink` with **no** `transformer`, so request/response bodies are plain JSON.

- Query: `GET <base>/trpc/<path>?input=<url-encoded JSON>`; batch form adds `batch=1` and an index-keyed input
  object. Mutation: `POST <base>/trpc/<path>` with `content-type: application/json` and the bare JSON input body.
- Non-batch response: `{"result":{"data":…}}` or `{"error":{…}}`.
- Batch response: `[{…},{…}]` in call order.
- Errors: `{"error":{"message":string,"code":number,"data":{"code":string,"httpStatus":number,"path"?:string,"stack"?:string}}}`.
  `code` is the JSON-RPC number (`INTERNAL_SERVER_ERROR` `-32603`, `BAD_REQUEST` `-32600`, `NOT_FOUND` `-32004`,
  `METHOD_NOT_SUPPORTED` `-32005`). `stack` only when the server runs with `isDev`.
- Service code throws plain `Error`, which tRPC maps to `INTERNAL_SERVER_ERROR` / HTTP **500**, with the thrown
  message preserved in `error.message`. So business refusals ("Name this plan before favoriting it.", "Only manually
  added items…") arrive as HTTP 500 — the native client must surface `error.message` and must not treat 500 as
  “unknown failure”.
- Zod input failures are `BAD_REQUEST` / HTTP 400 with the Zod issue array stringified into `message`.
- `Date` fields serialize to ISO-8601 strings (`JSON.stringify`); the web type explicitly says so
  (`apps/web/src/features/plans/planTypes.ts`). Parse as `Instant`, not a number.
- Unknown input keys are stripped by Zod objects; unknown response keys must be ignored by the native parser.

Simplification for the port: **do not batch.** One procedure per request keeps the response a single object and
avoids the mixed-status `207` path. Mutations must be `POST`; queries must be `GET` (no `methodOverride` is
configured, so a `POST` query is rejected `METHOD_NOT_SUPPORTED`).

Transport details the Kotlin client must match (from `@trpc/client` 11.18.0 `internals/httpUtils.ts` and
`@trpc/server` 11.18.0 `http/contentType.ts`):

- Query input is serialized as `input=<encodeURIComponent(JSON.stringify(input))>`. When a procedure’s input is
  omitted entirely (`plans.active`) the client sends **no** `input` parameter; the server treats that as `undefined`
  and the procedure’s default/optional schema applies. Sending `input=undefined` is a parse error.
- `plans.list` and `recipes.list` accept an omitted input because of `.default(...)`; a native client that always sends
  `{}` works too (defaults fill in). Sending `{"limit":0}` or `{"limit":201}` is a 400.
- `content-type: application/json` is only required on `POST`. A `GET` without it is still parsed (the server falls
  back to the JSON handler for GET).
- The server runs with `allowMethodOverride` unset, so a `POST` query is rejected with `METHOD_NOT_SUPPORTED`
  (HTTP 405). Keep queries as `GET`.
- Missing/unknown procedure path → `NOT_FOUND` (HTTP 404, `error.message` `No procedure found on path "…"`), which the
  native client should report as “server does not look like MealForge” rather than a data error.

## 3. tRPC procedure contracts

All ids are positive integers. `planId`/`id`/`mealId`/`itemId` are required.

| Procedure | Kind | Input | Success data | Server-thrown refusals (HTTP 500 unless noted) |
| --- | --- | --- | --- | --- |
| `plans.active` | query | none | `PlanSummary \| null` | — |
| `plans.byId` | query | `{planId}` | `PlanSummary \| null` (null when absent) | — |
| `plans.list` | query | `{status?: "upcoming"\|"active"\|"completed", favoritesOnly?: bool=false, limit?: int 1..100=20}` (whole input optional) | `PlanSummary[]` | Zod 400 on bad enum/limit |
| `plans.activate` | mutation | `{planId}` | `PlanSummary` | `"<displayName>" is already active. Complete it before activating another plan.`; `Meal plan <id> does not exist. Use list_meal_plans to find valid ids.` |
| `plans.complete` | mutation | `{planId}` | `PlanSummary` | plan-missing message as above |
| `plans.rename` | mutation | `{planId, name: string\|null}`; string is trimmed, 1..80 chars | `PlanSummary` | `A favorite plan needs a name. Remove it from favorites first.`; plan-missing; Zod 400 for empty/81-char name |
| `plans.toggleFavorite` | mutation | `{planId}` | `PlanSummary` | `Name this plan before favoriting it.`; plan-missing |
| `plans.setMealCooked` | mutation | `{mealId, cooked: bool}` | `PlanSummary` (the containing plan) | `Meal <id> does not exist.` |
| `recipes.list` | query | `{query?: string, favoritesOnly?: bool=false, mealType?: "breakfast"\|"lunch"\|"dinner"\|"snack", limit?: int 1..200=50}` | `RecipeSummary[]` | Zod 400 only |
| `recipes.byId` | query | `{id}` | `RecipeDetail \| null` (null when absent) | — |
| `recipes.toggleFavorite` | mutation | `{id}` | `RecipeSummary` | `Recipe <id> does not exist.` |
| `grocery.itemsForPlan` | query | `{planId}` | `GroceryItemRow[]` (may be `[]`, any plan status) | — |
| `grocery.setChecked` | mutation | `{itemId, checked: bool}` | `GroceryItemRow` | `Grocery item <id> does not exist.` |
| `grocery.addManualItem` | mutation | `{planId, name: string min 1, quantityText?: string, section?: StoreSection}` | `GroceryItemRow` | `Item name must not be empty.` (whitespace-only name passes Zod, then fails in service); unknown plan → SQLite FK constraint failure (500) |
| `grocery.removeManualItem` | mutation | `{itemId}` | `{ok:true}` | `Only manually added items can be removed from the list.`; **missing id is a silent no-op** |

Response shapes (exact field sets returned by the services):

```
PlanSummary   { planId, name: string|null, displayName, status, isFavorite, createdAt: ISO,
                completedAt: ISO|null, meals: [{ mealId, mealType, recipeId, title, cookedAt: ISO|null }] }
RecipeSummary { id, title, description, servings, prepMinutes: int|null, cookMinutes: int|null,
                tags: string[], mealTypes: string[], isFavorite, createdAt: ISO }
RecipeDetail  = RecipeSummary + { stepsMarkdown, ingredients: [{ id, name, quantity: number|null,
                unit: string|null, section, sortOrder }], usedInPlans: [{ planId, name, displayName }] }
GroceryItemRow{ id, planId, name, quantityText, section, checked, isManual, sortOrder }
```

Status guards are UI-only for two writes: `plans.setMealCooked` does not look at the plan’s status (an
`upcoming` meal can be marked cooked through the API), and `grocery.*` does not check that `planId` is active.
The web never renders those controls in those states, so the native client must enforce the same visibility
rules itself and must re-check that a mutation response describes the record currently on screen
(`setMealCooked` returns the containing plan; compare its `planId` before applying).

Notes that matter for parsing/UI:

- `quantity` on recipe ingredients is a **decimal** (`real` column); `null` means “to taste”. `unit: null` means a bare
  count. The web renders `String(quantity)` + `unit`, or `"to taste"` when `quantity === null`
  (`apps/web/src/routes/recipes.$recipeId.tsx`).
- `quantityText` on grocery rows is pre-formatted server-side (fractions like `3½ cups`, `1½ lb`, `200 g`) and is `""`
  for “no quantity”. The web grocery list shows nothing for `""`; only the recipe detail says “to taste”.
- `normalizedKey` is **not** exposed by the API. Grocery merge identity is server-only; the client must never
  synthesize or compare keys.
- `ingredients` insertion order is `sortOrder`; `meals` order is plan `sortOrder`; `grocery.itemsForPlan` order is
  `(sortOrder, name)`. `usedInPlans` is distinct plans, newest first.
- `plans.byId` returns `null` for a missing plan, but `plans.activate/complete/rename/toggleFavorite` throw. Do not
  infer existence from one call for the other.
- `recipes.list` search: `LIKE %query%` over title, tags (JSON text) and ingredient **name**, union, then filters and
  `limit`; ordered `createdAt` desc. Meal-type filter is a quoted-substring match on the `meal_types` JSON, so it is
  exact per element. Empty result is `[]`, not an error.
- `recipes.list` default limit 50; the web asks 100 (list) and 200 (plan detail metadata). `limit > 200` is a 400.
- `grocery.itemsForPlan` has **no status guard**: it returns items for any plan id. The grocery screen’s “active plan
  only” rule is a UI rule, not an API rule; bind the native screen to `plans.active` explicitly.
- `plans.list` ordering: `upcoming` = `createdAt` asc then id asc; `completed` = `completedAt` desc then id desc;
  everything else = `createdAt` desc then id desc. Web sub-tabs use `limit 50`; the active-screen fallback uses 20.
- `planDisplayName(name, id)` = name or `"Meal Plan {id}"`; the server already computes `displayName` — never
  re-derive it differently.
- `addManualItem` trims the name, defaults `quantityText` to `""` and `section` to `"other"`, sets `isManual: true` and
  `sortOrder: 10000`. Its `normalizedKey` is `manual:<lowercased name>:<Date.now()>`, so identical manual names are
  separate rows (and two adds in the same millisecond collide on the `(planId, normalizedKey)` unique index → 500).
  This is the concrete reason repeated-tap guards are mandatory, not cosmetic.

## 4. Lifecycle, status and invalidation rules

State machine (server-enforced, mirrored by the web):

- At most one `active` plan (partial unique index `meal_plans_one_active`). `activate` is idempotent for the already
  active plan; it refuses while a *different* plan is active; activating a `completed` plan clears every `cookedAt`
  (a fresh run); activating clears `completedAt`.
- `complete` is idempotent and keeps `cookedAt` marks; `completedAt` is set once.
- Favorite rules: favoriting requires a non-null name; a favorite cannot have its name cleared. Unfavoriting first is
  the only way to clear the name of a favorite.
- Rename `null` clears to `"Meal Plan {id}"`. The web sends `null` when the trimmed draft is empty, `maxLength 80`.
- The web shows the meal cooked control when `plan.status !== "upcoming"` (active and completed), never on upcoming.
- Grocery items are derived from the plan’s recipes at push time. Manual items are never touched by re-push; checked
  state survives for unchanged aggregated keys.

Client cache/invalidation obligations, taken from the web query invalidation:

| Mutation | Web invalidates | Additional native obligation |
| --- | --- | --- |
| `plans.activate` / `complete` / `rename` / `toggleFavorite` / `setMealCooked` | `plans.*` **and** `grocery.*` | derive “active plan” and “grocery list” from the same refetch; a completed plan makes the grocery tab empty |
| `recipes.toggleFavorite` | `recipes.*` and `plans.*` | plan meal cards show recipe favorite stars |
| `grocery.setChecked` | `grocery.itemsForPlan({planId})` | optimistic flip allowed (web does it), reconciled by refetch |
| `grocery.addManualItem` | `grocery.itemsForPlan({planId})` | wait for server success, then refetch; clear the draft only on success |
| `grocery.removeManualItem` | `grocery.itemsForPlan({planId})` | wait for `{ok:true}`, then refetch |

Only two writes are optimistic in the web app: grocery `checked` and meal `cookedAt`. Everything else waits for the
server. Native must not invent optimistic creates/deletes/renames.

Response/ownership guards required by the spec:

- Bind every grocery write to the currently displayed `planId` + returned `itemId`; bind `setMealCooked` to the
  displayed plan’s `mealId`. Discard late responses if the screen was left or the plan changed.
- One in-flight mutation per row plus one per plan-level action; disable the control and drop repeat taps.
- Screen exit cancels work: `LightActivity.goBack()` destroys the popped screen and clears its `ViewModelStore`
  (`sdk/client/.../LightActivity.kt`), so `viewModelScope` jobs are cancelled. A request already sent may still have
  been applied server-side — refetch on return rather than assuming failure.

Last-read cache (spec permits small caches only):

- Persist, via `lightContext.dataStore`, at most: server URL, last successful `plans.active`, last successful
  `plans.list` per status, last successful `grocery.itemsForPlan` keyed by `planId`, timestamp of each.
- Show cached data with an explicit “last read HH:MM” / stale marker. Never render a cached write as confirmed, never
  queue writes. No polling while the screen is dark; refresh only on screen show and explicit user actions.
- `lightContext.connectivity.observeNetworkStatus()` is available for an offline banner (`ACCESS_NETWORK_STATE` is an
  allowed permission).

## 5. Native port matrix (web surface → native surface)

Navigation adaptation: the web has a persistent 3-tab bar (`__root.tsx`), which the spec forbids. Native uses SDK
screen navigation (`navigateTo` / SDK back), with a home list as the fixed entry point. Wheel/touch scrolling comes
from `LightScrollView` / `LightLazyScrollView`; there is no persistent bar.

| Web route / component | Behavior to preserve | Native screen | Contract call(s) |
| --- | --- | --- | --- |
| `/` → redirect `/plans` | Home is plans | Home/menu list: Plans (Active / Upcoming / Completed / Favorites), Recipes, Grocery, Settings | — |
| `/plans` `plans.index.tsx` | Active plan full view; if none, empty state + upcoming preview list with **Set active** buttons; while loading, loading text | Plans list (Active) | `plans.active`; if null `plans.list{status:"upcoming",limit:20}`; `plans.activate` |
| `/plans/upcoming` | All upcoming, cards with 4-meal preview and “+N more meals” | Upcoming list | `plans.list{status:"upcoming",limit:50}` |
| `/plans/completed` | Completed history, newest first | Completed list | `plans.list{status:"completed",limit:50}` |
| `/plans/favorites` | All favorites | Favorites list | `plans.list{favoritesOnly:true,limit:50}` |
| `/plans/$planId` | Detail; invalid id or null → “This meal plan doesn't exist.” + back | Plan detail | `plans.byId{planId}` |
| `PlanView` header | displayName, edit affordance, favorite star, status line: `N meal(s)` + (`M cooked` only when > 0) + (`completed <date>` for completed else the raw status word `active`/`upcoming`), joined with ` · ` | Plan detail header | from `PlanSummary` |
| `PlanView` rename | Edit → text field prefilled with `name ?? ""`, placeholder `Meal Plan {id}`, Save/Cancel; Save sends trimmed or `null`; error hint inline; clears on success | Plan name editor (full-screen text entry) | `plans.rename` |
| `PlanView` favorite | Unnamed + not favorite → open rename editor with “Name this plan to favorite it.” and **no** API call; otherwise toggle | Plan detail toggle | `plans.toggleFavorite` |
| `PlanView` meal groups | Group by `MEAL_TYPES` order (breakfast, lunch, dinner, snack), skip empty groups; cooked meals dimmed; per-meal recipe star; times/tags from `recipes.list{limit:200}` lookup, title-only fallback | Plan detail body | `plans.byId` plus `recipes.list` metadata |
| `PlanView` cooked toggle | Button label `Complete` / `Completed ✓`; optimistic flip; aria-labels “Mark <title> as cooked/not cooked”; hidden on upcoming | Plan detail per-meal toggle | `plans.setMealCooked{mealId,cooked}` |
| `PlanView` navigation to recipes | Tapping a meal/recipe opens the recipe detail; the plan’s own back returns to the plan | Recipe detail (pushed from plan) | `recipes.byId` |
| `PlanView` plan action | Active → **Complete this plan**; else **Cook it again — make active** (completed) or **Make this the active plan** (upcoming) | Plan detail bottom action | `plans.complete` / `plans.activate` |
| `PlanCard` | name (★ if favorite), meta line (`N meals · active now` / `· completed <date>` / `· added <date>`), 4 meal previews, overflow count; tap → detail | List row | `PlanSummary` |
| `/recipes` `recipes.index.tsx` | Tabs Favorites / History (`favoritesOnly`), debounced (250 ms) trimmed text search, meal-type filter chips with tap-to-clear, `limit 100`, distinct empty states (no matches vs no favorites vs no recipes) | Recipe list | `recipes.list` |
| `RecipeCard` | title, total time (`prep+cook`, `N min` / `H hr` with one decimal ≥90), first 3 tags, favorite star | Recipe list row | `recipes.list` |
| `/recipes/$id` | Loading; null → “This recipe doesn't exist (it may have been replaced in a plan revision).” + back | Recipe detail | `recipes.byId` |
| Recipe detail header | title, `serves N`, optional `prep Nm` / `cook Nm`, meal-type labels (`Snacks` for snack), favorite star, description if non-empty | Recipe detail | `recipes.byId` |
| Ingredients | Group by `STORE_SECTIONS` order with unknown sections appended; section header only when more than one section; quantity `String(quantity)+unit` or “to taste” | Recipe detail | `recipes.byId` |
| Steps | Rendered markdown (escaped HTML first) | Recipe detail (plain/adapted — see blocker B2) | `recipes.byId` |
| Usage summary | `cooked once · last in <displayName>` or `cooked N× · last in <displayName>`; hidden when empty | Recipe detail footer | `usedInPlans` |
| Start cooking / `?cook=true` | Full-screen cook view over the detail | Cook screen | `recipes.byId` (reuse loaded detail) |
| Cook mode | `splitSteps` rules below; `index+1 / total`; Back disabled at first; Next step; last step shows **Done — serve it** → return to detail; Exit button; best-effort wake lock on enter, released on leave | Cook screen | local only |
| `/grocery` | Active plan header `displayName · done/total in the cart`; **no active plan** → “No list yet” empty state; groups only **unchecked** items by section; “Everything's in the cart” when no unchecked item remains and the list is non-empty; unchecked manual rows tagged “added” with a remove control; checked items in a separate “In the cart” block in list order (no remove there); add-item form | Grocery screen | `plans.active`, `grocery.itemsForPlan`, `grocery.setChecked`, `grocery.addManualItem`, `grocery.removeManualItem` |

Grocery presentation rules that must not drift:

- Section grouping uses `STORE_SECTIONS` order and only when at least one **unchecked** item exists in that section;
  unknown section strings are appended after the known ones (server may carry older/free-form values).
- Grocery section headers are always shown for each open section (no “only if more than one” rule). Recipe-detail
  ingredient headers, by contrast, appear only when the detail has more than one section.
- Checked items are **not** grouped by section in the web UI; they render as one block in `itemsForPlan` order.
- The remove control exists only on **unchecked** manual rows in the web UI. The “In the cart” block renders checked
  rows with no remove action, so the web requires unchecking a manual item before it can be deleted. The API itself
  does not require `checked == false`; the native port should match the web and keep remove on open rows only.
- Unchecking is allowed; `setChecked` is an absolute set, not a toggle.
- Remove is offered only when `isManual`; there is no confirmation dialog in the web UI. Generated items get no remove
  control at all, and the server rejects them anyway.
- The web add-item form exposes **name + section only** (section defaults to `other`); `quantityText` exists in the
  procedure. The approved native spec additionally requires optional quantity entry, using that existing input.
- A plan with zero grocery rows renders the header and the add-item form with no sections — **not** the “No list yet”
  empty state: `!items` is false for `[]`. “No list yet” is reached only when there is no active plan (query
  disabled) or when a failed `itemsForPlan` leaves `data` undefined while `isLoading` is false. The native screen
  must distinguish “no plan”, “empty list” and “fetch failed”; the web conflates the last with the first.
- The count in the header is `checked / all`, computed from the same list the screen renders.

### 5.1 Loading, empty, absent and server-error states

The web has no error boundary and no `onError` for queries, so a failed query leaves `data` undefined and the page
renders its **empty** state — indistinguishable from genuinely-empty data. The spec requires a distinct server-error
state, so the native port must add one and label it; this is a required correction, not a deviation from intent.

| Surface | Loading (web text) | Empty | Absent record | Server/transport error |
| --- | --- | --- | --- | --- |
| Plans active | “Loading your plan…” | “No active meal plan” (+ upcoming list if non-empty) | — | error panel with retry; must not say “No active meal plan” |
| Plans upcoming | “Loading…” | “Nothing queued up” | — | error panel with retry |
| Plans completed | “Loading…” | “Nothing completed yet” | — | error panel with retry |
| Plans favorites | “Loading…” | “No favorite plans yet” | — | error panel with retry |
| Plan detail | “Loading…” | — | “This meal plan doesn't exist.” + back to plans (also used for a non-numeric/non-positive route parameter) | error panel; do not show “doesn't exist” for a transport failure |
| Recipes list | “Loading…” | search text non-empty → “No matches” with the query quoted; favorites tab → “No favorites yet”; history → “No recipes yet” | — | error panel with retry |
| Recipe detail | “Loading…” | — | “This recipe doesn't exist (it may have been replaced in a plan revision).” + back | error panel |
| Grocery | “Loading the list…” | “No list yet” only when there is no active plan | — | error panel (web shows “No list yet”) |

Mutation errors:

- `plans.rename` and `plans.activate` surface `error.message` in an inline hint and keep the user on the screen
  (draft text preserved for rename). Native must do the same.
- `plans.complete`, `plans.toggleFavorite`, `plans.setMealCooked`, `recipes.toggleFavorite` and all `grocery.*`
  mutations have **no** error UI in the web app; a failure silently reverts via refetch. Native must show a short
  failure notice and refetch — silence would violate “failures reconcile visibly”.
- No mutation retries automatically. After a timeout or 5xx the client must refetch rather than re-send a write,
  because a write may already have been applied (the server has no idempotency keys).

Navigation affordance verified against the SDK: `sdk/client/README.md:64` claims the SDK “renders a back bar at the
bottom of the screen”. It does not — `LightActivity.setContent` composes only `entry.screen.Content()` plus transient
modals. Every native screen must draw its own back affordance (`LightTopBar(leftButton = LightBarButton.LightIcon(
LightIcons.BACK, onClick = …))`, as the tool-manager example does) alongside the platform back gesture.

## 6. Cook-step splitting (port this algorithm exactly)

`splitSteps` (`apps/web/src/lib/steps.ts`), used only by cook mode:

1. A line matching `/^\s*\d+[.)]\s+(.*)$/` starts a new step; anything before the first match is dropped; non-empty
   unnumbered lines are folded into the current step; blank lines are dropped; each step is trimmed.
2. If no numbered line exists, split on `\n{2,}`, trim, drop empties.
3. If that also yields nothing, the view shows one empty step (index 0 / `steps.length`, Back disabled).
4. `Next` clamps at the last index; reaching the last index swaps the button to “Done — serve it”.

`splitSteps` is part of the user-visible contract; a native implementation that splits differently changes cook mode.

## 7. SDK constraints verified at `251237e` (real APIs only)

| Area | Constraint | Source |
| --- | --- | --- |
| App shape | One `@InitialScreen` + `@EntryPoint` object; screens extend `LightScreen<R,VM>` (or `SimpleLightScreen`) and are created by `navigateTo`; `@Composable Content()` is the UI | `sdk/client/.../LightScreen.kt`, `EntryPoint.kt`, `InitialScreen.kt`, `tool/.../HomeScreen.kt` |
| Navigation/back | SDK owns the back stack; system back → `LightActivity.goBack()`; `LightViewModel.onBackPressed(): Boolean` can consume; `goBack(result)` pops; popping destroys the screen and clears its `ViewModelStore` | `LightActivity.kt`, `LightScreen.kt`, `LightViewModel.kt` |
| Lifecycle hooks | `willShow`/`willHide`/`onAppPause`/`onScreenDestroy`; **`onResume` calls `notifyWillShow` again**, `onPause` calls `notifyAppPause`. No background refresh hook; no destroy on pause | `LightActivity.kt:219-227`, `LightScreen.kt` |
| Key events | Non-system keys go to the active screen first (`onKeyDown/Up/Multiple`); BACK/HOME are short-circuited and never reach the screen | `LightActivity.kt:153-195`, `examples/ui-demo/.../UiDemoKeyEventsScreen.kt` |
| Rotary/volume keys | No SDK scroll helper. `light-keyboard` v0.0.19 exposes `LightDeviceKeys`: VolumeUp 24, VolumeDown 25, ShutterPressed 27, ShutterHalfPressed 80, RotaryTurnUp 317, RotaryTurnDown 318, RotaryButtonPress 319 (verified with `javap` on the resolved AAR). Handling them requires `onKeyDown` in a screen/VM; device behavior is unverified here | `light-keyboard-v0.0.19-api.jar`, `sdk/client/.../LightActivity.kt` |
| Scrolling/typography | `LightScrollView` (drag + scrollbar) and `LightLazyScrollView` (uniform item height); `LightText` variants; `LightThemeColors.Dark` = black bg, white content, `0xFFBBBBBB` secondary | `sdk/ui/.../LightScrollView.kt`, `LightText.kt`, `LightTheme.kt` |
| Text entry | Committed full-screen `LightTextInputEditor(title,state,onSubmit,onBack,keyboardOptionsFlow,…)` with embedded LP3 keyboard, `singleLine`, `submitLabel`, `showBackButton`; `LightTextField` is read-only tap-to-edit; `rememberKeyboardOptions()` round-trips to the LightOS server and may return null (falls back to cached defaults) | `sdk/ui/.../LightTextInputEditor.kt`, `LightTextField.kt`, `sdk/client/.../LightKeyboardManager.kt`, `examples/weather/.../WeatherHomeScreen.kt` |
| Screen wake | Compose `Modifier.keepScreenOn()` is available without direct Activity/Context access. It was added in Compose UI 1.9.0; the resolved 1.10.6 AAR contains `KeepScreenOnKt`, `KeepScreenOnElement` and `KeepScreenOnNode`. Apply only while cook mode is composed and verify pause/exit release; native runtime proof is still required. | [Android API reference](https://developer.android.com/reference/kotlin/androidx/compose/ui/keepScreenOn.modifier), resolved `ui-android:1.10.6`; SDK allow-list permits Compose |
| Networking | `ktor-client-core/okhttp/content-negotiation/serialization-json` are on the SDK client’s `api` classpath and allow-listed; `kotlinx-serialization` allowed; no auth helpers | `sdk/client/build.gradle.kts`, `plugin/.../allowed-dependencies.txt`, `examples/weather/.../WeatherApi.kt` |
| App metadata | Declared in `lighttool.toml` only: `tool.id` (dotted lowercase), `label` ≤50, `versionName` semver, `versionCode`, `permissions`, `capabilities`, `serverPackage` (must be `com.lightos` on device); manifest is generated, a hand-written `AndroidManifest.xml` is a build error; no Java sources | `plugin/.../LightToolMetadata.kt`, `ManifestGenerator.kt`, `LightSdkPlugin.kt` |
| Build toolchain | `compileSdk 36`, `minSdk 34`, `targetSdk 34`, `jvmTarget 17`; AGP 8.12.3, Kotlin 2.3.20; consumer module must apply `com.android.application` + `com.thelightphone.light-sdk` and be named outside the SDK’s module set | root `build.gradle.kts`, `gradle/libs.versions.toml`, `LightSdkPlugin.kt` |

Pinned-dependency note (no execution claim): the SDK modules are `group=com.thelightphone`, `version=0.2.3`, and
`settings.gradle.kts`/`plugin` assume a single Gradle build (`project(":sdk:client")`, `includeBuild("plugin")`).
A separate `apps/light-phone` build therefore needs either an included build with dependency substitution for
`com.thelightphone:sdk-client/sdk-ui/sdk-shared` or published `0.2.3` artifacts plus the included plugin build. That
integration is scaffold work (not re-verified here); it must not be described as working until executed.

## 8. First grocery tracer bullet — contract data for the port’s tests

Purpose: the smallest end-to-end slice that proves request format, aggregation, grouping, check-off and manual-item
restrictions. Run it against a disposable/local instance with fixtures (never the owner’s data).

Seed with MCP `push_meal_plan` on an empty database (this becomes the active plan; ids are server-assigned, so assert
on names/values, never fixed ids):

```
meals: [
 { mealType: "dinner", recipe: { title: "Tracer Chicken", servings: 4, prepMinutes: 10, cookMinutes: 25,
   stepsMarkdown: "1. Sear the chicken.\n2. Roast it.",
   ingredients: [
     { name: "chicken thighs", quantity: 1.5, unit: "lb",  section: "meat-seafood" },
     { name: "lemons",         quantity: 2,   unit: null,  section: "produce" },
     { name: "spinach",        quantity: 200, unit: "g",   section: "produce" },
     { name: "olive oil",      quantity: 2,   unit: "tbsp",section: "pantry" },
     { name: "salt",           quantity: null,unit: null,  section: "spices" },
     { name: "milk",           quantity: 1.5, unit: "cup", section: "dairy-eggs" } ] } },
 { mealType: "breakfast", recipe: { title: "Tracer Oats", servings: 2, prepMinutes: 5, cookMinutes: null,
   stepsMarkdown: "1. Stir.\n2. Chill.",
   ingredients: [
     { name: "rolled oats", quantity: 2, unit: "cup",   section: "pantry" },
     { name: "milk",        quantity: 2, unit: "cup",   section: "dairy-eggs" },
     { name: "lemons",      quantity: 2, unit: null,    section: "produce" },
     { name: "garlic",      quantity: 2, unit: "clove", section: "produce" } ] } } ]
```

Expected `GET /trpc/grocery.itemsForPlan?input={"planId":<activePlanId>}` data, in returned order
(`sortOrder` asc, then name): 8 rows, `isManual:false`, `checked:false`:

| # | name | quantityText | section | why |
| --- | --- | --- | --- | --- |
| 0 | garlic | `2 cloves` | produce | count family, pluralized |
| 1 | lemons | `4` | produce | count, no unit; 2+2 merged |
| 2 | spinach | `200 g` | produce | metric weight |
| 3 | chicken thighs | `1½ lb` | meat-seafood | 1.5 lb → oz → lb, fraction glyph |
| 4 | milk | `3½ cups` | dairy-eggs | 1.5+2 cups, merged by volume family |
| 5 | olive oil | `2 tbsp` | pantry | volume below 1 cup → tbsp |
| 6 | rolled oats | `2 cups` | pantry | volume ≥1 cup |
| 7 | salt | `""` | spices | null quantity → “to taste”, blank text |

Checks the slice must prove:

1. Query wire form and parsing: non-batch `{"result":{"data":[…]}}`; batching is not used by the native client.
   Batch form `?batch=1&input={"0":{"planId":N}}` returns `[{"result":{"data":[…]}}]` if a test wants to cover it.
2. Aggregation is server-side only: the client sends nothing but `planId` and must not re-merge or re-format.
3. Grouping: produce → meat-seafood → dairy-eggs → pantry → spices, headers only for non-empty sections; `salt`
   renders with no quantity text (grocery), while recipe detail shows `to taste`.
4. Check-off: `POST /trpc/grocery.setChecked` body `{"itemId":<milk id>,"checked":true}` →
   `{"result":{"data":{…,"checked":true}}}` with every other field unchanged. `checked:false` restores it.
   Unknown id → HTTP 500, `error.data.code == "INTERNAL_SERVER_ERROR"`,
   `error.message == "Grocery item <id> does not exist."`.
5. Manual add: `POST /trpc/grocery.addManualItem` body `{"planId":N,"name":"paper towels"}` →
   row `{name:"paper towels", quantityText:"", section:"other", checked:false, isManual:true, sortOrder:10000}`.
   It appears after all generated rows in `itemsForPlan` and groups under **Other**.
6. Manual add with `"section":"produce"` appears inside Produce after `spinach` (sortOrder 10000).
   Whitespace-only name `"   "` → HTTP 500 `Item name must not be empty.` (Zod passes, service rejects).
   Unknown `planId` → HTTP 500 (FK constraint), not 404.
7. Manual remove: `POST /trpc/grocery.removeManualItem` body `{"itemId":<manual id>}` → `{"result":{"data":{"ok":true}}}`;
   the next `itemsForPlan` no longer contains it. Removing a **generated** id → HTTP 500
   `Only manually added items can be removed from the list.` Removing an unknown id → `{ok:true}` no-op.
8. Empty list: a plan with no items returns `{"result":{"data":[]}}` (200), never null.
9. Input validation: `{"planId":"1"}` (string) → HTTP 400, `error.data.code == "BAD_REQUEST"`, Zod issue text in
   `error.message`; `{"planId":0}` → 400.
10. No active plan: `plans.active` → `{"result":{"data":null}}`; the grocery screen shows “No list yet” and must not
    call `itemsForPlan` with a placeholder id.
11. Cache labeling: with a cached list present, a failed refetch shows the cache plus “last read” and does not display
    a check-off or add as confirmed.

These are protocol/behavior expectations read from source; the first execution against a server is still required to
call them proven.
Parent’s scaffold session has since run ten real Hono+SQLite grocery protocol checks against a disposable DB; those
results supersede “not executed” for the cases they cover. Still **open** (source-only, unexecuted): the exact
`207` mixed-status batch path (not used by this port), FK-violation message text for an unknown `planId`, whether a
`Date` round-trips through the deployed server identically to local, and any deployed-server difference from `7841e51`.

## 9. Blockers and owner decisions

| # | Issue | Why it blocks / what is needed |
| --- | --- | --- |
| B1 | Cook-mode wake lifecycle is unverified, **not blocked by a missing SDK API**. Compose provides `Modifier.keepScreenOn()` (since 1.9.0), present in the resolved UI 1.10.6 dependency. | Use the composition-scoped modifier without `LocalContext`, `LocalView` or Activity/window access; prove enter/leave/pause behavior during implementation. No SDK-core change or wake waiver is justified by current evidence. |
| B2 | Recipe `stepsMarkdown` is markdown; no third-party markdown library is on the SDK dependency allow-list. | Implement readable monochrome rendering with supported Compose primitives; preserve step splitting. Any semantic presentation loss requires explicit approval, but an extra dependency is not inherently necessary. |
| B3 | The web form exposes name + section; the API supports optional `quantityText`. The approved native spec explicitly requires optional quantity entry. | Preserve the approved native name/optional-quantity/optional-section inputs using the existing procedure. This source difference does not authorize silently removing the quantity field or require a new backend. |
| B4 | Error messages include MCP-oriented wording. | Preserve server refusal semantics and show their messages, as the web does; no owner decision or server change is required for this checkpoint. |
| B5 | Server URL trust/transport: cleartext `http://` needs the `cleartext-http` capability in `lighttool.toml`; self-signed TLS needs an explicit trust decision; reverse-proxy auth cannot be reproduced. | Setup flow must state trusted/untrusted and the port must decide whether to accept `http://` at all. |
| B6 | Rotary/wheel interaction is unverified; key codes come from `light-keyboard` v0.0.19, and no SDK component automatically scrolls on them. | Implement and test supported key-event handling with bounds and back navigation; verify on the target surface when authorized. Touch-only scrolling is not an approved substitute for the requested wheel interaction. |
| B7 | Markdown-free long-text rendering and text entry practicality on a 1080×1240 device are unverified. `LightTextInputEditor` is full-screen with an embedded keyboard and a server round-trip for options. | Verify on emulator during implementation; design grocery/name entry as one field per screen, not a web-style inline row. |
| B8 | `addManualItem`’s `Date.now()` normalized key can collide for two adds in the same millisecond (unique index → 500). | Implement the per-row in-flight guard (already required) and treat the collision 500 as a generic failure with refetch. |
| B9 | Pinned-SDK build integration (included build + dependency substitution vs published `0.2.3`) is unproven; the SDK settings file assumes its own root build. | Scaffold owner must execute it; this document does not claim it works. |
| B10 | `apps/light-phone` under the pnpm `apps/*` glob and Nx has no `package.json`/`project.json`, so neither tool will build it and Nx autodiscovery must not be assumed. | Add minimal Nx wrapper targets only if useful; never treat `apps/*` membership as Android support. |

### 9.1 Open source questions (deliberately not expanded)

Per the parent’s handoff, investigation stopped here. These are unresolved and must not be assumed either way:

1. Whether the deployed `https://meals.loosewire.dev` runs exactly `7841e51` (no deployed-server diff was read).
2. The literal SQLite FK-error message an unknown `planId` produces through tRPC (shape is 500; text unverified).
3. Whether the owner intends reverse-proxy/single-user auth later, which would invalidate “no auth UI” for the port.
4. Committed `LightTextInputEditor` is the baseline input mechanism. The spec explicitly excludes the live checkout’s
   uncommitted text-input work; do not reopen that decision unless the committed mechanism proves insufficient.

## 10. Explicit non-goals confirmed against the web app

There is no web UI for creating/editing recipes, authoring plans, deleting plans, un-completing a plan, reordering
meals, recipe timers, cooking sessions, or offline sync. Those must not appear in the native port. Plan and recipe
creation arrives only through MCP (`push_meal_plan`, `create_recipe`), and the port keeps that boundary.
