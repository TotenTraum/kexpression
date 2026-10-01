package kexpression.internal

import kexpression.LambdaExpression

@Suppress("UNCHECKED_CAST")
internal actual fun <F : Function<*>> attachExpression(function: F, expression: LambdaExpression): F =
    when (expression.parameters.size) {
        0 -> QuotedFunction0(expression, function as () -> Any?)
        1 -> QuotedFunction1(expression, function as (Any?) -> Any?)
        2 -> QuotedFunction2(expression, function as (Any?, Any?) -> Any?)
        3 -> QuotedFunction3(expression, function as (Any?, Any?, Any?) -> Any?)
        4 -> QuotedFunction4(expression, function as (Any?, Any?, Any?, Any?) -> Any?)
        else -> throw UnsupportedOperationException("@Quoted lambdas with more than 4 parameters are not supported")
    } as F

internal actual fun attachedExpression(function: Function<*>): LambdaExpression? =
    (function as? QuotedFunction)?.expression

private interface QuotedFunction {
    val expression: LambdaExpression
}

private class QuotedFunction0(override val expression: LambdaExpression, private val f: () -> Any?) :
    () -> Any?, QuotedFunction {
    override fun invoke() = f()
}

private class QuotedFunction1(override val expression: LambdaExpression, private val f: (Any?) -> Any?) :
    (Any?) -> Any?, QuotedFunction {
    override fun invoke(p1: Any?) = f(p1)
}

private class QuotedFunction2(override val expression: LambdaExpression, private val f: (Any?, Any?) -> Any?) :
    (Any?, Any?) -> Any?, QuotedFunction {
    override fun invoke(p1: Any?, p2: Any?) = f(p1, p2)
}

private class QuotedFunction3(
    override val expression: LambdaExpression,
    private val f: (Any?, Any?, Any?) -> Any?,
) : (Any?, Any?, Any?) -> Any?, QuotedFunction {
    override fun invoke(p1: Any?, p2: Any?, p3: Any?) = f(p1, p2, p3)
}

private class QuotedFunction4(
    override val expression: LambdaExpression,
    private val f: (Any?, Any?, Any?, Any?) -> Any?,
) : (Any?, Any?, Any?, Any?) -> Any?, QuotedFunction {
    override fun invoke(p1: Any?, p2: Any?, p3: Any?, p4: Any?) = f(p1, p2, p3, p4)
}
