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
- [ ] Continue all plan, recipe, cook-mode, rename/favorite, grocery-entry/removal, navigation, lifecycle and cache workflows from the approved scope, test-first.
- [ ] Perform independent read-only review, static checks, APK build, artifact checksum and device-independent handoff. No install/release authorization.

## Protocol evidence

Command: `pnpm exec vitest run apps/light-phone/contract/protocol.test.ts`.
Result: **10 passed**, 1 file, exit 0. Actual backend adapter and SQLite migrations, not mocked service responses. Tests run in-process through `Hono.request`; this is not TCP, Android transport or production-endpoint proof.

Committed as `5602bfd97a9bd5ef933a3b88891838fd832e65b2` (`test(light-phone): verify disposable grocery wire contracts`), 1 file, 204 insertions. Post-commit `pnpm exec biome check apps/light-phone/contract/protocol.test.ts && pnpm exec vitest run apps/light-phone/contract/protocol.test.ts` passed: 1 formatted/linted file and 10 tests. An initial formatting-only check failed before the formatting correction; no lint rule was disabled.

Covered: plain query input and `result.data` response; grocery IDs/booleans/formatted quantities; absent plan versus empty grocery list; no-input active-plan query and ISO dates; check/recheck/uncheck with subsequent reads and other-plan isolation; manual-item trimming/defaults/explicit fields and removal with readback; generated-item removal refusal; absent-item removal no-op; validation and missing-target errors.

SDK baseline: in an isolated clean clone at the pinned revision, `JAVA_HOME=/home/gav/.local/share/light-tern-toolchain/jdk17 ANDROID_HOME=/home/gav/.local/share/light-tern-toolchain/android-sdk ./gradlew -DlightSdk.toolOnly=true :sdk:client:assembleDebug --console=plain` succeeded in 27 seconds (40 tasks executed). Temurin 17.0.21+9 and Android SDK 36 can build the pinned SDK client. Existing publication/deprecation warnings remain; this is not yet proof of the independent MealForge app.

Native tests target the first protocol increment, not full acceptance. They call the intended `TrpcProtocol` API directly. The SDK scans all Kotlin under `src`, including tests, and prohibits reflection; no reflective bridge or SDK-policy bypass is used. With the production codec absent, compilation is expected to fail before test execution. Such a result is compilation RED, **not** the required behavior-level RED, and must not be counted as executed native tests.

## Remaining proof

The grocery tracer and active-plan read screen now compile and build. Full plan/recipe/cook counterparts and runtime UI acceptance remain unfinished. Twenty-two native tests cover the codec, grocery store races and real HTTP writes/readback; they do not establish keyboard/wheel/back, persistence or visual/device acceptance. No installation, production mutation, SDK-core edit, API/web edit, signing/release, push or deployment is included.

Build/test results and local commit identifiers are recorded in the final handoff and build documentation after execution.

## Acceptance ledger

| Spec acceptance item | Current evidence / remaining work |
| --- | --- |
| All native web counterparts | Partial: native setup/trust, active-plan read and grocery read/check/add/remove screens. Full plans, recipes and cook workflows unfinished. |
| Contract coverage | 10 unchanged-server checks; 8 native codec + 8 store + 6 real HTTP/backend tests. Plan/recipe/ingredient/status/rename/favorite contracts remain. |
| Unit/UI safeguards | Grocery duplicate-tap/stale-response/cancellation tested. Runtime wheel/back/input, cook/wake, long-text, cache/persistence acceptance remains unfinished. |
| Designated-instance integration | Native Ktor → loopback HTTP → unchanged Hono/tRPC → migrated in-memory SQLite, with mutation readback. No Android network/device test. |
| Independent review, static checks and app build | Tracer unit tests/APK build and real Nx prep/test/build passed. Independent feature review/static/runtime acceptance remains. |
| Candidate debug APK and checksum | Unsigned tracer APK produced; final candidate checksum to follow. Not a device-tested/full-parity artifact. |

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
