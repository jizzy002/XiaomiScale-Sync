plugins {
    id("com.android.application") version "9.4.1" apply false
    // Compose compiler Gradle plugin; version must match the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // KSP (Room codegen); version is decoupled from Kotlin since KSP 2.3.
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
