// Independent Gradle build for the MealForge Light Phone client.
//
// It consumes the owner's Light SDK fork at one pinned commit, provisioned into
// the git-ignored .sdk directory by scripts/bootstrap-sdk.sh. SDK modules are
// mapped by project path, so nothing here reads the live SDK working tree and
// no SDK file is modified. Set LIGHT_SDK_DIR to reuse another clean checkout.

pluginManagement {
    // The pinned SDK's `plugin` build provides com.thelightphone.light-sdk.
    // Gradle requires this block first in the settings file, so the checkout
    // location is resolved here as well as below.
    val sdkDir: File = providers.environmentVariable("LIGHT_SDK_DIR").orNull
        ?.let(::file)
        ?: file(".sdk")
    check(sdkDir.resolve("plugin/build.gradle.kts").isFile) {
        "Pinned Light SDK checkout missing at $sdkDir. Run apps/light-phone/scripts/bootstrap-sdk.sh" +
            " or set LIGHT_SDK_DIR to an existing clean checkout of the pinned commit."
    }
    includeBuild(sdkDir.resolve("plugin"))

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Single source of truth for the SDK checkout used by this build.
val sdkDir: File = providers.environmentVariable("LIGHT_SDK_DIR").orNull
    ?.let { file(it) }
    ?: file(".sdk")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
    versionCatalogs {
        // Reuse the pinned SDK's catalog verbatim: plugin, Kotlin, Compose,
        // Ktor and serialization versions must match the SDK being built.
        create("libs") {
            from(files(sdkDir.resolve("gradle/libs.versions.toml")))
        }
    }
}

check(sdkDir.resolve("sdk/client/build.gradle.kts").isFile) {
    "Pinned Light SDK checkout missing at $sdkDir. Run apps/light-phone/scripts/bootstrap-sdk.sh" +
        " or set LIGHT_SDK_DIR to an existing clean checkout of the pinned commit."
}

rootProject.name = "mealforge-light-phone"

include(":app")
include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")

project(":lint-rules").projectDir = sdkDir.resolve("lint-rules")
project(":sdk").projectDir = sdkDir.resolve("sdk")
listOf("shared", "ui", "client").forEach { module ->
    project(":sdk:$module").projectDir = sdkDir.resolve("sdk/$module")
}
