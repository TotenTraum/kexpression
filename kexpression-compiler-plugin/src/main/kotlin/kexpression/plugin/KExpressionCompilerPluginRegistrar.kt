package kexpression.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration

class KExpressionCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "kexpression"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(KExpressionIrGenerationExtension(configuration.messageCollector))
    }
}
