plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.middle.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.middle.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Compose BOM for consistent versions.
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Nordic BLE library.
    implementation("no.nordicsemi.android:ble:2.7.4")
    implementation("no.nordicsemi.android:ble-ktx:2.7.4")

    // OkHttp for OpenAI API calls.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing for API responses.
    implementation("org.json:json:20231013")

    // Encrypted storage for API key.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Media playback.
    implementation("androidx.media3:media3-exoplayer:1.2.1")

    // Core AndroidX.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
