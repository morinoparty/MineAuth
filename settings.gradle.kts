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

// ビルドキャッシュは既定の場所（Gradle User Home）に置く。
// プロジェクト内に置くと CI の setup-gradle がキャッシュとして保存せず、毎回すべてをコンパイルし直すことになる
buildCache {
    local {
        isEnabled = true
    }
}
include("core")
include("api")
include("addons:quickshop-hikari")
include("addons:vault")
include("addons:betonquest")
include("addons:griefprevention")
include("addons:pure-tickets")
include("addons:voting-plugin")
