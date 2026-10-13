package kexpression

import kexpression.internal.attachedExpression

/**
 * The typed expression tree of a lambda — the counterpart of C#'s `Expression<Func<...>>`.
 *
 * ```
 * val e: Expr<(Int) -> Boolean> = expr { x -> x > 10 }
 * e.expression      // LambdaExpression: { x: kotlin.Int -> x > 10 }
 * e.compile()(42)   // true
 * ```
 */
class Expr<out F : Function<*>> internal constructor(
    val expression: LambdaExpression,
    private val function: F,
) {
    val parameters: List<ParameterExpression> get() = expression.parameters
    val body: Expression get() = expression.body

    /**
     * The counterpart of `Expression<T>.Compile()`. The lambda's code was generated statically at compile time,
     * so "compiling" is free: the original lambda is returned.
     */
    fun compile(): F = function

    override fun toString(): String = expression.toString()
}

/**
 * Quotes a lambda: the compiler replaces this call with code that builds the [Expr] tree.
 * The counterpart of C#'s implicit conversion of a lambda to `Expression<Func<...>>`.
 *
 * The argument must be a lambda literal.
 */
@Suppress("UNUSED_PARAMETER")
fun <F : Function<*>> expr(block: F): Expr<F> =
    throw IllegalStateException("kexpression compiler plugin is not applied to this module")

/**
 * Marks a parameter of a function type: a lambda literal passed to it gets an expression tree,
 * available via [toExpression]. This makes LINQ-style APIs possible:
 *
 * ```
 * fun <T> Query<T>.where(@Quoted predicate: (T) -> Boolean): Query<T> =
 *     copy(filters = filters + predicate.toExpression())
 *
 * users.where { it.age > 18 }
 * ```
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Quoted

/** The expression tree of a lambda passed to a [Quoted] parameter, or `null` if it is not available. */
fun Function<*>.toExpressionOrNull(): LambdaExpression? = attachedExpression(this)

/** The expression tree of a lambda passed to a [Quoted] parameter. */
fun Function<*>.toExpression(): LambdaExpression = toExpressionOrNull()
    ?: throw IllegalArgumentException("Function has no expression tree: pass a lambda literal to a @Quoted parameter")

/** A typed wrapper for a function received through a [Quoted] parameter. */
fun <F : Function<*>> F.toExpr(): Expr<F> = Expr(toExpression(), this)

operator fun <R> Expr<() -> R>.invoke(): R = compile()()
operator fun <A, R> Expr<(A) -> R>.invoke(a: A): R = compile()(a)
operator fun <A, B, R> Expr<(A, B) -> R>.invoke(a: A, b: B): R = compile()(a, b)
operator fun <A, B, C, R> Expr<(A, B, C) -> R>.invoke(a: A, b: B, c: C): R = compile()(a, b, c)
