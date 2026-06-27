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
        // AnkiDroid API is published through JitPack
        maven { url = uri("https://jitpack.io") }
        // Fallback repository hosted in the Anki-Android repo
        maven { url = uri("https://github.com/ankidroid/Anki-Android/raw/master/api/repository") }
    }
}

rootProject.name = "MicroAnki"
include(":app")
