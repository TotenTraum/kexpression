import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    kotlin("jvm") version libs.versions.kotlin
    `java-gradle-plugin`
    alias(libs.plugins.publishPlugin)
}

// Coordinates are shared with the root build: the plugin must point at runtime/compiler-plugin of the same version.
val buildInfo = Properties().apply { file("../gradle.properties").reader().use(::load) }
group = buildInfo.getProperty("buildInfo.group")
version = buildInfo.getProperty("buildInfo.version")
description = "Gradle plugin that attaches the kexpression compiler plugin and runtime to Kotlin compilations"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    // Provided by the Kotlin Gradle plugin the user applies; the user's Kotlin version wins.
    compileOnly(kotlin("gradle-plugin-api", libs.versions.kotlin.get()))
}

gradlePlugin {
    plugins {
        create("kexpression") {
            id = "${project.group}.kexpression"
            implementationClass = "kexpression.gradle.KExpressionGradlePlugin"
            displayName = "kexpression"
            description = project.description
        }
    }
}

// Group and version baked into the plugin, so it adds artifacts of exactly its own version.
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildInfo")
    val group = project.group.toString()
    val version = project.version.toString()
    inputs.property("group", group)
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("kexpression/gradle/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package kexpression.gradle
            |
            |internal object BuildInfo {
            |    const val GROUP = "$group"
            |    const val VERSION = "$version"
            |}
            |""".trimMargin()
        )
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateBuildInfo) }

mavenPublishing {
    coordinates(group.toString(), project.name, version.toString())
    configure(GradlePlugin(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))

    val projectUrl = buildInfo.getProperty("buildInfo.url")
    pom {
        name.set(project.name)
        description.set(project.description)
        url.set(projectUrl)
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            url.set(projectUrl)
            connection.set("scm:git:git://${projectUrl.removePrefix("https://")}.git")
            developerConnection.set("scm:git:ssh://git@${projectUrl.removePrefix("https://")}.git")
        }
        developers {
            developer {
                id.set("ttraum")
                name.set("Azim Usmanov")
                url.set("https://github.com/ttraum/")
            }
        }
    }

    publishToMavenCentral()

    // Same rule as in the root build: sign only when a key is configured.
    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    if (hasSigningKey) signAllPublications()
}
