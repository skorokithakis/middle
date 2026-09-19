import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.middle.app"
    // The haversine AAR declares minCompileSdk 36, so the app has to compile
    // against API 36 even though it still targets 35 at runtime.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.middle.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // This debug keystore is committed on purpose. The app is open source and
        // distributed as a debug APK, and a stable signing key is what lets a newer
        // build install over an older one. The tradeoff is that anyone can build an
        // APK that Android treats as an update to this app; that was accepted
        // deliberately. Do not reuse this key for a release build.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Action.fromJson logs and skips entries it cannot understand; the JVM
        // test run needs android.util.Log to return a default instead of
        // throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Compose BOM for consistent versions.
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Nordic BLE library.
    implementation("no.nordicsemi.android:ble:2.7.4")
    implementation("no.nordicsemi.android:ble-ktx:2.7.4")

    // Vendor library that speaks the Pebble Index 01 ring's closed BLE protocol.
    implementation("io.github.coredevices.haversine:haversine-android:2263387")

    // OkHttp for OpenAI API calls.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing for API responses.
    implementation("org.json:json:20231013")

    // Encrypted storage for API key.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Material Design (for Material3 theme in XML).
    implementation("com.google.android.material:material:1.12.0")

    // Media playback.
    implementation("androidx.media3:media3-exoplayer:1.2.1")

    // Core AndroidX.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Unit tests for the pure pipeline policy.
    testImplementation("junit:junit:4.13.2")
}
