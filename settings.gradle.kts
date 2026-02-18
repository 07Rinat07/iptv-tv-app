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

rootProject.name = "IptvTv"

include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:domain",
    ":core:database",
    ":core:network",
    ":core:parser",
    ":core:player",
    ":core:engine",
    ":core:designsystem",
    ":core:data",
    ":sync",
    ":feature:home",
    ":feature:scanner",
    ":feature:importer",
    ":feature:playlists",
    ":feature:editor",
    ":feature:favorites",
    ":feature:history",
    ":feature:player",
    ":feature:downloads",
    ":feature:settings",
    ":feature:diagnostics"
)
