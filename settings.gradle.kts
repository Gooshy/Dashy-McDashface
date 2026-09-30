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

rootProject.name = "Dashy-McDashface"
// core: the read-only car-bus client and the channel decode table
//       (a copy of OmodaBoard's :core; identified channels are added there first).
// dash: Omoda Dash, the driver's dashboard.
include(":core", ":dash")
