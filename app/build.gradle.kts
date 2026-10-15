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
        // CI sets SB_VERSION: "1.0.0" for a stable release (a v1.0.0 tag), "1.1.0-dev.N" for
        // default-branch pre-releases (matching the release tag, for Obtainium's version check),
        // "1.1.0-branch.N" for unpublished branch/PR test builds.
        // Adb test hooks (TestHooks.kt) are compiled in only when CI marks a build as an unpublished
        // branch/PR test build (SB_TEST_HOOKS=1), so published releases never carry them.
        buildConfigField("boolean", "TEST_HOOKS", (System.getenv("SB_TEST_HOOKS") == "1").toString())
        versionName = System.getenv("SB_VERSION") ?: "0.0.0-local.$build"
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

    buildFeatures {
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
    testImplementation("junit:junit:4.13.2")
    // Shizuku: optional on-device ADB (Settings → Advanced access).
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    // Lets the Clipboard reach Android's clipboard service as the shell (through Shizuku).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
