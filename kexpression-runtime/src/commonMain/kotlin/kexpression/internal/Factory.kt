@file:kotlin.jvm.JvmName("KExpressionFactory")

package kexpression.internal

import kexpression.*

// Точки входа, на которые компиляторный плагин заменяет лямбды. Не предназначены для ручного вызова.

fun <F : Function<*>> quoteExpr(function: F, expression: LambdaExpression): Expr<F> = Expr(expression, function)

/** Прикладывает дерево к лямбде из `@Quoted`-параметра; см. [attachExpression]. */
fun <F : Function<*>> quoteFunction(function: F, expression: LambdaExpression): F = attachExpression(function, expression)

fun constant(value: Any?, type: String): Expression = ConstantExpression(value, type)

fun parameter(name: String, type: String): ParameterExpression = ParameterExpression(name, type)

fun captured(name: String, value: Any?, type: String): Expression = CapturedExpression(name, value, type)

fun lambda(body: Expression, type: String, vararg parameters: ParameterExpression): LambdaExpression =
    LambdaExpression(parameters.asList(), body, type)

fun binary(operator: String, left: Expression, right: Expression, type: String): Expression =
    BinaryExpression(BinaryOperator.valueOf(operator), left, right, type)

fun unary(operator: String, operand: Expression, type: String): Expression =
    UnaryExpression(UnaryOperator.valueOf(operator), operand, type)

fun member(receiver: Expression?, member: String, declaringType: String?, type: String): Expression =
    MemberExpression(receiver, member, declaringType, type)

fun call(
    receiver: Expression?,
    function: String,
    declaringType: String?,
    isExtension: Boolean,
    type: String,
    vararg arguments: Expression,
): Expression = CallExpression(receiver, function, declaringType, isExtension, arguments.asList(), type)

fun construct(type: String, parameterNames: List<String>, vararg arguments: Expression): Expression =
    NewExpression(arguments.asList(), parameterNames, type)

fun names(vararg names: String): List<String> = names.asList()

fun conditional(test: Expression, ifTrue: Expression, ifFalse: Expression, type: String): Expression =
    ConditionalExpression(test, ifTrue, ifFalse, type)

fun typeOperator(operator: String, operand: Expression, targetType: String, type: String): Expression =
    TypeOperatorExpression(TypeOperator.valueOf(operator), operand, targetType, type)

fun template(type: String, vararg parts: Expression): Expression = StringTemplateExpression(parts.asList(), type)

fun array(type: String, vararg elements: Expression): Expression = ArrayExpression(elements.asList(), type)
