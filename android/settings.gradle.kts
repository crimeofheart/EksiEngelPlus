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

rootProject.name = "EksiEngelPlus"

include(":app")

// Pure JVM. Their tests run without an emulator, which is the point of the split:
// selector and date regressions get caught in CI in seconds.
include(":core:model")
include(":eksi:parser")

// Android libraries.
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":eksi:client")
