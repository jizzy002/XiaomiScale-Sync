plugins {
    id("com.android.application")
    // AGP 9 built-in Kotlin compiles Kotlin sources; no org.jetbrains.kotlin.android needed.
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xiaomiscalesync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xiaomiscalesync"
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release CI passes these from the tag / run number; local builds keep the defaults.
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "0.1.0-dev"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.compose.material3:material3")
    // Channels/suspend for the BleTransport seam (was only transitive via Compose)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Local weigh-in archive (Task 8): Room + DataStore-backed settings.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Xiaomi cloud client (Task 10): HTTP + JSON. MockWebServer below is test-only.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.google.code.gson:gson:2.14.0")

    // Garmin push queue (Task 12): one-shot, uniquely-named retry work.
    // CoroutineWorker lives in the ktx artifact.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
