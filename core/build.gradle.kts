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
    implementation(libs.bundles.opentelemetry)

    compileOnly(libs.luckperms.api)

    // テスト依存関係
    testImplementation(libs.bundles.junit.jupiter)
    testImplementation(libs.bundles.koin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mock.bukkit)
    testImplementation(libs.ktor.server.test.host)
    // OpenTelemetryのテスト用（InMemorySpanExporter / InMemoryMetricReader）
    testImplementation(libs.opentelemetry.sdk.testing)
    // Allureレポート用（JUnit5の実行結果をallure-resultsとして出力する）
    testImplementation(libs.allure.junit5)
    // compileOnlyのライブラリをテストでも使えるようにする
    testImplementation(libs.paper.api)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.coroutines)
    // コルーチンのテスト用（runTest / TestScheduler）
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bundles.exposed)
    testImplementation(libs.koin.core)
    testImplementation(libs.arrow.core)
    testImplementation(libs.bundles.ktor.server)
    testImplementation(libs.bundles.ktor.client)
    testImplementation(libs.nimbus.jose.jwt)
    testImplementation(libs.bcpkix.jdk18on)
    testImplementation(libs.java.uuid.generator)
    testImplementation(libs.hikari)
    testImplementation(libs.luckperms.api)
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

// JARに同梱する依存。これ以外のruntimeClasspathの外部依存はplugin.ymlのlibrariesでPaperに取得させる
fun isBundled(
    group: String,
    name: String,
    version: String,
): Boolean =
    // apiモジュール
    group == "party.morino" ||
        // クラスローダー競合（ClassCastException）を防ぐためrelocateするもの
        group == "io.ktor" ||
        (group == "org.jetbrains.kotlinx" && name == "kotlinx-coroutines-slf4j") ||
        // password4jはpsw4j.propertiesを読み込むためJARにバンドル
        group == "com.password4j" ||
        // cloudはMaven Centralにないスナップショット版を使う
        group == "org.incendo" ||
        group == "io.leangen.geantyref" ||
        // スナップショット版（OpenTelemetry instrumentationなど）はMaven Centralにない
        version.endsWith("-SNAPSHOT")

// runtimeClasspathのうち同梱しない外部依存（KMPは解決済みの -jvm アーティファクトになる）
val runtimeLibraries =
    configurations.runtimeClasspath.map { configuration ->
        configuration.incoming.artifacts.artifacts
            .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .filterNot { isBundled(it.group, it.module, it.version) }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
    }

tasks {
    build {
        dependsOn("shadowJar")
    }
    shadowJar {
        // Paperのlibrariesでダウンロードするので同梱しない
        dependencies {
            exclude { !isBundled(it.moduleGroup, it.moduleName, it.moduleVersion) }
        }

        // クラスローダー競合を防ぐためにrelocate
        // 他プラグインがkotlinx-coroutines-slf4jをバンドルしていても影響を受けない
        relocate("io.ktor", "party.morino.mineauth.shadow.io.ktor")
        relocate("kotlinx.coroutines.slf4j", "party.morino.mineauth.shadow.kotlinx.coroutines.slf4j")
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
        minecraftVersion("1.21.11")
        val plugins = runPaper.downloadPluginsSpec {
            // LuckPerms（ハード依存。無いとMineAuthが有効化されない）
            modrinth("luckperms", "v5.5.17-bukkit")
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


// Paperのlibrariesで取得させる依存のうち、compileOnlyで宣言しているもの
val manualLibraries =
    buildList {
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

sourceSets.main {
    resourceFactory {
        bukkitPluginYaml {
            name = rootProject.name
            version = project.version.toString()
            website = "https://github.com/morinoparty/MineAuth"
            main = "$group.mineauth.core.MineAuth"
            apiVersion = "1.20"
            libraries.set(
                runtimeLibraries.map { runtime ->
                    // 手動で指定したもの（compileOnlyの依存やMySQLドライバ）を優先し、同じモジュールは重複させない
                    (manualLibraries + runtime).distinctBy { it.substringBeforeLast(':') }
                },
            )
            // オフラインプレイヤーの権限評価に必須のため、ハード依存にする
            depend = listOf("LuckPerms")
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

