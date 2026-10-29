plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "io.mionav.nav"
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

    sourceSets["main"].jniLibs.srcDirs("libs")
}

dependencies {
    implementation(project(":core:proto"))
    implementation(project(":core:geo"))
    implementation(project(":core:hud"))
    implementation(project(":android:link"))

    // Drop the Amap SDK into android/nav/libs (see libs/README.md). Not committed.
    // `api` so :android:app can use MapView too; the .so files go to app/src/main/jniLibs.
    api(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
