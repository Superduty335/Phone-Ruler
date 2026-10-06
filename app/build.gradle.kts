plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.siteruler.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.siteruler.app"
        minSdk = 24 // ARCore minimum
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // A fixed debug key, so each new build installs as an update over the last one
    // instead of failing with "App not installed". It is only a debug key; use a
    // private release key before publishing to the Play Store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("siteruler-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("com.google.ar:core:1.45.0")
    implementation("androidx.core:core:1.13.1")
}
