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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Sky Player"
include(":app")
include(":xffmpeg")

// 仅在项目源码依赖模式下 include skymediaplayer 模块
val skyDependencyMode: String by settings
if (skyDependencyMode == "project") {
    include(":skymediaplayer")
}
