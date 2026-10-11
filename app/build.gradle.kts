plugins {
    id("com.android.application")
    // AGP 9 built-in Kotlin compiles Kotlin sources; no org.jetbrains.kotlin.android needed.
    id("org.jetbrains.kotlin.plugin.compose")
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
}
