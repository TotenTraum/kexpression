package kexpression.internal

import kexpression.LambdaExpression

// Лямбда на JS — нативная функция, т.е. объект: дерево кладётся в её свойство, обёртка не нужна.
// Поэтому ограничения на число параметров здесь нет.
private const val PROPERTY = $$"$kexpression"

internal actual fun <F : Function<*>> attachExpression(function: F, expression: LambdaExpression): F {
    function.asDynamic()[PROPERTY] = expression
    return function
}

internal actual fun attachedExpression(function: Function<*>): LambdaExpression? =
    function.asDynamic()[PROPERTY] as? LambdaExpression
