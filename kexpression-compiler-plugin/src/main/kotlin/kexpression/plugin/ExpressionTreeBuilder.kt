package kexpression.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.isFalseConst

internal class UnsupportedExpression(val element: IrElement, message: String) : RuntimeException(message)

private val TRANSLATABLE = ClassId(RUNTIME_PACKAGE, Name.identifier("Translatable"))

/**
 * Translates the IR of a lambda body into IR that builds the equivalent `kexpression.Expression` tree
 * at runtime, using the factories from `kexpression.internal`.
 *
 * Example: `{ x: Int -> x + 1 }` becomes
 * `lambda(binary("PLUS", parameter("x", "kotlin.Int"), constant(1, "kotlin.Int"), "kotlin.Int"), ..., parameter("x", ...))`.
 *
 * Funcletizer: the largest subtrees that don't depend on the lambda's parameters (`filter.minAge`, `repo.ids()`)
 * become `evaluable(<structure>, { <the subtree's original code> }, type)`. The evaluator is generated right here,
 * so the consumer of the tree needs no reflection. Calls to `@Translatable` functions are never evaluated.
 */
internal class ExpressionTreeBuilder(
    private val b: IrBuilderWithScope,
    private val symbols: RuntimeSymbols,
    private val context: IrPluginContext,
) {
    private val expressionType = symbols.constant.owner.returnType
    private val parameterExpressionType = symbols.parameter.owner.returnType

    /** Parameters of all lambdas we are currently inside. */
    private val lambdaParameters = mutableSetOf<IrValueSymbol>()

    /** Local `val`s inside the lambda (including temporaries of `?:`, `?.`, `when`) — inlined at their uses. */
    private val locals = mutableMapOf<IrValueSymbol, IrExpression>()

    /** The original initializers of those `val`s — inlined into evaluator code. */
    private val localInitializers = mutableMapOf<IrValueSymbol, IrExpression>()

    /** `val`s that can't be evaluated on the client: they depend on parameters or on `@Translatable` calls. */
    private val opaqueLocals = mutableSetOf<IrValueSymbol>()

    /** The parent for evaluator functions: the same as the quoted lambda's. */
    private var thunkParent: IrDeclarationParent? = null

    /** > 0 while building the structure inside an EvaluableExpression: nested evaluators are not needed. */
    private var structureOnly = 0

    fun lambda(lambda: IrFunctionExpression): IrExpression {
        val function = lambda.function
        if (thunkParent == null) thunkParent = function.parent
        lambdaParameters += function.parameters.map { it.symbol }
        val body = translate(bodyExpression(function))
        val params = function.parameters.map { parameterRef(it) }
        return call(symbols.lambda, body, str(typeName(lambda.type)), vararg(parameterExpressionType, params))
    }

    private fun bodyExpression(function: IrSimpleFunction): IrExpression {
        val statements = (function.body as? IrBlockBody)?.statements
            ?: throw UnsupportedExpression(function, "lambda has no body")
        for (statement in statements.dropLast(1)) {
            if (statement is IrVariable && !statement.isVar && statement.initializer != null) {
                bindLocal(statement)
            } else {
                throw UnsupportedExpression(statement, "only `val` declarations and a single result expression are allowed in expression lambdas")
            }
        }
        return when (val last = statements.lastOrNull()) {
            is IrReturn -> last.value
            is IrExpression -> last
            else -> throw UnsupportedExpression(last ?: function, "lambda body must end with an expression")
        }
    }

    private fun bindLocal(variable: IrVariable) {
        val initializer = variable.initializer!!
        localInitializers[variable.symbol] = initializer
        if (!isClosed(initializer)) opaqueLocals += variable.symbol
        locals[variable.symbol] = translate(initializer)
    }

    private fun translate(e: IrExpression): IrExpression =
        if (structureOnly == 0 && isEvaluable(e)) evaluable(e) else translateStructure(e)

    // --- funcletizer ---

    /** Not a leaf (constant, variable, object), independent of parameters, free of lambdas and `@Translatable` calls. */
    private fun isEvaluable(e: IrExpression): Boolean = when (stripImplicitCasts(e)) {
        is IrConst, is IrGetValue, is IrGetObjectValue, is IrGetEnumValue, is IrFunctionExpression, is IrVararg -> false
        else -> isClosed(e)
    }

    private fun isClosed(e: IrExpression): Boolean {
        var closed = true
        e.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (closed) element.acceptChildrenVoid(this)
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol in lambdaParameters || expression.symbol in opaqueLocals) closed = false
            }

            // Nested lambdas stay structure: the consumer handles `ids.any { it > 0 }`.
            override fun visitFunctionExpression(expression: IrFunctionExpression) {
                closed = false
            }

            override fun visitCall(expression: IrCall) {
                if (isTranslatable(expression.symbol.owner)) closed = false else super.visitCall(expression)
            }
        })
        return closed
    }

    /** `evaluable(<structure of e>, { e }, type)`. */
    private fun evaluable(e: IrExpression): IrExpression {
        structureOnly++
        val original = try {
            translateStructure(e)
        } finally {
            structureOnly--
        }
        return call(symbols.evaluable, original, thunk(e), str(typeName(e.type)))
    }

    /** `{ <copy of e> }: () -> Any?` — the quoted lambda's local `val`s are replaced with their initializers. */
    private fun thunk(e: IrExpression): IrExpression {
        val builtIns = context.irBuiltIns
        val function = context.irFactory.buildFun {
            startOffset = e.startOffset
            endOffset = e.endOffset
            name = SpecialNames.ANONYMOUS
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            visibility = DescriptorVisibilities.LOCAL
            returnType = builtIns.anyNType
        }
        function.parent = thunkParent!!
        val inliner = object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val initializer = localInitializers[expression.symbol] ?: return expression
                return initializer.deepCopyWithSymbols(function).transform(this, null)
            }
        }
        val body = e.deepCopyWithSymbols(function).transform(inliner, null)
        function.body = DeclarationIrBuilder(context, function.symbol, e.startOffset, e.endOffset).irBlockBody {
            +irReturn(body)
        }
        return IrFunctionExpressionImpl(
            e.startOffset, e.endOffset,
            builtIns.functionN(0).typeWith(builtIns.anyNType),
            function,
            IrStatementOrigin.LAMBDA,
        )
    }

    private fun stripImplicitCasts(e: IrExpression): IrExpression {
        var current = e
        while (current is IrTypeOperatorCall && current.operator !in EXPLICIT_TYPE_OPERATORS) current = current.argument
        return current
    }

    private fun isTranslatable(function: IrSimpleFunction) = function.annotations.any(::isTranslatableAnnotation)

    private fun isTranslatableAnnotation(annotation: IrConstructorCall) =
        annotation.symbol.owner.constructedClass.hasAnnotation(TRANSLATABLE)

    // --- structure ---

    private fun translateStructure(e: IrExpression): IrExpression = when (e) {
        is IrConst -> call(symbols.constant, e.deepCopyWithSymbols(), str(typeName(e.type)))
        is IrGetValue -> getValue(e)
        is IrGetObjectValue, is IrGetEnumValue -> call(symbols.constant, e.deepCopyWithSymbols(), str(typeName(e.type)))
        is IrCall -> translateCall(e)
        is IrConstructorCall -> {
            // Omitted arguments (default values) don't make it into the tree; the names keep the correspondence.
            val passed = e.symbol.owner.parameters
                .filter { it.kind == IrParameterKind.Regular }
                .mapNotNull { p -> e.arguments[p.indexInParameters]?.let { p.name.asString() to it } }
            call(
                symbols.construct,
                str(typeName(e.type)),
                call(symbols.names, vararg(b.context.irBuiltIns.stringType, passed.map { str(it.first) })),
                vararg(expressionType, passed.map { translate(it.second) }),
            )
        }
        is IrFunctionExpression -> {
            // Nested lambda: `list.any { it > x }` — the outer lambda's parameters stay visible.
            val saved = lambdaParameters.toSet()
            lambda(e).also { lambdaParameters.retainAll(saved) }
        }
        is IrWhen -> translateWhen(e)
        is IrTypeOperatorCall -> translateTypeOperator(e)
        is IrStringConcatenation -> call(
            symbols.template,
            str(typeName(e.type)),
            vararg(expressionType, e.arguments.map { translate(it) }),
        )
        is IrBlock -> translateBlock(e)
        is IrGetField -> member(
            e.receiver?.let { translate(it) },
            e.symbol.owner.name.asString(),
            declaringType(e.symbol.owner.parent),
            e.type,
        )
        is IrVararg -> call(
            symbols.array,
            str(typeName(e.type)),
            vararg(expressionType, e.elements.map {
                if (it is IrSpreadElement) throw UnsupportedExpression(it, "spread operator is not supported")
                translate(it as IrExpression)
            }),
        )
        else -> throw UnsupportedExpression(e, "${e::class.simpleName} is not supported in expression trees")
    }

    private fun getValue(e: IrGetValue): IrExpression {
        val symbol = e.symbol
        locals[symbol]?.let { return it.deepCopyWithSymbols(thunkParent) }
        val owner = symbol.owner
        if (symbol in lambdaParameters && owner is IrValueParameter) return parameterRef(owner)
        // Everything else is a closure: the value is read where the tree is created.
        val name = when {
            owner is IrValueParameter && owner.kind == IrParameterKind.DispatchReceiver -> "this"
            owner is IrValueParameter && owner.kind == IrParameterKind.ExtensionReceiver -> "this"
            else -> owner.name.asString()
        }
        return call(symbols.captured, str(name), e.deepCopyWithSymbols(), str(typeName(e.type)))
    }

    private fun parameterRef(p: IrValueParameter): IrExpression {
        val name = if (p.kind == IrParameterKind.ExtensionReceiver) "this" else p.name.asString()
        return call(symbols.parameter, str(name), str(typeName(p.type)))
    }

    private fun translateCall(e: IrCall): IrExpression {
        val function = e.symbol.owner
        val name = function.name.asString()
        val parameters = function.parameters
        val args = e.arguments.toList()

        if (isIrBuiltin(function)) return translateBuiltin(e, name, args)

        // Functions with @Translatable annotations are always plain calls, never shortened to properties or operators.
        val translatable = function.annotations.filter(::isTranslatableAnnotation)

        val dispatch = parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }.takeIf { it >= 0 }?.let { args[it] }
        val extension = parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }.takeIf { it >= 0 }?.let { args[it] }
        val regular = parameters.indices.filter { parameters[it].kind == IrParameterKind.Regular }.map { args[it] }
        // The receiver the call is written on: for a member extension (`with(O) { s.len() }`) that's `s`, not `O`.
        val receiver = extension ?: dispatch

        // `a != b` = `not(EQEQ(a, b))`, `a !== b` = `not(EQEQEQ(a, b))`.
        if (e.origin == IrStatementOrigin.EXCLEQ || e.origin == IrStatementOrigin.EXCLEQEQ) {
            val inner = receiver as? IrCall ?: throw UnsupportedExpression(e, "unexpected shape of `!=`")
            val op = if (e.origin == IrStatementOrigin.EXCLEQ) "NOT_EQUAL" else "IDENTITY_NOT_EQUAL"
            return binary(op, inner.arguments[0]!!, inner.arguments[1]!!, e.type)
        }

        // Property: a getter becomes a MemberExpression.
        if (translatable.isEmpty()) function.correspondingPropertySymbol?.let { property ->
            if (regular.isEmpty()) {
                return member(receiver?.let { translate(it) }, property.owner.name.asString(), declaringType(function.parent), e.type)
            }
        }

        // Operators (built-in and overloaded): `a + b`, `-a`, `!a`, `a and b`.
        if (receiver != null && extension == null && translatable.isEmpty()) {
            if (regular.size == 1) BINARY_OPERATORS[name]?.let { op ->
                if (function.isOperator || function.isInfix) return binary(op, receiver, regular[0]!!, e.type)
            }
            if (regular.isEmpty()) UNARY_OPERATORS[name]?.let { op ->
                if (function.isOperator || name == "inv") return unary(op, receiver, e.type)
            }
        }

        return call(
            symbols.call,
            dispatch?.let { translate(it) } ?: b.irNull(),
            extension?.let { translate(it) } ?: b.irNull(),
            str(name),
            declaringType(function.parent)?.let { str(it) } ?: b.irNull(),
            str(typeName(e.type)),
            call(
                symbols.annotations,
                vararg(context.irBuiltIns.annotationType, translatable.map { it.deepCopyWithSymbols(thunkParent) }),
            ),
            vararg(expressionType, regular.filterNotNull().map { translate(it) }),
        )
    }

    /** Pseudo-functions from `kotlin.internal.ir`: `==`, `===`, `<`, `>`, `!!`, etc. */
    private fun translateBuiltin(e: IrCall, name: String, args: List<IrExpression?>): IrExpression {
        val op = when (name) {
            "EQEQ", "ieee754equals" -> "EQUAL"
            "EQEQEQ" -> "IDENTITY_EQUAL"
            "less" -> "LESS"
            "lessOrEqual" -> "LESS_OR_EQUAL"
            "greater" -> "GREATER"
            "greaterOrEqual" -> "GREATER_OR_EQUAL"
            "CHECK_NOT_NULL" -> return unary("NOT_NULL", args[0]!!, e.type)
            else -> throw UnsupportedExpression(e, "intrinsic '$name' is not supported")
        }
        var left = args[0]!!
        var right = args[1]!!
        // `a < b` on Comparable is `less(a.compareTo(b), 0)` — restore the original form.
        if (op.startsWith("LESS") || op.startsWith("GREATER")) {
            if (left is IrCall && left.symbol.owner.name.asString() == "compareTo" && (right as? IrConst)?.value == 0) {
                val compareTo = left
                left = compareTo.arguments[0]!!
                right = compareTo.arguments[1]!!
            }
        }
        return binary(op, left, right, e.type)
    }

    private fun translateWhen(e: IrWhen): IrExpression {
        val branches = e.branches
        when (e.origin) {
            // `a && b` = `when { a -> b; else -> false }`
            IrStatementOrigin.ANDAND -> if (branches.size == 2 && branches[1].result.isFalseConst()) {
                return binary("AND", branches[0].condition, branches[0].result, e.type)
            }
            // `a || b` = `when { a -> true; else -> b }`
            IrStatementOrigin.OROR -> if (branches.size == 2 && branches[0].result.isTrueConst()) {
                return binary("OR", branches[0].condition, branches[1].result, e.type)
            }
        }
        // if / when → a chain of ConditionalExpressions.
        var result: IrExpression? = null
        for (branch in branches.asReversed()) {
            result = if (branch is IrElseBranch || branch.condition.isTrueConst()) {
                translate(branch.result)
            } else {
                call(
                    symbols.conditional,
                    translate(branch.condition),
                    translate(branch.result),
                    result ?: call(symbols.constant, b.irNull(), str(typeName(e.type))),
                    str(typeName(e.type)),
                )
            }
        }
        return result ?: throw UnsupportedExpression(e, "empty when")
    }

    private fun translateTypeOperator(e: IrTypeOperatorCall): IrExpression {
        val op = when (e.operator) {
            IrTypeOperator.INSTANCEOF -> "IS"
            IrTypeOperator.NOT_INSTANCEOF -> "NOT_IS"
            IrTypeOperator.CAST -> "AS"
            IrTypeOperator.SAFE_CAST -> "SAFE_AS"
            // Implicit conversions inserted by the compiler are transparent to the tree.
            else -> return translate(e.argument)
        }
        return call(symbols.typeOperator, str(op), translate(e.argument), str(typeName(e.typeOperand)), str(typeName(e.type)))
    }

    private fun translateBlock(e: IrBlock): IrExpression {
        val statements = e.statements
        // `a ?: b` = `{ val tmp = a; when { tmp == null -> b; else -> tmp } }`
        if (e.origin == IrStatementOrigin.ELVIS && statements.size == 2) {
            val tmp = statements[0] as? IrVariable
            val whenExpr = statements[1] as? IrWhen
            if (tmp != null && whenExpr != null && whenExpr.branches.size == 2) {
                return binary("ELVIS", tmp.initializer!!, whenExpr.branches[0].result, e.type)
            }
        }
        // Other blocks (`?.`, `when (x)`, ...) — temporaries are inlined at their uses.
        for (statement in statements.dropLast(1)) {
            if (statement is IrVariable && statement.initializer != null) {
                bindLocal(statement)
            } else {
                throw UnsupportedExpression(statement, "statements are not supported in expression trees")
            }
        }
        val last = statements.lastOrNull() as? IrExpression ?: throw UnsupportedExpression(e, "block must end with an expression")
        return translate(last)
    }

    // --- helpers ---

    private fun binary(op: String, left: IrExpression, right: IrExpression, type: IrType) =
        call(symbols.binary, str(op), translate(left), translate(right), str(typeName(type)))

    private fun unary(op: String, operand: IrExpression, type: IrType) =
        call(symbols.unary, str(op), translate(operand), str(typeName(type)))

    private fun member(receiver: IrExpression?, name: String, declaringType: String?, type: IrType) = call(
        symbols.member,
        receiver ?: b.irNull(),
        str(name),
        declaringType?.let { str(it) } ?: b.irNull(),
        str(typeName(type)),
    )

    private fun call(function: IrSimpleFunctionSymbol, vararg args: IrExpression): IrExpression =
        b.irCall(function).apply { args.forEachIndexed { i, arg -> arguments[i] = arg } }

    private fun str(value: String) = b.irString(value)

    private fun vararg(elementType: IrType, elements: List<IrExpression>) = b.irVararg(elementType, elements)

    private fun isIrBuiltin(function: IrSimpleFunction): Boolean =
        (function.parent as? IrPackageFragment)?.packageFqName?.asString() == "kotlin.internal.ir"

    private fun declaringType(parent: IrDeclarationParent): String? = when (parent) {
        is IrClass -> parent.fqNameWhenAvailable?.asString()
        is IrPackageFragment -> parent.packageFqName.asString()
        else -> null
    }

    companion object {
        private val EXPLICIT_TYPE_OPERATORS = setOf(
            IrTypeOperator.INSTANCEOF, IrTypeOperator.NOT_INSTANCEOF, IrTypeOperator.CAST, IrTypeOperator.SAFE_CAST,
        )
        private val BINARY_OPERATORS = mapOf(
            "plus" to "PLUS", "minus" to "MINUS", "times" to "TIMES", "div" to "DIV", "rem" to "REM",
            "and" to "BIT_AND", "or" to "BIT_OR", "xor" to "BIT_XOR",
            "shl" to "SHL", "shr" to "SHR", "ushr" to "USHR",
        )
        private val UNARY_OPERATORS = mapOf(
            "not" to "NOT", "unaryMinus" to "MINUS", "unaryPlus" to "PLUS", "inv" to "INV",
        )
    }
}

/** A human-readable type name: `kotlin.Int`, `kotlin.String?`, `T`. */
internal fun typeName(type: IrType): String {
    val base = type.classFqName?.asString()
        ?: ((type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter)?.name?.asString()
        ?: "kotlin.Any"
    return if (type.isMarkedNullable()) "$base?" else base
}
