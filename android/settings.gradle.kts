pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RFMapper"

// Pure-JVM core. These carry the correctness-critical logic and are testable without an SDK.
include(":core-model")
include(":core-export")
include(":core-import")
include(":core-radio")

// Android libraries.
include(":radio-android")
include(":data-room")

// Applications.
include(":collector-app")
include(":master-app")
