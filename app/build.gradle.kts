plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.superboox.quickaccess"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.superboox.quickaccess"
        // Note Air (Android 10) and newer.
        minSdk = 29
        targetSdk = 34
        // CI run number, so each downloaded build installs as an upgrade over the last.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.0-build.$build"
    }

    signingConfigs {
        // Shared test key checked into the repo: every build (local or CI) has the same
        // signature, so updates install over each other without losing settings.
        // Not for Play Store releases.
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
