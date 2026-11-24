// Root of the MealForge Light Phone build. The SDK modules mapped in
// settings.gradle.kts read these values from the root project, so they are the
// single source of truth for the Android/JVM levels this app compiles against.

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

group = "com.mealforge"

// Values mirror the pinned SDK's own root build; the mapped SDK modules read
// them from rootProject.ext["..."].
ext["compileSdk"] = 36
ext["minSdk"] = 34
ext["targetSdk"] = 34
ext["jvmTarget"] = "17"
ext["lintVersion"] = "31.12.3"

// The mapped SDK modules publish under property("sdkGroup")/("sdkVersion");
// take the authoritative values from the pinned SDK's gradle.properties.
val sdkDir: File = providers.environmentVariable("LIGHT_SDK_DIR").orNull
    ?.let(::file)
    ?: rootProject.file(".sdk")

val sdkProperties = java.util.Properties().apply {
    sdkDir.resolve("gradle.properties").inputStream().use { load(it) }
}

allprojects {
    extra["sdkGroup"] = sdkProperties.getProperty("sdkGroup")
    extra["sdkVersion"] = sdkProperties.getProperty("sdkVersion")
}
