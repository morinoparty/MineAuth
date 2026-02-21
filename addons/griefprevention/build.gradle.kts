plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.resource.factory)
}

group = "party.morino"
version = project.version.toString()
val addonName = "addon-griefprevention"

dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.paper.api)

    implementation(libs.bundles.commands)

    implementation(libs.koin.core)

    // Paperのlibraries機能またはMineAuthコアから提供されるライブラリ（compileOnly）
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.bundles.coroutines)
    compileOnly(libs.bundles.exposed)
    compileOnly(kotlin("stdlib-jdk8"))

    compileOnly(libs.griefprevention)
    compileOnly(libs.vault.api)
}

tasks {
    build {
        dependsOn("shadowJar")
    }
    shadowJar {
        // MineAuthコアがPaperのlibrariesで提供するので除外
        dependencies {
            exclude(dependency("org.jetbrains.kotlin:.*:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-.*:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-.*:.*"))
            exclude(dependency("org.jetbrains.exposed:.*:.*"))
            exclude(dependency("io.arrow-kt:.*:.*"))
        }
    }
    runServer {
        minecraftVersion("1.21.8")
    }
}


sourceSets.main {
    resourceFactory {
        bukkitPluginYaml {
            name = rootProject.name + "-" + addonName
            version = project.version.toString()
            website = "https://github.com/morinoparty/MineAuth"
            main = "$group.mineauth.addons.griefprevention.GriefPreventionAddon"
            apiVersion = "1.20"
            // librariesはMineAuthコアから提供されるため宣言しない
            // （別クラスローダーでの重複ロードによるLinkageError防止）
            depend = listOf("MineAuth", "GriefPrevention", "Vault")
        }
    }
}
