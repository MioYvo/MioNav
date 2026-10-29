plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "io.mionav.link"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
