package kexpression

/** Прямые дочерние узлы в порядке их появления в исходном коде. */
val Expression.children: List<Expression>
    get() = when (this) {
        is ConstantExpression, is ParameterExpression, is CapturedExpression -> emptyList()
        is LambdaExpression -> parameters + body
        is BinaryExpression -> listOf(left, right)
        is UnaryExpression -> listOf(operand)
        is MemberExpression -> listOfNotNull(receiver)
        is CallExpression -> listOfNotNull(receiver) + arguments
        is NewExpression -> arguments
        is ConditionalExpression -> listOf(test, ifTrue, ifFalse)
        is TypeOperatorExpression -> listOf(operand)
        is StringTemplateExpression -> parts
        is ArrayExpression -> elements
    }

/** Все узлы дерева в порядке обхода в глубину (pre-order), начиная с самого узла. */
fun Expression.walk(): Sequence<Expression> = sequence {
    yield(this@walk)
    for (child in children) yieldAll(child.walk())
}

/**
 * Перестраивает дерево снизу вверх — аналог `ExpressionVisitor` из C#.
 * [transform] получает узел с уже перестроенными детьми и возвращает замену или `null`, чтобы оставить узел как есть.
 */
fun Expression.rewrite(transform: (Expression) -> Expression?): Expression {
    fun Expression.r() = rewrite(transform)
    val rebuilt = when (this) {
        is ConstantExpression, is ParameterExpression, is CapturedExpression -> this
        is LambdaExpression -> copy(body = body.r())
        is BinaryExpression -> copy(left = left.r(), right = right.r())
        is UnaryExpression -> copy(operand = operand.r())
        is MemberExpression -> copy(receiver = receiver?.r())
        is CallExpression -> copy(receiver = receiver?.r(), arguments = arguments.map { it.r() })
        is NewExpression -> copy(arguments = arguments.map { it.r() })
        is ConditionalExpression -> copy(test = test.r(), ifTrue = ifTrue.r(), ifFalse = ifFalse.r())
        is TypeOperatorExpression -> copy(operand = operand.r())
        is StringTemplateExpression -> copy(parts = parts.map { it.r() })
        is ArrayExpression -> copy(elements = elements.map { it.r() })
    }
    return transform(rebuilt) ?: rebuilt
}
