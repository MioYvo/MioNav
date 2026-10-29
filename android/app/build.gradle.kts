plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val amapKey: String = (project.findProperty("mionav.amapKey") as String?) ?: ""

android {
    namespace = "io.mionav.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.mionav.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
        // Both the M60T and the Find X8s are arm64; 32-bit libs would only add ~50 MB.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:proto"))
    implementation(project(":core:geo"))
    implementation(project(":core:hud"))
    implementation(project(":core:tilepack"))
    implementation(project(":android:nav"))
    implementation(project(":android:link"))
    implementation(project(":android:pack"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
