package kexpression

/**
 * Узел дерева выражений — аналог `System.Linq.Expressions.Expression` из C#.
 *
 * Деревья строятся компилятором (плагином kexpression) из лямбда-литералов, переданных в [expr]
 * или в параметр, помеченный [Quoted]. Их также можно собирать вручную.
 *
 * [type] — полное имя статического типа узла, например `kotlin.Int` или `kotlin.String?`.
 */
sealed class Expression {
    abstract val type: String

    // final в базовом классе => data-классы не генерируют свой toString.
    final override fun toString(): String = ExpressionPrinter.print(this)
}

/** Литерал или значение, известное во время компиляции: `42`, `"abc"`, `null`, enum entry, object. */
data class ConstantExpression(val value: Any?, override val type: String) : Expression()

/** Ссылка на параметр лямбды. Параметры сравниваются по имени и типу. */
data class ParameterExpression(val name: String, override val type: String) : Expression()

/**
 * Значение, захваченное замыканием (локальная переменная, параметр внешней функции, `this`).
 * Аналог `MemberExpression` над closure-классом в C#: [value] — значение на момент создания дерева.
 */
data class CapturedExpression(val name: String, val value: Any?, override val type: String) : Expression()

/** Лямбда: `{ a, b -> body }`. Корень любого дерева, полученного из [expr]. */
data class LambdaExpression(
    val parameters: List<ParameterExpression>,
    val body: Expression,
    override val type: String,
) : Expression()

data class BinaryExpression(
    val operator: BinaryOperator,
    val left: Expression,
    val right: Expression,
    override val type: String,
) : Expression()

data class UnaryExpression(
    val operator: UnaryOperator,
    val operand: Expression,
    override val type: String,
) : Expression()

/** Чтение свойства: `receiver.member`. [receiver] равен `null` для top-level свойств. */
data class MemberExpression(
    val receiver: Expression?,
    val member: String,
    val declaringType: String?,
    override val type: String,
) : Expression()

/**
 * Вызов функции: `receiver.function(arguments)`.
 * [isExtension] — `true`, если [receiver] является extension-receiver'ом (`list.first()`).
 * [declaringType] — класс-владелец или пакет для top-level функций.
 */
data class CallExpression(
    val receiver: Expression?,
    val function: String,
    val declaringType: String?,
    val isExtension: Boolean,
    val arguments: List<Expression>,
    override val type: String,
) : Expression()

/** Вызов конструктора: `Point(x, y)`. */
data class NewExpression(
    val arguments: List<Expression>,
    /** Имена параметров конструктора для [arguments] — аналог `NewExpression.Members` в C#. */
    val parameterNames: List<String>,
    override val type: String,
) : Expression()

/** `if (test) ifTrue else ifFalse`; также результат понижения `when`. */
data class ConditionalExpression(
    val test: Expression,
    val ifTrue: Expression,
    val ifFalse: Expression,
    override val type: String,
) : Expression()

/** `is`, `!is`, `as`, `as?`. */
data class TypeOperatorExpression(
    val operator: TypeOperator,
    val operand: Expression,
    val targetType: String,
    override val type: String,
) : Expression()

/** Строковый шаблон: `"Hello, $name!"`. */
data class StringTemplateExpression(val parts: List<Expression>, override val type: String) : Expression()

/** Элементы vararg-аргумента: `listOf(1, 2, 3)`. */
data class ArrayExpression(val elements: List<Expression>, override val type: String) : Expression()

enum class BinaryOperator(val symbol: String) {
    PLUS("+"), MINUS("-"), TIMES("*"), DIV("/"), REM("%"),
    EQUAL("=="), NOT_EQUAL("!="), IDENTITY_EQUAL("==="), IDENTITY_NOT_EQUAL("!=="),
    LESS("<"), LESS_OR_EQUAL("<="), GREATER(">"), GREATER_OR_EQUAL(">="),
    AND("&&"), OR("||"), ELVIS("?:"),
    BIT_AND("and"), BIT_OR("or"), BIT_XOR("xor"), SHL("shl"), SHR("shr"), USHR("ushr"),
}

enum class UnaryOperator(val symbol: String) {
    NOT("!"), MINUS("-"), PLUS("+"), NOT_NULL("!!"), INV("inv"),
}

enum class TypeOperator(val symbol: String) {
    IS("is"), NOT_IS("!is"), AS("as"), SAFE_AS("as?"),
}
