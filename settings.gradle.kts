pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (providers.gradleProperty("booxPenSdk").orNull == "true") {
            maven("https://repo.boox.com/repository/maven-public/") {
                content {
                    includeGroup("com.onyx.android.sdk")
                    includeVersion("com.tencent", "mmkv", "1.0.19")
                    includeVersion("pub.devrel", "easypermissions", "0.2.1")
                }
            }
        }
    }
}

rootProject.name = "SuperBoox"
include(":app")
