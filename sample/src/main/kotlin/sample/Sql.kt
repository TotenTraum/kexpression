package sample

import kexpression.*
import kotlin.reflect.KClass

/**
 * A mini LINQ provider: predicates are written as plain Kotlin lambdas and turned into SQL
 * via their expression trees — like `IQueryable<T>.Where(Expression<Func<T, bool>>)` in C#.
 *
 * Joins are typed by the number of tables: `Query<A>` → `Query2<A, B>` → `Query3<A, B, C>`.
 * The i-th parameter of any lambda is the i-th table of the query, so
 * `{ u, o -> u.id == o.userId }` translates to `u.id = o.user_id`.
 * In `leftJoin` the ON condition sees the table as non-null, and later lambdas see it as nullable.
 */
/** A table and the class of its rows — needed to materialize results in `toList()`. */
class Table<T : Any>(val name: String, val alias: String, val type: KClass<T>)

inline fun <reified T : Any> table(name: String, alias: String = name) = Table(name, alias, T::class)

fun <T : Any> from(table: Table<T>) = Query<T>(SelectModel(table))

inline fun <reified T : Any> from(table: String, alias: String = table) = from(table<T>(table, alias))

class Query<A> internal constructor(model: SelectModel) : QueryBase(model) {
    fun where(@Quoted predicate: (A) -> Boolean) = Query<A>(model.where(predicate))
    fun orderBy(@Quoted selector: (A) -> Comparable<*>?) = Query<A>(model.orderBy(selector, ascending = true))
    fun orderByDescending(@Quoted selector: (A) -> Comparable<*>?) = Query<A>(model.orderBy(selector, ascending = false))

    fun <R> select(@Quoted selector: (A) -> R) = SelectQuery<R>(model, selector.toExpression())

    fun <B : Any> join(table: Table<B>, @Quoted on: (A, B) -> Boolean) = Query2<A, B>(model.join("JOIN", table, on))
    fun <B : Any> leftJoin(table: Table<B>, @Quoted on: (A, B) -> Boolean) = Query2<A, B?>(model.join("LEFT JOIN", table, on))
}

class Query2<A, B> internal constructor(model: SelectModel) : QueryBase(model) {
    fun where(@Quoted predicate: (A, B) -> Boolean) = Query2<A, B>(model.where(predicate))
    fun orderBy(@Quoted selector: (A, B) -> Comparable<*>?) = Query2<A, B>(model.orderBy(selector, ascending = true))
    fun orderByDescending(@Quoted selector: (A, B) -> Comparable<*>?) = Query2<A, B>(model.orderBy(selector, ascending = false))

    fun <R> select(@Quoted selector: (A, B) -> R) = SelectQuery<R>(model, selector.toExpression())

    fun <C : Any> join(table: Table<C>, @Quoted on: (A, B, C) -> Boolean) = Query3<A, B, C>(model.join("JOIN", table, on))
    fun <C : Any> leftJoin(table: Table<C>, @Quoted on: (A, B, C) -> Boolean) = Query3<A, B, C?>(model.join("LEFT JOIN", table, on))
}

class Query3<A, B, C> internal constructor(model: SelectModel) : QueryBase(model) {
    fun where(@Quoted predicate: (A, B, C) -> Boolean) = Query3<A, B, C>(model.where(predicate))
    fun orderBy(@Quoted selector: (A, B, C) -> Comparable<*>?) = Query3<A, B, C>(model.orderBy(selector, ascending = true))
    fun orderByDescending(@Quoted selector: (A, B, C) -> Comparable<*>?) = Query3<A, B, C>(model.orderBy(selector, ascending = false))

    fun <R> select(@Quoted selector: (A, B, C) -> R) = SelectQuery<R>(model, selector.toExpression())
}

abstract class QueryBase internal constructor(internal val model: SelectModel) {
    /** SQL with `?` and the values already bound — rebuilt on every call. */
    fun toSql(policy: EvaluationPolicy = SqlEvaluation.Default): SqlStatement = model.toSql(projection = null, policy)

    /** SQL with `$1, $2, ...`: the text comes from [cache] by query shape; only the values are extracted again. */
    fun toCachedSql(cache: SqlCache = SqlCache.Default, policy: EvaluationPolicy = SqlEvaluation.Default): SqlStatement =
        cache.statement(model, projection = null, policy)
}

/**
 * A query with a projection — the result of `select { ... }`, like `IQueryable<R>` after `Select` in LINQ.
 * Columns are derived from the projection tree: constructor arguments, `a to b`, or a single expression.
 */
class SelectQuery<R> internal constructor(
    internal val model: SelectModel,
    internal val projection: LambdaExpression,
) {
    fun toSql(policy: EvaluationPolicy = SqlEvaluation.Default): SqlStatement = model.toSql(projection, policy)

    fun toCachedSql(cache: SqlCache = SqlCache.Default, policy: EvaluationPolicy = SqlEvaluation.Default): SqlStatement =
        cache.statement(model, projection, policy)
}

internal data class JoinClause(val kind: String, val table: Table<*>, val on: LambdaExpression)

internal data class SelectModel(
    val from: Table<*>,
    val joins: List<JoinClause> = emptyList(),
    val filters: List<LambdaExpression> = emptyList(),
    val order: List<Pair<LambdaExpression, Boolean>> = emptyList(),
) {
    fun where(predicate: Function<*>) = copy(filters = filters + predicate.toExpression())
    fun orderBy(selector: Function<*>, ascending: Boolean) = copy(order = order + (selector.toExpression() to ascending))
    fun join(kind: String, table: Table<*>, on: Function<*>): SelectModel {
        val aliases = listOf(from.alias) + joins.map { it.table.alias }
        require(table.alias !in aliases) { "Duplicate alias '${table.alias}': use table(name, alias) for self-joins" }
        return copy(joins = joins + JoinClause(kind, table, on.toExpression()))
    }

    /** All lambdas of the query in a fixed order — captured values are numbered in this order. */
    fun lambdas(projection: LambdaExpression?): List<LambdaExpression> =
        listOfNotNull(projection) + joins.map { it.on } + filters + order.map { it.first }

    fun toSql(projection: LambdaExpression?, policy: EvaluationPolicy): SqlStatement {
        val captures = Captures(lambdas(projection))
        val r = render(projection, Placeholders.JDBC, captures, policy)
        return SqlStatement(r.sql, r.slots.map { it.resolve(captures) }, r.columns)
    }

    fun render(projection: LambdaExpression?, style: Placeholders, captures: Captures, policy: EvaluationPolicy): Rendered {
        val sources = listOf(from) + joins.map { it.table }
        val translator = SqlTranslator(sources.map { it.alias }, qualify = sources.size > 1, style, captures, policy)
        // Translation order = text order, so that `?` line up with parameters.
        val columns = projection?.let { translator.projection(it) }
        val sql = buildString {
            append("SELECT ")
            append(columns?.joinToString(", ") { it.sql } ?: "*")
            append(" FROM ").append(source(from))
            for (join in joins) {
                append(' ').append(join.kind).append(' ').append(source(join.table))
                append(" ON ").append(translator.translate(join.on))
            }
            if (filters.isNotEmpty()) {
                append(" WHERE ")
                append(filters.joinToString(" AND ") { translator.translate(it, parenthesize = filters.size > 1) })
            }
            if (order.isNotEmpty()) {
                append(" ORDER BY ")
                append(order.joinToString(", ") { (e, asc) -> translator.translate(e) + if (asc) "" else " DESC" })
            }
        }
        return Rendered(sql, translator.slots, columns?.map { it.name }.orEmpty())
    }

    private fun source(table: Table<*>) = if (table.alias == table.name) table.name else "${table.name} ${table.alias}"
}

internal class Rendered(val sql: String, val slots: List<ValueSource>, val columns: List<String>)

/** [columns] are the result column names for a projection (empty for `SELECT *`). */
data class SqlStatement(val sql: String, val parameters: List<Any?>, val columns: List<String> = emptyList())

internal data class Column(val sql: String, val name: String)

/**
 * A database function called from Kotlin: `@SqlFunction("datetime") fun dateTime(d: LocalDate, t: LocalTime)`
 * translates to `datetime(<d>, <t>)`. The call is never evaluated on the client (see [Translatable]);
 * the arguments are translated as usual — columns, parameters, nested calls.
 */
@Translatable
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class SqlFunction(val name: String, val schema: String = "")

/** [EvaluableExpression] evaluation policies for SQL. */
object SqlEvaluation {
    /**
     * Arithmetic and logic over values (`minAge + 50`) stay in SQL, as parameters: `$1 + 50`;
     * everything else (properties and function calls on captured objects) is evaluated on the client.
     */
    val Default = EvaluationPolicy { node ->
        !node.original.walk().all {
            it is BinaryExpression || it is UnaryExpression || it is ConstantExpression || it is CapturedExpression
        }
    }
}

internal enum class Placeholders {
    /** `?`; values the text depends on (`IN` lists, `LIKE` patterns) are expanded in place. */
    JDBC,

    /** `$1, $2, ...`; the text doesn't depend on values: `= ANY($1)`, `LIKE '%' || $1`. One variable, one number. */
    NUMBERED,
}

private class SqlTranslator(
    private val aliases: List<String>,
    private val qualify: Boolean,
    private val style: Placeholders,
    private val captures: Captures,
    private val policy: EvaluationPolicy,
) {
    /** Where to take the value for each placeholder, in order. */
    val slots = mutableListOf<ValueSource>()

    /** Lambda parameter → table alias (by position). */
    private var scope: Map<ParameterExpression, String> = emptyMap()

    /**
     * SELECT columns:
     *  - `UserOrder(name = u.name, total = o.total * 2)` → `u.name, o.total * 2 AS total`;
     *  - `u.name to o.total` → `u.name AS first, o.total AS second`;
     *  - `o` (the whole row) → `o.*`;
     *  - any other expression → a single column.
     */
    fun projection(lambda: LambdaExpression): List<Column> {
        scope = lambda.parameters.zip(aliases).toMap()
        return when (val body = lambda.body) {
            is NewExpression -> body.arguments.zip(body.parameterNames) { arg, name -> column(arg, name) }
            is CallExpression if body.function == "to" && body.isExtension && body.receiver != null ->
                listOf(column(body.receiver!!, "first"), column(body.arguments.single(), "second"))
            is ParameterExpression -> listOf(Column(qualified(scope[body] ?: unsupported(body), "*"), "*"))
            is MemberExpression -> listOf(column(body, body.member))
            else -> listOf(column(body, "value"))
        }
    }

    private fun column(e: Expression, name: String): Column {
        if (e is NewExpression || e is ParameterExpression) unsupported(e) // nested objects are not flat
        val sql = visit(e)
        val alias = name.toSnakeCase()
        // `u.name` named `name` needs no AS.
        val sameName = e is MemberExpression && e.receiver is ParameterExpression && e.member.toSnakeCase() == alias
        return Column(if (sameName) sql else "$sql AS ${identifier(alias)}", alias)
    }

    fun translate(lambda: LambdaExpression, parenthesize: Boolean = false): String {
        scope = lambda.parameters.zip(aliases).toMap()
        val e = lambda.body
        val sql = visit(e)
        return if (parenthesize && e is BinaryExpression && e.operator == BinaryOperator.OR) "($sql)" else sql
    }

    private fun visit(e: Expression): String = when (e) {
        is MemberExpression -> column(e)
        // Captured variables become query parameters — protection against SQL injection.
        is CapturedExpression -> bind(valueSource(e))
        is ConstantExpression -> literal(e.value)
        is BinaryExpression -> binary(e)
        is UnaryExpression -> when (e.operator) {
            UnaryOperator.NOT -> "NOT (${visit(e.operand)})"
            UnaryOperator.MINUS -> "-${visit(e.operand)}"
            else -> unsupported(e)
        }
        is CallExpression -> call(e)
        is ConditionalExpression -> conditional(e)
        // A subtree without lambda parameters: evaluate on the client (→ parameter) or translate its structure.
        is EvaluableExpression -> if (policy.shouldEvaluate(e)) bind(valueSource(e)) else visit(e.original)
        else -> unsupported(e)
    }

    /**
     * `o?.total` compiles to `if (o == null) null else o.total` — in SQL, NULL propagates on its own,
     * so the check can be dropped (including in chains like `p?.title?.length`).
     * Other `if`s become `CASE WHEN`.
     */
    private fun conditional(e: ConditionalExpression): String {
        val test = e.test
        if (test is BinaryExpression && test.operator == BinaryOperator.EQUAL &&
            test.right.isNullConstant() && e.ifTrue.isNullConstant()
        ) {
            return visit(e.ifFalse)
        }
        return "CASE WHEN ${visit(test)} THEN ${visit(e.ifTrue)} ELSE ${visit(e.ifFalse)} END"
    }

    private fun binary(e: BinaryExpression): String {
        // `x == null` → `x IS NULL`
        if (e.operator == BinaryOperator.EQUAL || e.operator == BinaryOperator.NOT_EQUAL) {
            val isNull = if (e.operator == BinaryOperator.EQUAL) "IS NULL" else "IS NOT NULL"
            if (e.right.isNullConstant()) return "${nullable(e.left)} $isNull"
            if (e.left.isNullConstant()) return "${nullable(e.right)} $isNull"
            // `x == true` → `x`
            if (e.operator == BinaryOperator.EQUAL && (e.right as? ConstantExpression)?.value == true) return visit(e.left)
        }
        val op = when (e.operator) {
            BinaryOperator.EQUAL -> "="
            BinaryOperator.NOT_EQUAL -> "<>"
            BinaryOperator.LESS -> "<"
            BinaryOperator.LESS_OR_EQUAL -> "<="
            BinaryOperator.GREATER -> ">"
            BinaryOperator.GREATER_OR_EQUAL -> ">="
            BinaryOperator.AND -> "AND"
            BinaryOperator.OR -> "OR"
            BinaryOperator.PLUS -> if (e.type == "kotlin.String") "||" else "+"
            BinaryOperator.MINUS -> "-"
            BinaryOperator.TIMES -> "*"
            BinaryOperator.DIV -> "/"
            BinaryOperator.REM -> "%"
            BinaryOperator.ELVIS -> return "COALESCE(${visit(e.left)}, ${visit(e.right)})"
            else -> unsupported(e)
        }
        return "${operand(e.left, e.operator)} $op ${operand(e.right, e.operator, isRight = true)}"
    }

    /** `o == null` for a row from a LEFT JOIN means "no matching row"; check its `id` key. */
    private fun nullable(e: Expression): String =
        if (e is ParameterExpression) qualified(scope[e] ?: unsupported(e), "id") else visit(e)

    private fun operand(e: Expression, parent: BinaryOperator, isRight: Boolean = false): String {
        val sql = visit(e)
        // Operators are left-associative: `a - (b - c)` needs parentheses on the right at equal precedence.
        val needsParens = e is BinaryExpression &&
            (precedence(e.operator) < precedence(parent) || isRight && precedence(e.operator) == precedence(parent))
        return if (needsParens) "($sql)" else sql
    }

    private fun precedence(op: BinaryOperator) = when (op) {
        BinaryOperator.OR -> 1
        BinaryOperator.AND -> 2
        BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL, BinaryOperator.LESS, BinaryOperator.LESS_OR_EQUAL,
        BinaryOperator.GREATER, BinaryOperator.GREATER_OR_EQUAL -> 3
        BinaryOperator.PLUS, BinaryOperator.MINUS -> 4
        else -> 5
    }

    private fun call(e: CallExpression): String {
        e.annotation<SqlFunction>()?.let { fn ->
            val name = if (fn.schema.isEmpty()) fn.name else "${fn.schema}.${fn.name}"
            // The dispatch receiver (object, companion, class instance) is just a Kotlin namespace and is not
            // passed to SQL. The receiver of an extension function (including member extensions) is the first argument.
            val args = listOfNotNull(e.extensionReceiver) + e.arguments
            return "$name(${args.joinToString { visit(it) }})"
        }
        val receiver = e.receiver ?: unsupported(e)
        return when (e.function) {
            "startsWith" -> "${visit(receiver)} LIKE ${likePattern(e.arguments.single(), suffix = "%")}"
            "endsWith" -> "${visit(receiver)} LIKE ${likePattern(e.arguments.single(), prefix = "%")}"
            "contains" ->
                if (receiver.type == "kotlin.String") {
                    "${visit(receiver)} LIKE ${likePattern(e.arguments.single(), prefix = "%", suffix = "%")}"
                } else {
                    val values = valueSource(receiver)
                    val column = visit(e.arguments.single())
                    when (style) {
                        // `it.id in ids` → `id IN (?, ?, ?)` — the number of `?` depends on the list size.
                        Placeholders.JDBC -> {
                            val list = values.resolve(captures) as? Iterable<*> ?: unsupported(e)
                            "$column IN (${list.joinToString { bind(ValueSource.Literal(it)) }})"
                        }
                        // `it.id in ids` → `id = ANY($1)` — a single array parameter, the text is stable.
                        Placeholders.NUMBERED -> "$column = ANY(${bind(ValueSource.AsArray(values))})"
                    }
                }
            "lowercase" -> "LOWER(${visit(receiver)})"
            "uppercase" -> "UPPER(${visit(receiver)})"
            else -> unsupported(e)
        }
    }

    private fun likePattern(arg: Expression, prefix: String = "", suffix: String = ""): String {
        if (arg is ConstantExpression) {
            val pattern = "$prefix${arg.value}$suffix"
            return if (style == Placeholders.JDBC) bind(ValueSource.Literal(pattern)) else literal(pattern)
        }
        val value = valueSource(arg)
        return when (style) {
            Placeholders.JDBC -> bind(ValueSource.Pattern(prefix, value, suffix))
            // Concatenate in SQL, not in the value: `$1` stays the variable's plain value.
            Placeholders.NUMBERED -> listOfNotNull(
                prefix.takeIf { it.isNotEmpty() }?.let(::literal),
                bind(value),
                suffix.takeIf { it.isNotEmpty() }?.let(::literal),
            ).joinToString(" || ")
        }
    }

    private fun column(e: MemberExpression): String {
        // Properties of captured objects arrive as EvaluableExpression; only hand-built trees end up here,
        // and there is no way to evaluate them without reflection.
        val receiver = e.receiver as? ParameterExpression ?: unsupported(e)
        return qualified(scope[receiver] ?: unsupported(e), e.member.toSnakeCase())
    }

    /** Aliases that are keywords (`second`, `value`, `order`, ...) are quoted. */
    private fun identifier(name: String) = if (name.uppercase() in RESERVED) "\"$name\"" else name

    private fun qualified(alias: String, column: String) = if (qualify) "$alias.$column" else column

    /**
     * A value recipe: a captured variable or an evaluator generated by the compiler
     * (`filter.minAge`, `repo.activeIds()`) — no reflection.
     */
    private fun valueSource(e: Expression): ValueSource = when (e) {
        is CapturedExpression -> ValueSource.Capture(captures.indexOf(e))
        is EvaluableExpression -> ValueSource.Evaluate(captures.indexOf(e))
        else -> unsupported(e)
    }

    private fun bind(source: ValueSource): String = when (style) {
        Placeholders.JDBC -> {
            slots += ValueSource.Literal(source.resolve(captures))
            "?"
        }
        Placeholders.NUMBERED -> {
            // The same variable gets the same number.
            val index = slots.indexOf(source).takeIf { it >= 0 } ?: slots.size.also { slots += source }
            "$${index + 1}"
        }
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "NULL"
        is String -> "'${value.replace("'", "''")}'"
        is Boolean -> if (value) "TRUE" else "FALSE"
        is Enum<*> -> "'${value.name}'"
        else -> value.toString()
    }

    private fun Expression.isNullConstant() = this is ConstantExpression && value == null

    private fun String.toSnakeCase() = replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    private fun unsupported(e: Expression): Nothing =
        throw UnsupportedOperationException("Cannot translate to SQL: $e (${e::class.simpleName})")

    companion object {
        private val RESERVED = setOf(
            "ALL", "AND", "ANY", "AS", "BETWEEN", "CASE", "CHECK", "DAY", "DEFAULT", "DISTINCT", "END", "FIRST",
            "FROM", "GROUP", "HOUR", "IN", "IS", "JOIN", "KEY", "LAST", "LEFT", "LIMIT", "MINUTE", "MONTH", "NOT",
            "NULL", "OFFSET", "ON", "OR", "ORDER", "RIGHT", "SECOND", "SELECT", "TABLE", "TO", "UNION", "USER",
            "VALUE", "VALUES", "WHEN", "WHERE", "YEAR",
        )
    }
}
