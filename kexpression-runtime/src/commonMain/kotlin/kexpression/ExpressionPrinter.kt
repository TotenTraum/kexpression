package kexpression

/** Prints a tree as Kotlin-like source code. */
internal object ExpressionPrinter {
    fun print(e: Expression): String = when (e) {
        is ConstantExpression -> literal(e.value, e.type)
        is ParameterExpression -> e.name
        is CapturedExpression -> e.name
        is LambdaExpression ->
            if (e.parameters.isEmpty()) "{ ${print(e.body)} }"
            else "{ ${e.parameters.joinToString { "${it.name}: ${shortType(it.type)}" }} -> ${print(e.body)} }"
        is BinaryExpression -> "${operand(e.left)} ${e.operator.symbol} ${operand(e.right)}"
        is UnaryExpression ->
            if (e.operator == UnaryOperator.NOT_NULL) "${operand(e.operand)}!!"
            else if (e.operator == UnaryOperator.INV) "${operand(e.operand)}.inv()"
            else "${e.operator.symbol}${operand(e.operand)}"
        is MemberExpression -> e.receiver?.let { "${operand(it)}.${e.member}" } ?: e.member
        is CallExpression -> {
            val args = e.arguments.joinToString(", ", "(", ")") { print(it) }
            e.receiver?.let { "${operand(it)}.${e.function}$args" } ?: "${e.function}$args"
        }
        is NewExpression -> e.arguments.joinToString(", ", "${shortType(e.type)}(", ")") { print(it) }
        is ConditionalExpression -> "if (${print(e.test)}) ${print(e.ifTrue)} else ${print(e.ifFalse)}"
        is TypeOperatorExpression -> "${operand(e.operand)} ${e.operator.symbol} ${shortType(e.targetType)}"
        is StringTemplateExpression -> e.parts.joinToString("", "\"", "\"") { part ->
            if (part is ConstantExpression && part.value is String) escape(part.value) else "\${${print(part)}}"
        }
        is ArrayExpression -> e.elements.joinToString(", ") { print(it) }
        is EvaluableExpression -> print(e.original)
    }

    private fun operand(e: Expression): String = when (e) {
        is EvaluableExpression -> operand(e.original)
        is BinaryExpression, is ConditionalExpression, is TypeOperatorExpression, is LambdaExpression -> "(${print(e)})"
        else -> print(e)
    }

    // The literal form comes from the node's static type, not from the value: on JS, Int, Float and Double
    // are the same JS number, and `value is Float` is true even for `2`.
    private fun literal(value: Any?, type: String): String = when {
        value == null -> "null"
        value is Enum<*> -> "${value::class.simpleName}.${value.name}"
        else -> when (type.removeSuffix("?")) {
            "kotlin.String" -> "\"${escape(value.toString())}\""
            "kotlin.Char" -> "'$value'"
            "kotlin.Long" -> "${value}L"
            "kotlin.Float" -> "${value}f"
            else -> value.toString()
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("$", "\\$")

    private fun shortType(type: String) = type.substringAfterLast('.')
}
