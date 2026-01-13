plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.resource.factory)
    `maven-publish`
}

group = "party.morino"
version = project.version.toString()

dependencies {
    implementation(project(":api"))
    compileOnly(libs.paper.api)

    implementation(libs.bundles.commands)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bundles.coroutines)

    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)

    implementation(libs.bundles.securities)
    implementation(libs.java.uuid.generator)
    implementation(libs.arrow.core)

    implementation(libs.bundles.exposed)

    implementation(libs.koin.core)
    implementation(kotlin("stdlib-jdk8"))

    compileOnly(libs.vault.api)
    compileOnly(libs.quickshop.api)

    // テスト依存関係
    testImplementation(libs.bundles.junit.jupiter)
    testImplementation(libs.bundles.koin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mock.bukkit)
    testImplementation(libs.ktor.server.test.host)
}


publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = group.toString()
            artifactId = "mineauth-core"
            version = version
            from(components["kotlin"])
        }
    }
}

tasks {
    build {
        dependsOn("shadowJar")
    }
    shadowJar
    test {
        useJUnitPlatform()
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
            name = rootProject.name
            version = project.version.toString()
            website = "https://github.com/morinoparty/Moripa-API"
            main = "$group.mineauth.core.MineAuth"
            apiVersion = "1.20"
            libraries = libs.bundles.coroutines.asString()
            softDepend = listOf("Vault", "QuickShop-Hikari")
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

