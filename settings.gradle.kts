rootProject.name = "MioNav"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// Pure-JVM modules build anywhere. Android modules need SDK 35 + AGP.
include(":core:proto", ":core:geo", ":core:hud", ":core:voice", ":core:tilepack")

val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()

if (hasAndroidSdk) {
    include(":android:app", ":android:watch", ":android:nav", ":android:link", ":android:pack")
} else {
    logger.lifecycle("MioNav: no Android SDK found, skipping :android:* modules")
}
