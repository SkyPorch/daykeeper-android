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
        exclusiveContent {
            forRepository {
                maven { url = uri(providers.gradleProperty("daykeeperRepository").get()) }
            }
            filter { includeGroup("io.github.skyporch") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "daykeeper-independent-consumer"
