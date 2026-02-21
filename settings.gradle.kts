rootProject.name = "MineAuth"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://papermc.io/repo/repository/maven-public/")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

buildCache {
    local {
        isEnabled = true
        directory = file("$rootDir/.gradle/build-cache")
    }
}
include("core")
include("api")
include("addons:quickshop-hikari")
include("addons:vault")
include("addons:betonquest")
include("addons:griefprevention")
