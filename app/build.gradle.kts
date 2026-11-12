plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.handoff.hostspike"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.handoff.hostspike"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }

    // A fixed debug key, so each new build installs over the previous one instead of
    // needing an uninstall (which also wipes the log and the SMS permission).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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

// No third-party dependencies: the app uses only the Android framework and the Kotlin standard library.
