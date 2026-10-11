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
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.compose.material3:material3")
    // Channels/suspend for the BleTransport seam (was only transitive via Compose)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Local weigh-in archive (Task 8): Room + DataStore-backed settings.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("com.google.code.gson:gson:2.11.0") // read committed crypto test vectors
}
