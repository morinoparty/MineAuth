import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
}

val projectVersion: String by project
group = "party.morino"
version = projectVersion

buildscript {
    repositories {
        mavenCentral()
    }
}

allprojects {

    apply(plugin = "java")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://jitpack.io")
        maven("https://plugins.gradle.org/m2/")
        maven("https://repo.codemc.io/repository/maven-public/")
    }

    dependencies {
        compileOnly(kotlin("stdlib"))
        compileOnly("org.jetbrains:annotations:26.0.2")
    }

    kotlin {
        jvmToolchain {
            (this).languageVersion.set(JavaLanguageVersion.of(21))
        }
        jvmToolchain(21)
    }

    tasks.register("hello") {
        doLast {
            println("I'm ${this.project.name}")
        }
    }

    tasks {
        test {
            useJUnitPlatform()
            testLogging {
                showStandardStreams = true
                events("passed", "skipped", "failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
        compileKotlin {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
            compilerOptions.javaParameters = true
            compilerOptions.languageVersion.set(KotlinVersion.KOTLIN_2_0)
        }
        compileTestKotlin {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        }

        withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
        }
    }


}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}
kotlin {
    jvmToolchain(21)
}
