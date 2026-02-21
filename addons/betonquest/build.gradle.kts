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
val addonName = "addon-betonquest"

repositories {
    maven("https://repo.betonquest.org/betonquest/")
    maven("https://repo.minebench.de/")  // MineDown依存関係用
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.paper.api)

    implementation(libs.bundles.commands)

    // Paperのlibraries機能またはMineAuthコアから提供されるライブラリ（compileOnly）
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.bundles.coroutines)
    compileOnly(libs.bundles.exposed)
    compileOnly(kotlin("stdlib-jdk8"))

    compileOnly(libs.betonquest)
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
        minecraftVersion("1.21.4")
        val plugins = runPaper.downloadPluginsSpec {

            url("https://repo.betonquest.org/betonquest/org/betonquest/betonquest/3.0.0-SNAPSHOT/betonquest-3.0.0-20250818.215240-364-shaded.jar")
        }
        downloadPlugins {
            downloadPlugins.from(plugins)
        }
    }
}


sourceSets.main {
    resourceFactory {
        bukkitPluginYaml {
            name = rootProject.name + "-" + addonName
            version = project.version.toString()
            website = "https://github.com/morinoparty/MineAuth"
            main = "$group.mineauth.addons.betonquest.BetonQuestAddon"
            apiVersion = "1.20"
            // librariesはMineAuthコアから提供されるため宣言しない
            // （別クラスローダーでの重複ロードによるLinkageError防止）
            depend = listOf("MineAuth", "BetonQuest")
        }
    }
}
