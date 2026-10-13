package kexpression.internal

import kexpression.LambdaExpression

// On JS a lambda is a native function, i.e. an object: the tree is stored in one of its properties, no wrapper needed.
// That's why there is no limit on the number of parameters here.
private const val PROPERTY = $$"$kexpression"

internal actual fun <F : Function<*>> attachExpression(function: F, expression: LambdaExpression): F {
    function.asDynamic()[PROPERTY] = expression
    return function
}

internal actual fun attachedExpression(function: Function<*>): LambdaExpression? =
    function.asDynamic()[PROPERTY] as? LambdaExpression
