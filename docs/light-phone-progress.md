# Native client progress

## Run 1 checkpoint boundary (historical)

This is a scaffold/tests-first checkpoint, not the native port. Root `AGENTS.md` requires tests and implementation in separate sessions. No production protocol, data model, workflow, cache, or mutation implementation is included in this session. The full acceptance scope in `light-phone-client-spec.md` remains unchanged.

Session: `01a20b9e-6dc9-7385-9d33-bfdcadab0ca5`.
Session file: `/home/gav/.hermes/profiles/forge/cache/scratch/omp-mealforge-light-phone/sessions/2026-11-24T03-34-21-897Z_01a20b9e-6dc9-7385-9d33-bfdcadab0ca5.jsonl`.
Baseline: `feat/light-phone`, `7841e5133e1d93874fcccc5d8383a7883d9dde75`.
Only initial handoff changes were the untracked spec and SVG/PNG concept; they remain owner-owned.

## Incremental plan

- [x] Verify baseline and preserve handoff files.
- [x] Exercise single-request grocery protocol against unchanged Hono/tRPC and migrated disposable SQLite.
- [x] Verify independent pinned SDK scaffold and executable Nx wrappers.
- [x] Run the prior-session native protocol tests; distinguish compile-RED from behavior RED.
- [x] Implement the real protocol codec in the authorized implementation continuation.
- [x] Add the SDK grocery/model/UI path and prove check/add/remove through disposable HTTP/SQLite reads.
- [x] Add compiled native plan/recipe/cook, rename/favorite/cooked, grocery-entry/removal, navigation and lifecycle counterparts, test-first. Runtime acceptance remains separate.
- [x] Perform independent read-only review, static checks, APK build, artifact checksum and device-independent handoff. No install/release authorization; runtime acceptance remains open.

## Protocol evidence

Command: `pnpm exec vitest run apps/light-phone/contract/protocol.test.ts`.
Result: **10 passed**, 1 file, exit 0. Actual backend adapter and SQLite migrations, not mocked service responses. Tests run in-process through `Hono.request`; this is not TCP, Android transport or production-endpoint proof.

Committed as `5602bfd97a9bd5ef933a3b88891838fd832e65b2` (`test(light-phone): verify disposable grocery wire contracts`), 1 file, 204 insertions. Post-commit `pnpm exec biome check apps/light-phone/contract/protocol.test.ts && pnpm exec vitest run apps/light-phone/contract/protocol.test.ts` passed: 1 formatted/linted file and 10 tests. An initial formatting-only check failed before the formatting correction; no lint rule was disabled.

Covered: plain query input and `result.data` response; grocery IDs/booleans/formatted quantities; absent plan versus empty grocery list; no-input active-plan query and ISO dates; check/recheck/uncheck with subsequent reads and other-plan isolation; manual-item trimming/defaults/explicit fields and removal with readback; generated-item removal refusal; absent-item removal no-op; validation and missing-target errors.

SDK baseline: in an isolated clean clone at the pinned revision, `JAVA_HOME=/home/gav/.local/share/light-tern-toolchain/jdk17 ANDROID_HOME=/home/gav/.local/share/light-tern-toolchain/android-sdk ./gradlew -DlightSdk.toolOnly=true :sdk:client:assembleDebug --console=plain` succeeded in 27 seconds (40 tasks executed). Temurin 17.0.21+9 and Android SDK 36 can build the pinned SDK client. Existing publication/deprecation warnings remain; this is not yet proof of the independent MealForge app.

Native tests target the first protocol increment, not full acceptance. They call the intended `TrpcProtocol` API directly. The SDK scans all Kotlin under `src`, including tests, and prohibits reflection; no reflective bridge or SDK-policy bypass is used. With the production codec absent, compilation is expected to fail before test execution. Such a result is compilation RED, **not** the required behavior-level RED, and must not be counted as executed native tests.

## Remaining proof

Native plan/recipe/grocery/cook counterparts now compile and build; 56 JVM tests cover protocol, real disposable HTTP writes/readback, stores and cook intent. Rich markdown formatting is not implemented. Actual keyboard/wheel/back, persistent cache reopening, visual layout, Android networking and physical wake behavior remain unverified. No installation, production mutation, SDK-core edit, API/web edit, signing/release, push or deployment is included. Full web-port acceptance is not claimed.

Build/test results and local commit identifiers are recorded in the final handoff and build documentation after execution.

## Acceptance ledger

| Spec acceptance item | Current evidence / remaining work |
| --- | --- |
| All native web counterparts | Native lists/details/actions/input/cook screens now implemented and compiled. No visual/device acceptance; instruction markdown is literal text rather than rich formatting. |
| Contract coverage | 10 unchanged-server checks; 56 native JVM cases cover codec, real plan/recipe/grocery HTTP, stores, cooking and fixture startup. |
| Unit/UI safeguards | Grocery/plan/recipe gates, stale/cancel behavior and cook navigation/wake intent tested. Actual wheel/back/text input, rendering, persistence and wake behavior remain unverified. |
| Designated-instance integration | Native Ktor → loopback HTTP → unchanged Hono/tRPC → migrated in-memory SQLite with write/readback. No Android network/device test. |
| Independent review, static checks and app build | Source workflow review found no blocker. Boundary review found relative SDK path divergence, now fixed and regression-tested; reviewer closed it. Forced native tests/lint and real Nx targets passed. Runtime acceptance remains. |
| Candidate debug APK and checksum | Source `596b58daf149eb56f485ea4ef92cff964d1d88b7`, unsigned APK retained with SHA-256 below. No device/full-parity acceptance. |

## Continuation 2

The owner resumed the completed bounded test-writing run as an implementation
continuation. Existing native protocol tests predate this continuation. New
grocery behavior/integration tests are being written by separate test-only
agents before their corresponding production code, per the feature skill's
separate-session/agent boundary.

- Provisioning RED: 3 failed / 1 passed; observed clean clones rejected and a dirty
  wrong-revision checkout's HEAD moved before rejection.
- Provisioning GREEN: 4 passed after guarding dirty inputs before checkout and
  populating only the uninitialized no-index/no-working-files state.
- First Gradle failure: catalog import used bare `File`; corrected to `files(...)`.
- Native protocol compile-RED: four unresolved references, zero tests executed.
- Protocol GREEN: 8 passed, 0 skipped/failures/errors; independent unsigned
  `:app:assembleDebug` succeeded with JDK 17 / Android SDK 36.
- Saved checkpoint `17ccf054f9025ac61c0e18834b0cf225f222e74b`: pinned scaffold,
  safe provisioning and real tRPC codec. Original owner spec/mockup files remain
  uncommitted and untouched.

The first failed provisioning recheck used a partial local clone as its fixture
remote and could not obtain missing promisor blobs. Populating the default SDK
from its declared fork resolved that prerequisite; no dirty input was deleted
or guard weakened. See `light-phone-build.md` for exact commands.

Nx execution at checkpoint `17ccf054f9`: `light-phone:test` and
`light-phone:build` each executed their real `prep` dependency and succeeded.
The revised test wrapper was executed with the tracer: four provisioning tests
passed, Gradle test succeeded (22 tests retained, up-to-date), and Nx build
succeeded (unsigned APK, tasks up-to-date). Prep ran in both targets.

Grocery implementation proof: `apps/light-phone/gradlew :app:testDebugUnitTest
-DlightSdk.unsigned=true :app:assembleDebug --console=plain` succeeded, 90 tasks,
20 executed / 70 up-to-date, 22 tests with zero failures/errors/skips.
Before implementation the new tests produced missing-symbol compile-RED, not
executed assertion RED. Next failure was unavailable JDK `HttpServer` imports
under the Android test compiler; replacing only test transport with
`ServerSocket` preserved all eight store behavior assertions. Six integration
tests use the actual disposable backend and confirm returned state by reads.

Saved grocery checkpoint: `1754f191f709014c258be05bc061ab000e937b35`.
Its unsigned APK is retained outside Git at
`apps/light-phone/app/build/checkpoints/1754f191f709014c258be05bc061ab000e937b35/app-debug-unsigned.apk`.
SHA-256: `90f3a790e70f29b8db453e637c3b5cf6bd5a9a18b2252a6a24bc5d2a3ba5890f`.
SDK HEAD was exactly `251237ef3778c907c5dbd89e84685532c9d99a28`;
`git status --porcelain` in the isolated dependency returned no entries.

Additional grocery-checkpoint checks: `:app:lintDebug` succeeded (114 tasks:
52 executed, 6 from cache, 56 up-to-date); Biome checked fixture server,
provisioning checks and Nx project JSON with no fixes; the ten unchanged-backend
TypeScript protocol tests passed under the actual Node runtime. Plan and
recipe/cook test-only agents are preparing the next contracts before production
implementation. No runtime UI acceptance is inferred from static lint.

## Continuation 3: interrupted contracts reconciled

The independently verified grocery baseline is recorded in
`apps/light-phone/build/verified/forge-grocery-1754f19-verification.txt`.
Its forced 22-test execution is distinct from Nx's subsequent up-to-date
Gradle reuse; its APK and OMP's APK have different recorded checksums.

Initial current-tree `:app:testDebugUnitTest` failed compilation: extracted
fixture KDoc contained a nested `/*` and plan methods were missing. No tests
executed. After syntax repair and real plan protocol implementation:
**41 tests executed, 40 passed, 1 failed** at `planClientTest.kt:198`.
That test incorrectly toggled the already-favorite fixture plan and expected
it still favorite. Actual unchanged `plans/service.ts:410-422` flips
`!plan.isFavorite`; the fixture confirms it. The test now explicitly checks the
unfavorite transition before favoriting again and testing the name guard.
No production behavior or backend was changed to satisfy the incorrect assertion.

Separate test-only agents then added five plan-state gate/lifecycle tests and
three fixture-startup regressions. Their missing-symbol compile-RED was observed.
The shared launcher now fails promptly on child exit, reports the exit code and
`pnpm install --frozen-lockfile`, validates the loopback URL and kills rejected
children. Native plan store reads and mutations are server-confirmed, guarded
against duplicate/stopped/stale writes, status/meal-id checked and reconcile
errors by read without mutation retry.

`apps/light-phone/gradlew :app:testDebugUnitTest --console=plain`:
**BUILD SUCCESSFUL**, 62 tasks (9 executed, 53 up-to-date).
Actual XML: **49 tests**, 0 failures/errors/skips:
8 codec, 8 grocery store, 6 grocery HTTP, 7 plan HTTP, 5 plan store,
4 recipe HTTP, 8 cook-step, 3 fixture-startup. Recipe models/transport and pure
cook bounds are implemented; plan/recipe/cook native screens are not yet complete.

The first combined test/assembly attempt overlapped a test-only agent's final
file edit and failed on incomplete test syntax; it is not another executed test
result. Standalone unsigned assembly then succeeded (83 tasks: 3 executed,
80 up-to-date), with the dependency HEAD/clean guard checked immediately before
the build. A stable-file test rerun is required before the next code checkpoint.

Stable-file rerun completed after test ownership returned:
`:app:testDebugUnitTest -DlightSdk.unsigned=true :app:assembleDebug` succeeded
(90 tasks: 5 executed, 85 up-to-date). XML again contains 49 tests and zero
failures/errors/skips. Biome passed the extended fixture after whitespace-only
formatting corrections. The approved spec/SVG/PNG provenance was committed
unchanged as `f1d365628ed4b4816a2e05b02599b60c62f29ec2`.

## Native screen stage

Saved domain checkpoints: `d5953ca3ad275d516d6812cab32e87bae587ea88`
and final separate-agent plan-state tests
`6ac79599e39dee02062748cf7a6c1cde8c07b074`.

New native home choices open active/upcoming/completed/favorite plan lists,
plan detail, optional-name Save/Cancel with the 80-character/name-favorite
rules, plan activate/complete/favorite and eligible cooked toggles. Meal groups
use the existing category order; titles navigate to recipes, metadata uses the
existing limit-200 recipe query. Views refetch on returning to the SDK screen.

Recipes have favorites/history, submitted plain-text search and clear,
category select/tap-to-clear, detail metadata, grouped ingredient quantities
and units/to-taste, literal instructions, usage summary and server-confirmed
favorite changes. Search is submitted from the SDK full-screen text editor
instead of issuing debounced requests while editing. List rows open the detail
for actions rather than recreating web cards or tabs.

Cook mode is a separate SDK screen with previous/next/explicit Done/Exit,
wheel step bounds and a composition-scoped `Modifier.keepScreenOn()` only
while the pure session is visible and unfinished. Pause/hide releases its wake
intent; return preserves the step. No timers, backend sessions or background
polling. The old read-only active-plan screen was removed.

Separate test-only agents saved four recipe-store and three cook-visibility
tests before implementation. Initial UI compilation showed missing store/cook
APIs (compile-RED, zero tests). After implementation:
`:app:testDebugUnitTest -DlightSdk.unsigned=true :app:assembleDebug` succeeded
(90 tasks: 15 executed, 2 from cache, 73 up-to-date).
XML contains **56 tests, 0 failures/errors/skips**; the additional tests prove
recipe duplicate/stopped/read ownership and pure wake/wheel/visibility intent,
not an Android renderer or real hardware.

Independent read-only review and forced verification results are recorded below.
No full web-port acceptance or physical UI behavior is claimed.

## Final continuation evidence

- Native screen checkpoint: `26b00beb4a121ad4a8ba8849f1af390cc49cbd64`.
- Reviewed build-integrity fix: `9a578c43da` exports the canonical, validated
  `LIGHT_SDK_DIR` into Gradle. Regression RED was 4 pass / 1 fail: Gradle tried
  `apps/light-phone/apps/light-phone/.sdk` after validating the correct directory.
  GREEN in the real Nx test target: **5 pass / 0 fail**.
- Application candidate: `596b58daf149eb56f485ea4ef92cff964d1d88b7`.
  `usedInPlans` includes active/upcoming uncooked plan references in the real
  fixture, not completed cooking sessions; native says "Used in N plans".
- `NativeWorkflowReview`: source-only review found no correctness blocker.
  `NativeBoundaryReview`: P2 relative-path issue closed by inspection after fix.
  Neither review claims runtime/device evidence.

Final commands used the documented JDK 17 / Android SDK 36:

```sh
apps/light-phone/gradlew :app:testDebugUnitTest :app:lintDebug \
  --rerun-tasks --no-build-cache --console=plain
pnpm exec biome check apps/light-phone/contract/fixtureServer.ts \
  apps/light-phone/contract/protocol.test.ts \
  apps/light-phone/scripts/bootstrapSdkChecks.ts apps/light-phone/project.json
pnpm exec vitest run apps/light-phone/contract/protocol.test.ts
pnpm nx run light-phone:test --skip-nx-cache
pnpm nx run light-phone:build --skip-nx-cache
```

Observed:

- Forced Gradle native tests + lint: **BUILD SUCCESSFUL**, **121 / 121 tasks
  executed**. Actual XML totals: **56 tests, 0 failures, 0 errors, 0 skips**.
- Biome: **4 files passed**, no fixes. In-process protocol suite: **10 passed**.
- Nx test: prep executed, **5 provisioning/wrapper tests executed and passed**;
  native Gradle task then reused the forced results (**62 tasks: 2 executed,
  60 up-to-date**). This is not a second fresh 56-test run.
- Immediately before final assembly, isolated SDK HEAD was
  `251237ef3778c907c5dbd89e84685532c9d99a28`, with empty porcelain status.
- Nx build: prep executed; unsigned assembly **BUILD SUCCESSFUL**, **83 tasks:
  6 executed, 2 from cache, 75 up-to-date**.

APK: `apps/light-phone/app/build/outputs/apk/debug/app-debug-unsigned.apk`.
Retained candidate copy:
`apps/light-phone/app/build/checkpoints/596b58daf149eb56f485ea4ef92cff964d1d88b7/app-debug-unsigned.apk`.
SHA-256 for both:
`66589319ddf1b18ce26b7de1981006673c9e3f8400e5c0e1e856ab2c8f68125f`.
Unsigned verification artifact only; not installed, launched, signed or released.

Next concrete decision: review the compiled native screens on an explicitly
authorized runtime/device and decide whether literal markdown instructions are
an acceptable presentation adaptation or need supported native rich formatting.
Long text, full-screen keyboard entry/cancel, hardware wheel/back, lifecycle
reopening/cache and actual wake release still need that runtime evidence.
No protocol blocker or SDK-core compatibility change was required.

Post-source-commit smoke at application candidate `596b58daf149eb56f485ea4ef92cff964d1d88b7`:
`:app:testDebugUnitTest -DlightSdk.unsigned=true :app:assembleDebug` succeeded
(90 tasks: 2 executed, 88 up-to-date); APK SHA-256 remained identical.
This confirms the committed application's task graph/artifact, not another
fresh execution of the 56 tests.
