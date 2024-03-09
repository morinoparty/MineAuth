import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("xyz.jpenilla.run-paper") version "2.2.3"
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
    kotlin("plugin.serialization") version "1.9.22"
}

group = "party.morino"
version = "1.0-SNAPSHOT"

fun captureVersion(dependency: Dependency): String {
    return dependency.version ?: throw IllegalArgumentException("Version not found for $dependency")
}


repositories {
    mavenCentral()
    maven("https://papermc.io/repo/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://jitpack.io")
    maven("https://plugins.gradle.org/m2/")
    maven("https://repo.incendo.org/content/repositories/snapshots")
    maven("https://repo.codemc.io/repository/maven-public/")
}


dependencies {
    val paperVersion = "1.20.4-R0.1-SNAPSHOT"
    val mccoroutineVersion = "2.14.0"
    val lampVersion = "3.1.9"
    val koinVersion = "3.5.3"
    val coroutineVersion = "1.7.3"
    val serializationVersion = "1.6.2"
    val junitVersion = "5.10.2"
    val mockkVersion = "1.13.9"
    val ktorVersion = "2.3.8"
    val mockBukkitVersion = "3.76.1"

    compileOnly("io.papermc.paper:paper-api:$paperVersion")

    library(kotlin("stdlib"))

    implementation("com.github.Revxrsal.Lamp:common:$lampVersion")
    implementation("com.github.Revxrsal.Lamp:bukkit:$lampVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutineVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-api:$mccoroutineVersion")
    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-core:$mccoroutineVersion")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-network-tls-certificates:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-java:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    implementation("com.nimbusds:nimbus-jose-jwt:9.38-rc3")

    implementation("ch.qos.logback:logback-classic:1.3.14")

    implementation("io.insert-koin:koin-core:$koinVersion")

    testImplementation("com.github.seeseemelk:MockBukkit-v1.20:$mockBukkitVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.insert-koin:koin-test:$koinVersion")
    testImplementation("io.insert-koin:koin-test-junit5:$koinVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
        kotlinOptions.javaParameters = true
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "17"
    }
    build {
        dependsOn(shadowJar)
    }
    runServer {
        minecraftVersion("1.20.4")
    }
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
    test {
        useJUnitPlatform()
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}



bukkit {
    name = "Moripa-API"
    version = "miencraft_plugin_version"
    website = "https://github.com/morinoparty/Moripa-API"

    main = "$group.moripaapi.MoripaAPI"

    apiVersion = "1.20"
}