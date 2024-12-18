import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.0"
}

group = "sv.truestudio.app"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }

    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("244.*")

        pluginDescription.set(
            """
            Android Dependency Latest Version Checker [libs.versions.toml].
            - Visual Indicator ✅ or ❌
            - Auto checks from Maven & Google Repository
        """.trimIndent()
        )
        changeNotes.set(
            """
            Initial release
        """.trimIndent()
        )
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

dependencies {
    intellijPlatform {
        // Android Studio fails
        intellijIdeaCommunity("2024.3.1")
    }
}

tasks.withType<RunIdeTask> {
    jvmArgs("-Didea.log.debug.categories=sv.truestudio.app.superversion")
}