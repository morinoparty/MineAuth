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
val addonName = "quickshop-hikari-addon"

dependencies {
    compileOnly(project(":api"))
    compileOnly(libs.paper.api)

    implementation(libs.bundles.commands)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bundles.coroutines)

    implementation(libs.bundles.exposed)

    implementation(libs.koin.core)
    implementation(kotlin("stdlib-jdk8"))

    compileOnly(libs.quickshop.api)
}

tasks {
    build {
        dependsOn("shadowJar")
    }
    shadowJar
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
            libraries = libs.bundles.coroutines.asString()
            depend = listOf("MineAuth", "QuickShop-Hikari")
        }
    }
}

fun Provider<MinimalExternalModuleDependency>.asString(): String {
    val dependency = this.get()
    return dependency.module.toString() + ":" + dependency.versionConstraint.toString()
}

fun Provider<ExternalModuleDependencyBundle>.asString(): List<String> {
    return this.get().map { dependency ->
        "${dependency.group}:${dependency.name}:${dependency.version}"
    }
}