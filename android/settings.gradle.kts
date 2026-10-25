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

// CodeQL's default setup restores ~/.gradle between runs, the build cache with it: Kotlin compiles then came FROM-CACHE,
// the extractor saw no compilation and the java-kotlin analysis failed ("could not automatically build any of it").
// GitHub's runners set CI, so they build without the local cache; this Mac keeps it for the gate.
buildCache {
    local {
        isEnabled = System.getenv("CI") == null
    }
}

rootProject.name = "Skipti"
include(":app")
