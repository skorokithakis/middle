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
        // Silero VAD is only published on JitPack; the owner accepted this.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Middle"
include(":app")
