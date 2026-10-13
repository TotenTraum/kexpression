package kexpression.internal

import kexpression.LambdaExpression

/**
 * Attaches an expression tree to a lambda and returns a function that can be passed instead of the original.
 *
 * On JVM, Native and Wasm this is a wrapper implementing the function type. On JS classes can't do that,
 * but a lambda is a plain JS function, so the tree is stored directly in one of its properties.
 */
internal expect fun <F : Function<*>> attachExpression(function: F, expression: LambdaExpression): F

/** The tree attached by [attachExpression], or `null`. */
internal expect fun attachedExpression(function: Function<*>): LambdaExpression?
