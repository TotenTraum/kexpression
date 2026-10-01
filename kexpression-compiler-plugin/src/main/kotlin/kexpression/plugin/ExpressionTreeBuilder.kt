package kexpression.plugin

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBoolean
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

/**
 * Переводит IR тела лямбды в IR, который во время выполнения строит эквивалентное дерево
 * `kexpression.Expression` через фабрики из `kexpression.internal`.
 *
 * Пример: `{ x: Int -> x + 1 }` превращается в
 * `lambda(binary("PLUS", parameter("x", "kotlin.Int"), constant(1, "kotlin.Int"), "kotlin.Int"), ..., parameter("x", ...))`.
 */
internal class ExpressionTreeBuilder(
    private val b: IrBuilderWithScope,
    private val symbols: RuntimeSymbols,
) {
    private val expressionType = symbols.constant.owner.returnType
    private val parameterExpressionType = symbols.parameter.owner.returnType

    /** Параметры всех лямбд, внутри которых мы сейчас находимся. */
    private val lambdaParameters = mutableSetOf<IrValueSymbol>()

    /** Локальные `val` внутри лямбды (в т.ч. временные переменные `?:`, `?.`, `when`) — подставляются по месту. */
    private val locals = mutableMapOf<IrValueSymbol, IrExpression>()

    fun lambda(lambda: IrFunctionExpression): IrExpression {
        val function = lambda.function
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
                locals[statement.symbol] = translate(statement.initializer!!)
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

    private fun translate(e: IrExpression): IrExpression = when (e) {
        is IrConst -> call(symbols.constant, e.deepCopyWithSymbols(), str(typeName(e.type)))
        is IrGetValue -> getValue(e)
        is IrGetObjectValue, is IrGetEnumValue -> call(symbols.constant, e.deepCopyWithSymbols(), str(typeName(e.type)))
        is IrCall -> translateCall(e)
        is IrConstructorCall -> {
            // Пропущенные аргументы (значения по умолчанию) не попадают в дерево — имена сохраняют соответствие.
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
            // Вложенная лямбда: `list.any { it > x }` — параметры внешней остаются видимыми.
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
        locals[symbol]?.let { return it.deepCopyWithSymbols() }
        val owner = symbol.owner
        if (symbol in lambdaParameters && owner is IrValueParameter) return parameterRef(owner)
        // Всё остальное — замыкание: значение читается в месте создания дерева.
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

        val dispatch = parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }.takeIf { it >= 0 }?.let { args[it] }
        val extension = parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }.takeIf { it >= 0 }?.let { args[it] }
        val regular = parameters.indices.filter { parameters[it].kind == IrParameterKind.Regular }.map { args[it] }
        val receiver = dispatch ?: extension

        // `a != b` = `not(EQEQ(a, b))`, `a !== b` = `not(EQEQEQ(a, b))`.
        if (e.origin == IrStatementOrigin.EXCLEQ || e.origin == IrStatementOrigin.EXCLEQEQ) {
            val inner = receiver as? IrCall ?: throw UnsupportedExpression(e, "unexpected shape of `!=`")
            val op = if (e.origin == IrStatementOrigin.EXCLEQ) "NOT_EQUAL" else "IDENTITY_NOT_EQUAL"
            return binary(op, inner.arguments[0]!!, inner.arguments[1]!!, e.type)
        }

        // Свойство: геттер превращается в MemberExpression.
        function.correspondingPropertySymbol?.let { property ->
            if (regular.isEmpty()) {
                return member(receiver?.let { translate(it) }, property.owner.name.asString(), declaringType(function.parent), e.type)
            }
        }

        // Операторы (встроенные и перегруженные): `a + b`, `-a`, `!a`, `a and b`.
        if (receiver != null && extension == null) {
            if (regular.size == 1) BINARY_OPERATORS[name]?.let { op ->
                if (function.isOperator || function.isInfix) return binary(op, receiver, regular[0]!!, e.type)
            }
            if (regular.isEmpty()) UNARY_OPERATORS[name]?.let { op ->
                if (function.isOperator || name == "inv") return unary(op, receiver, e.type)
            }
        }

        return call(
            symbols.call,
            receiver?.let { translate(it) } ?: b.irNull(),
            str(name),
            declaringType(function.parent)?.let { str(it) } ?: b.irNull(),
            b.irBoolean(extension != null),
            str(typeName(e.type)),
            vararg(expressionType, regular.filterNotNull().map { translate(it) }),
        )
    }

    /** Псевдофункции из `kotlin.internal.ir`: `==`, `===`, `<`, `>`, `!!` и т.д. */
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
        // `a < b` для Comparable: `less(a.compareTo(b), 0)` — восстанавливаем исходную форму.
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
        // if / when → цепочка ConditionalExpression.
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
            // Неявные преобразования компилятора прозрачны для дерева.
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
        // Прочие блоки (`?.`, `when (x)`, ...) — временные переменные подставляются по месту использования.
        for (statement in statements.dropLast(1)) {
            if (statement is IrVariable && statement.initializer != null) {
                locals[statement.symbol] = translate(statement.initializer!!)
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

/** Человекочитаемое имя типа: `kotlin.Int`, `kotlin.String?`, `T`. */
internal fun typeName(type: IrType): String {
    val base = type.classFqName?.asString()
        ?: ((type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter)?.name?.asString()
        ?: "kotlin.Any"
    return if (type.isMarkedNullable()) "$base?" else base
}
