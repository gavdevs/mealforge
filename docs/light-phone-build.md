# Light Phone build (apps/light-phone)

Experimental, owner-scoped Android/Kotlin client for the MealForge tRPC API.
This document records the pinned Gradle/Nx lane and tested native increments.
Native plan, recipe, grocery and cook counterparts are implemented and compile.
This is not full web-port acceptance: Android runtime/device, text entry,
wheel/back, visual and actual wake behavior remain unverified.

## Lane: experimental/local, not distribution-compatible

Light's builder stages a single tool directory into a *pinned official* SDK
workspace and builds `:tool:assembleRelease` there. This project instead:

- maps `:sdk:shared`, `:sdk:ui`, `:sdk:client` and `:lint-rules` onto an
  independent clone of the **owner fork** `https://github.com/gavdevs/light-sdk`;
- includes that clone's `plugin` build for the `com.thelightphone.light-sdk`
  Gradle plugin;
- builds the app module as `:app`, not `:tool`.

A release build of this module is therefore not evidence of Light-builder
compatibility, and the APK is not Light-signed. Treat all artifacts as local
debug builds. Distribution would need the tool directory to be re-based on the
official SDK repo, which is out of scope here.

## SDK pin

| Item | Value |
| --- | --- |
| Repository | `https://github.com/gavdevs/light-sdk` (owner fork) |
| Commit | `251237ef3778c907c5dbd89e84685532c9d99a28` |
| Checkout used by Gradle | `apps/light-phone/.sdk` (git-ignored) |
| SDK version (`gradle.properties`) | `0.2.3` |
| Gradle | `9.0.0`, via the pinned SDK's own wrapper |
| AGP / Kotlin / KSP | `8.12.3` / `2.3.20` / `2.3.6` |
| compileSdk / minSdk / targetSdk | `36` / `34` / `34` |
| JVM target | `17` |

The test-only uncommitted text-input work in any live SDK working tree is
deliberately not used: the build always reads the pinned commit.

`scripts/bootstrap-sdk.sh` clones (or reuses) the checkout, fetches the exact
commit, checks it out detached and verifies `HEAD`. Override the location with
`LIGHT_SDK_DIR=/path/to/clean/clone`; nothing else in the build hard-codes a
machine path.

The Gradle wrapper is the pinned SDK's (`apps/light-phone/gradlew` execs
`$LIGHT_SDK_DIR/gradlew -p apps/light-phone`), so the Gradle distribution
version stays identical to the SDK's without copying wrapper binaries.

## How the build resolves the SDK

- `settings.gradle.kts` declares the single `sdkDir`, checks the checkout is
  present, imports the SDK's version catalog (`gradle/libs.versions.toml`) as
  `libs`, includes the SDK's `plugin` build for plugin resolution, and maps the
  four SDK projects by `projectDir`.
- `build.gradle.kts` publishes the values those modules read from
  `rootProject.ext` (`compileSdk`, `minSdk`, `targetSdk`, `jvmTarget`,
  `lintVersion`) and the SDK's `sdkGroup`/`sdkVersion` properties, loaded from
  the pinned `gradle.properties`.
- No dependency substitution, no published SDK artifacts, no SDK edits. The
  live `~/Projects/light-sdk` checkout is never referenced.

## Module for this session

`app/` applies `com.thelightphone.light-sdk` plus the allowlisted Android,
Kotlin, KSP, serialization and Compose Kotlin plugins, depends on `:sdk:client`,
and declares only allowlisted dependencies:

- `org.jetbrains.kotlinx:kotlinx-serialization-json` (tRPC JSON payloads);
- `io.ktor:ktor-client-{core,okhttp,content-negotiation}` and
  `io.ktor:ktor-serialization-kotlinx-json` (HTTP transport);
- `kotlin("test-junit")` for tests, which pulls JUnit 4 in.

`junit:junit` cannot be declared directly: the SDK plugin's allowlist is applied
to test configurations as well, and the only JUnit entry is the transitive of
`org.jetbrains.kotlin:kotlin-test`. `kotlin-test-junit` matches the allowlist
entry `org.jetbrains.kotlin:kotlin-test` and gives JUnit 4 semantics (the
default runner for `testDebugUnitTest`).

`app/lighttool.toml` carries the tool identity the plugin requires
(`com.mealforge.lightphone`, versionCode 1, INTERNET and
ACCESS_NETWORK_STATE, `serverPackage = "com.lightos"`). The plugin generates the
manifest from it; there is no `AndroidManifest.xml` and no signing config in
this project — debug builds for verification use `-DlightSdk.unsigned=true`.

## TypeScript generator exemption

This app is not a TypeScript feature and must not go through
`nx generate @mealforge/project-plugin:feature`. The project is registered by a
hand-written `project.json` with plain `nx:run-commands` wrappers; the Android
code is unaffected by pnpm/Nx/Biome targets. Biome must not be pointed at the
Kotlin/Gradle tree, and `apps/*` workspace membership is not evidence of Gradle
or Nx autodiscovery — the three targets below are the actual surface.

## Bootstrap and verification commands

Host toolchain used below: JDK 17 at
`/home/gav/.local/share/light-tern-toolchain/jdk17`, Android SDK at
`/home/gav/.local/share/light-tern-toolchain/android-sdk` (platform 36,
build-tools present). Both the independent app's protocol tests and unsigned
debug APK build have now succeeded with that toolchain.

Fresh checkout prerequisite: from the repository root run
`pnpm install --frozen-lockfile`. Native integration tests launch
`node --import tsx` from `apps/api`; Gradle alone does not install these locked
workspace dependencies. The shared fixture launcher reports an early child exit
with its exit code, captured output and this prerequisite instead of waiting
60 seconds for a URL.

When using an external clean pinned checkout, export both
`LIGHT_SDK_DIR=/path/to/checkout` and `LIGHT_SDK_FIXTURE_REPO=/path/to/checkout`
for the Nx provisioning tests. Without the fixture override, those tests use
the app-local `.sdk` as their local clone source. Pin and dirty-input guards
are unchanged.

```sh
cd apps/light-phone

# 1. Provision the pinned SDK checkout (idempotent)
scripts/bootstrap-sdk.sh

# 2. Build the unsigned debug APK
JAVA_HOME=/home/gav/.local/share/light-tern-toolchain/jdk17 \
ANDROID_HOME=/home/gav/.local/share/light-tern-toolchain/android-sdk \
./gradlew -DlightSdk.unsigned=true :app:assembleDebug --console=plain

# 3. Unit tests (JUnit 4)
JAVA_HOME=/home/gav/.local/share/light-tern-toolchain/jdk17 \
ANDROID_HOME=/home/gav/.local/share/light-tern-toolchain/android-sdk \
./gradlew :app:testDebugUnitTest --console=plain
```

Equivalent Nx wrappers (each has `cache: false` and `cwd: apps/light-phone`):

```sh
pnpm nx run light-phone:prep
pnpm nx run light-phone:build
pnpm nx run light-phone:test
```

Verified artifact: `apps/light-phone/app/build/outputs/apk/debug/app-debug-unsigned.apk`.

## Current test state

The prior-run protocol contracts initially failed at `compileDebugUnitTestKotlin`
with four unresolved `TrpcProtocol` references (zero tests executed). The real
codec now implements URL-encoded single queries, preserves `result.data` including
null, and rejects server errors or missing result data. All eight protocol tests
passed, followed by `:app:assembleDebug` (unsigned).

## Integration risks to watch on first execution

1. **SDK plugin validation scans all of `app/src`, tests included.** Any blocked
   import/pattern (`android.content.Intent`, `kotlin.reflect.`, `Class.forName`,
   `javaClass`, `startActivity`, …) in test sources fails configuration. Tests
   must stay plain JUnit 4 + serialization JSON.
2. **Allowlist is closed.** New runtime dependencies (e.g. a Ktor engine other
   than OkHttp, a JSON library other than kotlinx, JUnit declared directly) fail
   configuration. Use allowlisted coordinates or request an SDK change upstream.
3. **Plugin-generated manifest, identity and versions.** `applicationId`,
   `versionCode`, `versionName` and `namespace` must not appear in
   `app/build.gradle.kts`; they come from `lighttool.toml`. Bumping the version
   is release data, not scaffold work.
4. **Missing `local.properties`/`ANDROID_HOME`.** AGP needs the platform SDK
   location; export `ANDROID_HOME` as above rather than committing a path.
5. **SDK integrity.** The app wrapper runs the provisioning/integrity guard
   before invoking any SDK wrapper. Modified checkouts are rejected before a
   revision change; exact-pin validation is not limited to Nx prep.
6. **Gradle daemon reuse across SDK versions.** Different SDK pins share
   `~/.gradle`; expect first-run downloads of Gradle 9.0.0 dependencies and
   KSP/AGP artifacts.
7. **`-DlightSdk.unsigned=true` clears the debug signing config.** The output is
   an unsigned APK; it cannot be installed or launched as-is. Device install is
   explicitly out of scope for this lane.

## Deliberately not done

- Rich markdown instruction formatting and runtime/device acceptance remain unfinished.
- No `AndroidManifest.xml`, keystore or release signing.
- No production server mutation, device install, push or release.
- No changes to `apps/api`, `apps/web`, shared root config, or the SDK.

## Continuation 2: provisioning and protocol checkpoint

`node --test apps/light-phone/scripts/bootstrapSdkChecks.ts` first produced
3 failures / 1 pass: fresh and pre-existing no-checkout clones were rejected,
and an edited wrong-revision clone had its HEAD moved before rejection. The fix
hydrates only a checkout with no Git index and no working files; all other dirty
inputs are rejected before checkout. After populating the default partial clone,
all **4 checks passed**. An intermediate local-clone fixture attempt failed on
missing promisor blobs; this was a fixture-source prerequisite, not a passing run.

The first independent Gradle invocation exposed a scaffold configuration error:
the SDK catalog must use `from(files(...))`, not a bare `File`. After correction:

```sh
export JAVA_HOME=/home/gav/.local/share/light-tern-toolchain/jdk17
export ANDROID_HOME=/home/gav/.local/share/light-tern-toolchain/android-sdk
apps/light-phone/gradlew :app:testDebugUnitTest \
  --tests com.mealforge.lightphone.TrpcProtocolTest \
  -DlightSdk.unsigned=true :app:assembleDebug --console=plain
```

Result: **BUILD SUCCESSFUL**, 90 tasks (41 executed, 49 up-to-date); eight native
protocol tests. `pnpm exec biome check apps/light-phone/scripts/bootstrapSdkChecks.ts
apps/light-phone/project.json` also passed. This checkpoint is codec + scaffold,
not grocery UI or full parity. No SDK edits, signing, installation or production
requests were used.

## Continuation 2: native grocery tracer

Separate test-only agents saved eight store behavior tests and six Kotlin
HTTP integration tests before implementation. The initial native result was
compile-RED (missing client/store symbols), not an executed assertion failure.
The next build exposed `com.sun.net.httpserver` unavailable on Android's test
compilation classpath. The test-only helper now uses standard `ServerSocket`;
the behavior assertions were not changed or skipped.

The real Ktor/OkHttp client now reads `plans.active` and grocery rows, performs
absolute check/uncheck, adds optional quantity/section, and removes manual rows.
The store waits for server-confirmed state, drops duplicate writes, rejects
generated/checked-manual removal, discards stale results and cancels on stop.
Failed writes reconcile with a read instead of automatically retrying a POST.
Six integration tests launch the unchanged Hono/tRPC app over loopback HTTP
with migrated in-memory SQLite and confirm mutations using subsequent reads.

The SDK entry point now has explicit server trust/setup, native SDK navigation
and text entry, active-plan read, sectioned groceries, manual-item form,
bounded stale-labelled last-read cache, lifecycle cancellation and wheel scroll.
`cleartext-http` is declared for explicitly configured local HTTP servers.
These screens compiled; no phone/emulator was installed or visually exercised.

```sh
apps/light-phone/gradlew :app:testDebugUnitTest \
  -DlightSdk.unsigned=true :app:assembleDebug --console=plain
```

With the documented JDK/SDK: **BUILD SUCCESSFUL**, 90 tasks (20 executed,
70 up-to-date), **22 tests**: 8 codec + 8 store + 6 real HTTP/backend integration;
zero failures/errors/skips. This is a tested tracer, not complete acceptance.

## Native workflow candidate and fresh verification

Current screens use SDK navigation and full-screen text entry, plain lists and
separators. Recipe instruction source is shown literally (HTML remains inert),
not as rich markdown. Search is submitted from the editor; actions live in the
detail screens rather than web cards/tabs. These are documented presentation
differences, not evidence of device acceptance.

`recipes.byId.usedInPlans` is plan usage, not completed cooking history. The
disposable fixture returns active/upcoming references with uncooked meals.
The web labels that count "cooked"; native labels it "Used in N plans" rather
than inventing cooking-session data. No backend change was made.

The native suite now contains 56 cases: 8 codec, 8 grocery state, 6 grocery HTTP,
7 plan HTTP, 5 plan state, 4 recipe HTTP, 4 recipe state, 8 cook-step, 3 cook
visibility/wheel intent and 3 fixture startup. JVM intent tests do not verify a
renderer, actual rotary/back/text input, storage lifecycle or physical wake.

For uncached native execution:

```sh
# First install locked workspace dependencies and export the JDK/Android SDK above.
apps/light-phone/gradlew :app:testDebugUnitTest :app:lintDebug \
  --rerun-tasks --no-build-cache --console=plain
pnpm exec vitest run apps/light-phone/contract/protocol.test.ts
pnpm nx run light-phone:test --skip-nx-cache
pnpm nx run light-phone:build --skip-nx-cache
```

Nx `test` executes SDK prep, five provisioning/wrapper checks (the fifth invokes
real Gradle `help` with a relative SDK override), then the native task. An
up-to-date native task in Nx is not another forced test execution. The app
wrapper exports the canonical absolute checkout validated by prep so Gradle
settings cannot resolve a relative override to a different checkout.

Final application candidate: `596b58daf149eb56f485ea4ef92cff964d1d88b7`.
Forced native test/lint execution succeeded: 121 tasks executed, XML **56 tests,
zero failures/errors/skips**. The existing ten in-process protocol tests and
four-file Biome check passed. Nx test executed all five provisioning/wrapper
checks, then reused the forced native results (2 tasks executed / 60 up-to-date).
Nx unsigned build succeeded (6 executed / 2 from cache / 75 up-to-date).
SDK HEAD/clean status was checked immediately before assembly at the exact pin.

APK: `apps/light-phone/app/build/outputs/apk/debug/app-debug-unsigned.apk`.
Retained copy:
`apps/light-phone/app/build/checkpoints/596b58daf149eb56f485ea4ef92cff964d1d88b7/app-debug-unsigned.apk`.
SHA-256: `66589319ddf1b18ce26b7de1981006673c9e3f8400e5c0e1e856ab2c8f68125f`.
Source-only independent review found no remaining blocker after the relative
SDK-directory fix. No physical/device/renderer, signing or release claim.
