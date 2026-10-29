import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Everything secret comes from `local.properties` (already ignored) or a `-P` flag. */
val localProps: Properties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        file.inputStream().use { load(it) }
    }
}

fun secret(name: String): String? =
    (project.findProperty(name) as String?) ?: localProps.getProperty(name)

/**
 * The Amap key never lives in git: pass `-Pmionav.amapKey=…`, or drop `mionav.amapKey=…` into
 * `local.properties`.
 */
val amapKey: String = secret("mionav.amapKey")?.trim().orEmpty()

/** Release signing, only when the keystore is configured; otherwise `assembleRelease` stays unsigned. */
val releaseKeystore: String? = secret("mionav.releaseStore")

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

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = secret("mionav.releaseStorePassword")
                keyAlias = secret("mionav.releaseKeyAlias") ?: "mionav"
                keyPassword = secret("mionav.releaseKeyPassword") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation(project(":core:voice"))
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
