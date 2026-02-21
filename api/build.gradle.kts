import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

group = "party.morino"
version = project.version.toString()

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("party.morino", "mineauth-api", version.toString())

    pom {
        name.set("MineAuth API")
        description.set("API module for MineAuth - OAuth2/OpenID Connect authentication plugin for Minecraft")
        url.set("https://github.com/morinoparty/MineAuth")

        licenses {
            license {
                name.set("CC0-1.0")
                url.set("https://creativecommons.org/publicdomain/zero/1.0/")
            }
        }

        developers {
            developer {
                id.set("morinoparty")
                name.set("MorinoParty")
                url.set("https://github.com/morinoparty")
            }
        }

        scm {
            url.set("https://github.com/morinoparty/MineAuth")
            connection.set("scm:git:git://github.com/morinoparty/MineAuth.git")
            developerConnection.set("scm:git:ssh://git@github.com/morinoparty/MineAuth.git")
        }
    }
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(kotlin("stdlib-jdk8"))
}

kotlin {
    jvmToolchain {
        (this).languageVersion.set(JavaLanguageVersion.of(21))
    }
    jvmToolchain(21)
}

dependencies {
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
