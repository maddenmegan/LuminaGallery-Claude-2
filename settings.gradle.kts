pluginManagement {
    repositories {
        // Google's mirror of Maven Central first: avoids 403 / rate-limit responses seen from CI runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "LuminaGallery"
include(":app")
