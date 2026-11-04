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
        // The wireless ADB installer is published on JitPack only. Nothing else is taken from here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MuntashirAkon"); includeGroup("com.github.MuntashirAkon.spake2-java") }
        }
    }
}

rootProject.name = "ObsiBible"
include(":app")
