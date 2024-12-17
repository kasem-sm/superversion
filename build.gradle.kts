import org.jetbrains.intellij.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "sv.truestudio.app"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    localPath.set("/Applications/Android Studio.app/Contents")  // Path to your local Android Studio
    type.set("AI")
    // TODO: When using toml parser in future, add the dep here as well
    plugins.set(listOf(/* Plugin Dependencies */))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("232")
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

tasks.withType<RunIdeTask> {
    jvmArgs("-Didea.log.debug.categories=sv.truestudio.app.superversion")
}