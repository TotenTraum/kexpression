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

subprojects {
    group = providers.gradleProperty("buildInfo.group").get()
    version = providers.gradleProperty("buildInfo.version").get()

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
