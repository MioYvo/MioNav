plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.mionav.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.mionav.watch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    implementation(project(":core:hud"))
    implementation(project(":core:geo"))
    implementation(project(":android:pack"))
    implementation(project(":core:tilepack"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
