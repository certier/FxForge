pluginManagement {
    repositories {
        google()
        // Google-hosted mirror avoids Maven Central rate limits on CI runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Keep a mirror ahead of Maven Central as a resilient fallback.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "FxForge"
include(":app", ":core:shizuku")
