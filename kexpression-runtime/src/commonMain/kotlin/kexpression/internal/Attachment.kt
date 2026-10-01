package kexpression.internal

import kexpression.LambdaExpression

/**
 * Связывает лямбду с её деревом выражения и возвращает функцию, которую можно передать вместо исходной.
 *
 * На JVM, Native и Wasm это обёртка, реализующая функциональный тип. На JS классам это запрещено,
 * зато лямбда — обычная JS-функция, и дерево хранится прямо в её свойстве.
 */
internal expect fun <F : Function<*>> attachExpression(function: F, expression: LambdaExpression): F

/** Дерево, приложенное [attachExpression], или `null`. */
internal expect fun attachedExpression(function: Function<*>): LambdaExpression?
