package kexpression.plugin

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val RUNTIME_PACKAGE = FqName("kexpression")
internal val INTERNAL_PACKAGE = FqName("kexpression.internal")
private val QUOTED_ANNOTATION = ClassId(RUNTIME_PACKAGE, Name.identifier("Quoted"))

class KExpressionIrGenerationExtension(private val messageCollector: MessageCollector) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (file in moduleFragment.files) {
            val symbols = RuntimeSymbols.resolve(pluginContext, file) ?: continue
            file.transformChildrenVoid(QuoteTransformer(pluginContext, symbols, file, messageCollector))
        }
    }
}

/** Функции runtime-библиотеки, которые использует сгенерированный код. */
internal class RuntimeSymbols(private val find: (FqName, String) -> IrSimpleFunctionSymbol?) {
    val expr = function(RUNTIME_PACKAGE, "expr")
    val quoteExpr = function(INTERNAL_PACKAGE, "quoteExpr")
    val quoteFunction = function(INTERNAL_PACKAGE, "quoteFunction")
    val constant = function(INTERNAL_PACKAGE, "constant")
    val parameter = function(INTERNAL_PACKAGE, "parameter")
    val captured = function(INTERNAL_PACKAGE, "captured")
    val lambda = function(INTERNAL_PACKAGE, "lambda")
    val binary = function(INTERNAL_PACKAGE, "binary")
    val unary = function(INTERNAL_PACKAGE, "unary")
    val member = function(INTERNAL_PACKAGE, "member")
    val call = function(INTERNAL_PACKAGE, "call")
    val construct = function(INTERNAL_PACKAGE, "construct")
    val names = function(INTERNAL_PACKAGE, "names")
    val conditional = function(INTERNAL_PACKAGE, "conditional")
    val typeOperator = function(INTERNAL_PACKAGE, "typeOperator")
    val template = function(INTERNAL_PACKAGE, "template")
    val array = function(INTERNAL_PACKAGE, "array")

    private fun function(pkg: FqName, name: String): IrSimpleFunctionSymbol =
        find(pkg, name) ?: throw MissingRuntime()

    private class MissingRuntime : RuntimeException()

    companion object {
        fun resolve(context: IrPluginContext, file: IrFile): RuntimeSymbols? {
            val finder = context.finderForSource(file)
            return try {
                RuntimeSymbols { pkg, name ->
                    finder.findFunctions(CallableId(pkg, Name.identifier(name))).singleOrNull()
                }
            } catch (_: MissingRuntime) {
                null // kexpression-runtime нет в classpath — модулю нечего трансформировать.
            }
        }
    }
}

/**
 * Находит лямбды, которые нужно процитировать:
 *  - аргумент `expr { ... }` — вызов заменяется на `quoteExpr(lambda, <дерево>)`;
 *  - аргумент параметра с `@Quoted` — лямбда оборачивается в `quoteFunction(lambda, <дерево>)`.
 */
private class QuoteTransformer(
    private val context: IrPluginContext,
    private val symbols: RuntimeSymbols,
    private val file: IrFile,
    private val messageCollector: MessageCollector,
) : IrElementTransformerVoidWithContext() {

    override fun visitCall(expression: IrCall): IrExpression {
        if (expression.symbol == symbols.expr) {
            expression.transformChildrenVoid()
            val lambda = expression.arguments[0]
            if (lambda !is IrFunctionExpression) {
                report(lambda ?: expression, "expr() argument must be a lambda literal")
                return expression
            }
            return quote(lambda, symbols.quoteExpr, expression) ?: expression
        }
        return visitFunctionAccess(expression) { super.visitCall(expression) }
    }

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression =
        visitFunctionAccess(expression) { super.visitConstructorCall(expression) }

    private inline fun visitFunctionAccess(
        expression: IrFunctionAccessExpression,
        visitChildren: () -> IrExpression,
    ): IrExpression {
        val result = visitChildren()
        for (parameter in expression.symbol.owner.parameters) {
            if (!parameter.hasAnnotation(QUOTED_ANNOTATION)) continue
            when (val argument = expression.arguments[parameter.indexInParameters]) {
                null -> {}
                is IrFunctionExpression ->
                    quote(argument, symbols.quoteFunction, argument)
                        ?.let { expression.arguments[parameter.indexInParameters] = it }
                // Пробрасывание @Quoted-параметра дальше сохраняет дерево — это нормально.
                is IrGetValue if (argument.symbol.owner as? IrValueParameter)?.hasAnnotation(QUOTED_ANNOTATION) == true -> {}
                else -> report(
                    argument,
                    "Argument for @Quoted parameter '${parameter.name}' is not a lambda literal; " +
                        "its expression tree will not be available",
                    CompilerMessageSeverity.WARNING,
                )
            }
        }
        return result
    }

    /** Строит `factory<F>(lambda, <дерево>)` или возвращает `null`, если лямбду нельзя процитировать. */
    private fun quote(lambda: IrFunctionExpression, factory: IrSimpleFunctionSymbol, original: IrExpression): IrExpression? {
        val builder = DeclarationIrBuilder(context, currentScope!!.scope.scopeOwnerSymbol, original.startOffset, original.endOffset)
        val tree = try {
            ExpressionTreeBuilder(builder, symbols).lambda(lambda)
        } catch (e: UnsupportedExpression) {
            report(e.element, "Cannot convert lambda to expression tree: ${e.message}")
            return null
        }
        return builder.irCall(factory, original.type).apply {
            typeArguments[0] = lambda.type
            arguments[0] = lambda
            arguments[1] = tree
        }
    }

    private fun report(
        element: IrElement,
        message: String,
        severity: CompilerMessageSeverity = CompilerMessageSeverity.ERROR,
    ) {
        messageCollector.report(severity, "[kexpression] $message", location(element))
    }

    private fun location(element: IrElement): CompilerMessageSourceLocation? {
        val entry = file.fileEntry
        if (element.startOffset < 0) return null
        return CompilerMessageLocation.create(
            entry.name,
            entry.getLineNumber(element.startOffset) + 1,
            entry.getColumnNumber(element.startOffset) + 1,
            null,
        )
    }
}
