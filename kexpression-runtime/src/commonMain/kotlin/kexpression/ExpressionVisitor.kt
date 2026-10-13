package kexpression

/** The direct children of the node, in the order they appear in source. */
val Expression.children: List<Expression>
    get() = when (this) {
        is ConstantExpression, is ParameterExpression, is CapturedExpression -> emptyList()
        is LambdaExpression -> parameters + body
        is BinaryExpression -> listOf(left, right)
        is UnaryExpression -> listOf(operand)
        is MemberExpression -> listOfNotNull(receiver)
        is CallExpression -> listOfNotNull(dispatchReceiver, extensionReceiver) + arguments
        is NewExpression -> arguments
        is ConditionalExpression -> listOf(test, ifTrue, ifFalse)
        is TypeOperatorExpression -> listOf(operand)
        is StringTemplateExpression -> parts
        is ArrayExpression -> elements
        is EvaluableExpression -> listOf(original)
    }

/** All nodes of the tree in depth-first (pre-order) order, starting with the node itself. */
fun Expression.walk(): Sequence<Expression> = sequence {
    yield(this@walk)
    for (child in children) yieldAll(child.walk())
}

/**
 * Rebuilds the tree bottom-up — the counterpart of C#'s `ExpressionVisitor`.
 * [transform] receives a node whose children are already rebuilt and returns a replacement, or `null` to keep the node.
 */
fun Expression.rewrite(transform: (Expression) -> Expression?): Expression {
    fun Expression.r() = rewrite(transform)
    val rebuilt = when (this) {
        is ConstantExpression, is ParameterExpression, is CapturedExpression -> this
        is LambdaExpression -> copy(body = body.r())
        is BinaryExpression -> copy(left = left.r(), right = right.r())
        is UnaryExpression -> copy(operand = operand.r())
        is MemberExpression -> copy(receiver = receiver?.r())
        is CallExpression -> copy(
            dispatchReceiver = dispatchReceiver?.r(),
            extensionReceiver = extensionReceiver?.r(),
            arguments = arguments.map { it.r() },
        )
        is NewExpression -> copy(arguments = arguments.map { it.r() })
        is ConditionalExpression -> copy(test = test.r(), ifTrue = ifTrue.r(), ifFalse = ifFalse.r())
        is TypeOperatorExpression -> copy(operand = operand.r())
        is StringTemplateExpression -> copy(parts = parts.map { it.r() })
        is ArrayExpression -> copy(elements = elements.map { it.r() })
        // The evaluator stays the same: it runs the original code, not the rebuilt structure.
        is EvaluableExpression -> copy(original = original.r())
    }
    return transform(rebuilt) ?: rebuilt
}
