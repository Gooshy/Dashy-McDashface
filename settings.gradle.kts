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
//       (a copy of the OmodaBoard recorder's :core, where channels are identified).
// dash: Dashy McDashface, the driver's dashboard.
include(":core", ":dash")
