package kexpression

import kexpression.internal.attachedExpression

/**
 * Типизированное дерево выражения лямбды — аналог `Expression<Func<...>>` из C#.
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
     * Аналог `Expression<T>.Compile()`. Код лямбды сгенерирован статически во время компиляции,
     * поэтому «компиляция» бесплатна — возвращается исходная лямбда.
     */
    fun compile(): F = function

    override fun toString(): String = expression.toString()
}

/**
 * Цитирование лямбды: компилятор заменяет этот вызов на код, строящий дерево [Expr].
 * Аналог неявного преобразования лямбды к `Expression<Func<...>>` в C#.
 *
 * Аргумент должен быть лямбда-литералом.
 */
@Suppress("UNUSED_PARAMETER")
fun <F : Function<*>> expr(block: F): Expr<F> =
    throw IllegalStateException("kexpression compiler plugin is not applied to this module")

/**
 * Помечает параметр функционального типа: лямбда-литерал, переданный в него, получит
 * дерево выражения, доступное через [toExpression]. Позволяет писать LINQ-подобные API:
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

/** Дерево выражения лямбды, переданной в [Quoted]-параметр, или `null`, если оно недоступно. */
fun Function<*>.toExpressionOrNull(): LambdaExpression? = attachedExpression(this)

/** Дерево выражения лямбды, переданной в [Quoted]-параметр. */
fun Function<*>.toExpression(): LambdaExpression = toExpressionOrNull()
    ?: throw IllegalArgumentException("Function has no expression tree: pass a lambda literal to a @Quoted parameter")

/** Типизированная обёртка для функции из [Quoted]-параметра. */
fun <F : Function<*>> F.toExpr(): Expr<F> = Expr(toExpression(), this)

operator fun <R> Expr<() -> R>.invoke(): R = compile()()
operator fun <A, R> Expr<(A) -> R>.invoke(a: A): R = compile()(a)
operator fun <A, B, R> Expr<(A, B) -> R>.invoke(a: A, b: B): R = compile()(a, b)
operator fun <A, B, C, R> Expr<(A, B, C) -> R>.invoke(a: A, b: B, c: C): R = compile()(a, b, c)
