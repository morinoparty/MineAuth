import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(kotlin("stdlib-jdk8"))
}

group = project.group
version = project.version.toString()

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = group.toString()
            artifactId = "mineauth-api"
            version = version
            from(components["kotlin"])
        }
    }
}

kotlin {
    jvmToolchain {
        (this).languageVersion.set(JavaLanguageVersion.of(21))
    }
    jvmToolchain(21)
}

dependencies{
    implementation(libs.kotlinx.serialization.json)
}

tasks {
    compileKotlin {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        compilerOptions.javaParameters = true
    }
}
repositories {
    mavenCentral()
}

