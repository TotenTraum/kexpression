import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("jvm") version libs.versions.kotlin apply false
    kotlin("multiplatform") version libs.versions.kotlin apply false

    alias(libs.plugins.publishPlugin) apply false
}

val prebuiltCompilerPlugin: File? = providers.gradleProperty("compilerPluginJar").orNull?.let { file(it) }
extra["prebuiltCompilerPlugin"] = prebuiltCompilerPlugin

subprojects {
    group = providers.gradleProperty("buildInfo.group").get()
    version = providers.gradleProperty("buildInfo.version").get()

    // The kexpression Gradle plugin adds published coordinates; inside this build they are the local modules.
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("$group:kexpression-runtime")).using(project(":kexpression-runtime"))
            substitute(module("$group:kexpression-compiler-plugin")).using(project(":kexpression-compiler-plugin"))
        }
    }

    // Binary compatibility (CI): -PcompilerPluginJar=<jar built with the pinned Kotlin> replaces the compiler plugin,
    // so a newer compiler runs the plugin exactly as users get it, without recompiling it.
    if (prebuiltCompilerPlugin != null) plugins.withId("ru.ttraum.kexpression") {
        configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach {
            exclude(group = group.toString(), module = "kexpression-compiler-plugin")
            dependencies.add(project.dependencies.create(files(prebuiltCompilerPlugin)))
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // Maven Central publishing for every module that applies the publish plugin.
    //   ./gradlew publishToMavenLocal                  — local check, no credentials needed
    //   ./gradlew publishAndReleaseToMavenCentral      — needs credentials in ~/.gradle/gradle.properties
    plugins.withId("com.vanniktech.maven.publish") {
        val projectUrl = providers.gradleProperty("buildInfo.url").get()

        extensions.configure<MavenPublishBaseExtension> {
            coordinates(group.toString(), project.name, version.toString())

            pom {
                name.set(project.name)
                description.set(providers.provider { project.description })
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

            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = true))
            }
            plugins.withId("org.jetbrains.kotlin.jvm") {
                configure(KotlinJvm(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
            }

            publishToMavenCentral()

            // Sign only when a key is configured, so publishToMavenLocal works on any machine.
            // Maven Central rejects unsigned artifacts, so a release still requires the key.
            val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
                providers.gradleProperty("signing.keyId").isPresent
            if (hasSigningKey) signAllPublications()
        }
    }
}

// The Gradle plugin is an included build: root publish tasks publish it together with the subprojects.
listOf("publishToMavenLocal", "publishAndReleaseToMavenCentral").forEach { name ->
    tasks.register(name) {
        group = "publishing"
        dependsOn(gradle.includedBuild("kexpression-gradle-plugin").task(":$name"))
    }
}

// With another Kotlin (CI compatibility runs) the JS tooling resolves other npm versions than kotlin-js-store/yarn.lock
// pins. That is not what those runs check, so the lock file is neither compared nor updated there.
if (providers.gradleProperty("kotlinVersion").isPresent) {
    tasks.matching { it.name == "kotlinStoreYarnLock" }.configureEach { enabled = false }
}
