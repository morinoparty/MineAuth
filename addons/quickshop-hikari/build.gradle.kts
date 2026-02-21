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
val addonName = "addon-quickshop-hikari"

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

    compileOnly(libs.quickshop.api)
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
        val plugins = runPaper.downloadPluginsSpec {
            //Vault
            url("https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar")
            //EssestialsX
            url("https://ci.ender.zone/job/EssentialsX/1576/artifact/jars/EssentialsX-2.21.0-dev+93-3a6fdd9.jar")
            //QuickShop
            url("https://cdn.modrinth.com/data/ijC5dDkD/versions/yr8al7fH/QuickShop-Hikari-6.2.0.6.jar")
        }
        downloadPlugins {
            downloadPlugins.from(plugins)
        }
    }
}


sourceSets.main {
    resourceFactory {
        bukkitPluginYaml {
            name = rootProject.name + "-" + addonName // "mineauth-api-quickshop-hikari-addon"
            version = project.version.toString()
            website = "https://github.com/morinoparty/MineAuth"
            main = "$group.mineauth.addons.quickshop.QuickShopHikariAddon"
            apiVersion = "1.20"
            // librariesはMineAuthコアから提供されるため宣言しない
            // （別クラスローダーでの重複ロードによるLinkageError防止）
            depend = listOf("MineAuth", "QuickShop-Hikari")
        }
    }
}
