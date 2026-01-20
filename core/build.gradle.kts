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

    // Paperのlibraries機能でダウンロードさせるライブラリ（compileOnly）
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.bundles.coroutines)
    compileOnly(libs.bundles.exposed)
    compileOnly(libs.arrow.core)
    compileOnly(libs.nimbus.jose.jwt)
    compileOnly(libs.bcpkix.jdk18on)
    compileOnly(libs.java.uuid.generator)
    compileOnly(libs.hikari)
    compileOnly(kotlin("stdlib-jdk8"))

    // JARにバンドル（Paperのlibrariesでは動かない）
    // password4jはpsw4j.propertiesを読み込むためJARにバンドル
    implementation(libs.password4j)
    implementation(libs.koin.core)
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)

    compileOnly(libs.vault.api)
    compileOnly(libs.luckperms.api)

    // テスト依存関係
    testImplementation(libs.bundles.junit.jupiter)
    testImplementation(libs.bundles.koin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mock.bukkit)
    testImplementation(libs.ktor.server.test.host)
    // compileOnlyのライブラリをテストでも使えるようにする
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.coroutines)
    testImplementation(libs.bundles.exposed)
    testImplementation(libs.koin.core)
    testImplementation(libs.arrow.core)
    testImplementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.ktor.client)
    testImplementation(libs.nimbus.jose.jwt)
    testImplementation(libs.bcpkix.jdk18on)
    testImplementation(libs.java.uuid.generator)
    testImplementation(libs.hikari)
    testImplementation(kotlin("stdlib-jdk8"))
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
    shadowJar {
        // Paperのlibrariesでダウンロードするので除外
        dependencies {
            exclude(dependency("org.jetbrains.kotlin:.*:.*"))
            // kotlinx-ioはKtorが必要なので除外しない
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-.*:.*"))
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-serialization-.*:.*"))
            exclude(dependency("org.jetbrains.exposed:.*:.*"))
            exclude(dependency("io.arrow-kt:.*:.*"))
            // Ktor、Koinは除外しない（Paperのlibrariesで動かない）
            // password4jはpsw4j.propertiesを読み込むためJARにバンドル
            exclude(dependency("com.nimbusds:.*:.*"))
            exclude(dependency("org.bouncycastle:.*:.*"))
            exclude(dependency("com.fasterxml.uuid:.*:.*"))
        }
    }
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
            website = "https://github.com/morinoparty/MineAuth"
            main = "$group.mineauth.core.MineAuth"
            apiVersion = "1.20"
            libraries = buildList {
                // Paperが起動時にダウンロードするライブラリ
                // Kotlin標準ライブラリ（shadowJarで除外しているため必須）
                add("org.jetbrains.kotlin:kotlin-stdlib:${libs.plugins.kotlin.jvm.get().version}")
                addAll(libs.bundles.coroutines.asString())
                addAll(libs.bundles.exposed.asString())
                // password4jはpsw4j.propertiesを読み込むためJARにバンドル（librariesに含めない）
                add(libs.nimbus.jose.jwt.asString())
                add(libs.bcpkix.jdk18on.asString())
                add(libs.arrow.core.asString())
                add(libs.kotlinx.serialization.json.asString())
                add(libs.java.uuid.generator.asString())
                add(libs.hikari.asString())
                add(libs.mysql.connector.asString())
            }
            softDepend = listOf("Vault", "LuckPerms")
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

