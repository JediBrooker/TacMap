// Top-level Gradle file.
//
// Note: with Kotlin 1.9.x, the Compose compiler is enabled via
// `composeOptions.kotlinCompilerExtensionVersion` in app/build.gradle.kts.
// (The `org.jetbrains.kotlin.plugin.compose` Gradle plugin only exists for
// Kotlin 2.0+, so it is intentionally not declared here.)
plugins {
    id("com.android.application")        version "8.5.0" apply false
    id("org.jetbrains.kotlin.android")    version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
