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
include(":ops:engine")
include(":ops:runtime")
include(":webview")

// Feature modules. Screens plus the logic only that screen needs, so :app stays
// assembly-only and the pure parts (the CSV codec, the sync driver) keep JVM tests.
include(":feature:lists")
include(":feature:settings")

// Temporary. Dogfoods the production modules against the live site on a real
// device -- the only way to validate the cookie bridge and the parsers outside a
// mock. Deleted when android-foundations is archived; never shipped.
include(":devharness")
