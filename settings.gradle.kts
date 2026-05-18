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
        // Uncomment when re-enabling GeckoView:
        // maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "DevBrowser"
include(":app")
