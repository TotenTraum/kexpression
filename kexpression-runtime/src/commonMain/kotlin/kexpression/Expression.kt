package kexpression

/**
 * A node of an expression tree — the counterpart of C#'s `System.Linq.Expressions.Expression`.
 *
 * Trees are built by the compiler (the kexpression plugin) from lambda literals passed to [expr]
 * or to a parameter annotated with [Quoted]. They can also be built by hand.
 *
 * [type] is the fully qualified static type of the node, e.g. `kotlin.Int` or `kotlin.String?`.
 */
sealed class Expression {
    abstract val type: String

    // final in the base class => data classes don't generate their own toString.
    final override fun toString(): String = ExpressionPrinter.print(this)
}

/** A literal or a value known at compile time: `42`, `"abc"`, `null`, an enum entry, an object. */
data class ConstantExpression(val value: Any?, override val type: String) : Expression()

/** A reference to a lambda parameter. Parameters are compared by name and type. */
data class ParameterExpression(val name: String, override val type: String) : Expression()

/**
 * A value captured by the closure (a local variable, a parameter of the enclosing function, `this`).
 * The counterpart of a `MemberExpression` over the closure class in C#: [value] is the value at the time the tree was created.
 */
data class CapturedExpression(val name: String, val value: Any?, override val type: String) : Expression()

/** A lambda: `{ a, b -> body }`. The root of every tree obtained from [expr]. */
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

/** A property read: `receiver.member`. [receiver] is `null` for top-level properties. */
data class MemberExpression(
    val receiver: Expression?,
    val member: String,
    val declaringType: String?,
    override val type: String,
) : Expression()

/**
 * A function call: `receiver.function(arguments)`.
 *
 * A Kotlin call can have two receivers, and both are stored separately:
 *  - [dispatchReceiver] is the object the function belongs to: `list.size`, `O.lpad(...)`, an implicit `this`;
 *  - [extensionReceiver] is the receiver of an extension function: `name.uppercase()`, `list.first()`.
 *
 * A member extension (`fun String.len()` inside `object O`, called as `with(O) { s.len() }`) has both:
 * `dispatchReceiver = O`, `extensionReceiver = s`.
 *
 * [declaringType] is the owning class, or the package for top-level functions.
 */
data class CallExpression(
    val dispatchReceiver: Expression?,
    val extensionReceiver: Expression?,
    val function: String,
    val declaringType: String?,
    val arguments: List<Expression>,
    override val type: String,
    /**
     * Annotations of the called function that are marked with [Translatable] (e.g. `@SqlFunction("datetime")`).
     * The compiler never turns such a call into an [EvaluableExpression].
     */
    val annotations: List<Annotation> = emptyList(),
) : Expression()

/** The receiver the call is written on in source (`s` in `s.len()`): the extension receiver, otherwise the dispatch receiver. */
val CallExpression.receiver: Expression? get() = extensionReceiver ?: dispatchReceiver

/** `true` for extension functions, including member extensions. */
val CallExpression.isExtension: Boolean get() = extensionReceiver != null

/** The annotation of type [A] on the called function, if present. */
inline fun <reified A : Annotation> CallExpression.annotation(): A? = annotations.firstOrNull { it is A } as A?

/** A constructor call: `Point(x, y)`. */
data class NewExpression(
    val arguments: List<Expression>,
    /** Constructor parameter names for [arguments] — the counterpart of C#'s `NewExpression.Members`. */
    val parameterNames: List<String>,
    override val type: String,
) : Expression()

/** `if (test) ifTrue else ifFalse`; also the result of lowering `when`. */
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

/** A string template: `"Hello, $name!"`. */
data class StringTemplateExpression(val parts: List<Expression>, override val type: String) : Expression()

/** The elements of a vararg argument: `listOf(1, 2, 3)`. */
data class ArrayExpression(val elements: List<Expression>, override val type: String) : Expression()

/**
 * A subtree that does not depend on the lambda's parameters (`filter.minAge`, `repo.activeIds()`, `today.minusDays(7)`):
 * the output of the funcletizer. It holds both the structure and an evaluator; the consumer of the tree decides
 * which one to use via [EvaluationPolicy]. A SQL provider usually evaluates it on the client and binds it as a parameter.
 *
 * [evaluate] is generated by the compiler and runs the subtree's original code — without reflection, on any
 * platform. It reads values when it is called, not when the tree is created, and it can have the side effects
 * of the original code: don't call it more often than needed.
 *
 * Node equality compares [evaluate] by reference; to compare the shape of trees, compare [original].
 */
data class EvaluableExpression(
    val original: Expression,
    val evaluate: () -> Any?,
    override val type: String,
) : Expression()

/** Decides whether to evaluate an [EvaluableExpression] on the client or to process its [EvaluableExpression.original]. */
fun interface EvaluationPolicy {
    fun shouldEvaluate(node: EvaluableExpression): Boolean

    companion object {
        /** Evaluate everything that can be evaluated. */
        val Always = EvaluationPolicy { true }

        /** Structure only: evaluators are never called. */
        val Never = EvaluationPolicy { false }
    }
}

/**
 * Meta-annotation: calls to functions carrying an annotation marked with it are translated by the consumer of the
 * tree itself (for example, into a database function call) and are never evaluated on the client. The function's
 * annotation itself is copied into [CallExpression.annotations].
 *
 * ```
 * @Translatable
 * annotation class SqlFunction(val name: String)
 *
 * @SqlFunction("datetime")
 * fun dateTime(date: LocalDate, time: LocalTime): LocalDateTime = error("SQL only")
 * ```
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class Translatable

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
