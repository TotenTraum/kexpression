package kexpression.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * `plugins { id("ru.ttraum.kexpression") }` next to a Kotlin plugin. The Kotlin Gradle plugin then attaches
 * `kexpression-compiler-plugin` to every compilation (JVM, JS, Wasm, Native), and this plugin adds `kexpression-runtime`.
 */
class KExpressionGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        val extension = target.extensions.create("kexpression", KExpressionExtension::class.java)
        extension.addRuntimeDependency.convention(true)

        val runtime = "${BuildInfo.GROUP}:kexpression-runtime:${BuildInfo.VERSION}"
        fun addRuntimeTo(configurationName: String) {
            target.configurations.named(configurationName).configure { configuration ->
                configuration.withDependencies { dependencies ->
                    if (extension.addRuntimeDependency.get()) dependencies.add(target.dependencies.create(runtime))
                }
            }
        }

        target.plugins.withId("org.jetbrains.kotlin.multiplatform") { addRuntimeTo("commonMainImplementation") }
        target.plugins.withId("org.jetbrains.kotlin.jvm") { addRuntimeTo("implementation") }
        target.plugins.withId("org.jetbrains.kotlin.android") { addRuntimeTo("implementation") }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }

    // Must match `pluginId` of KExpressionCompilerPluginRegistrar.
    override fun getCompilerPluginId(): String = "kexpression"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(BuildInfo.GROUP, "kexpression-compiler-plugin", BuildInfo.VERSION)
}
