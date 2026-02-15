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
    }
}

rootProject.name = "tgweb"

include(":app")
include(":core:tdlib")
include(":core:db")
include(":core:data")
include(":core:media")
include(":core:notifications")
include(":core:sync")
include(":backend:push")
