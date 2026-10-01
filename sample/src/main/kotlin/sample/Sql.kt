package sample

import kexpression.*
import kotlin.reflect.KClass

/**
 * Мини-LINQ-провайдер: предикаты пишутся обычными Kotlin-лямбдами, а превращаются в SQL
 * по дереву выражения — как `IQueryable<T>.Where(Expression<Func<T, bool>>)` в C#.
 *
 * Join'ы типизированы по числу таблиц: `Query<A>` → `Query2<A, B>` → `Query3<A, B, C>`.
 * i-й параметр любой лямбды соответствует i-й таблице запроса, поэтому
 * `{ u, o -> u.id == o.userId }` транслируется в `u.id = o.user_id`.
 * В `leftJoin` условие ON видит таблицу как non-null, а последующие лямбды — как nullable.
 */
/** Таблица и класс её строк — нужен, чтобы материализовать результат в `toList()`. */
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
    /** SQL с `?` и уже подставленными значениями — строится заново при каждом вызове. */
    fun toSql(): SqlStatement = model.toSql(projection = null)

    /** SQL с `$1, $2, ...`: текст берётся из [cache] по форме запроса, заново извлекаются только значения. */
    fun toCachedSql(cache: SqlCache = SqlCache.Default): SqlStatement = cache.statement(model, projection = null)
}

/**
 * Запрос с проекцией — результат `select { ... }`, аналог `IQueryable<R>` после `Select` в LINQ.
 * Колонки выводятся из дерева проекции: аргументы конструктора, `a to b` или одно выражение.
 */
class SelectQuery<R> internal constructor(
    internal val model: SelectModel,
    internal val projection: LambdaExpression,
) {
    fun toSql(): SqlStatement = model.toSql(projection)

    fun toCachedSql(cache: SqlCache = SqlCache.Default): SqlStatement = cache.statement(model, projection)
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

    /** Все лямбды запроса в фиксированном порядке — по нему нумеруются захваченные значения. */
    fun lambdas(projection: LambdaExpression?): List<LambdaExpression> =
        listOfNotNull(projection) + joins.map { it.on } + filters + order.map { it.first }

    fun toSql(projection: LambdaExpression?): SqlStatement {
        val captures = Captures(lambdas(projection))
        val r = render(projection, Placeholders.JDBC, captures)
        return SqlStatement(r.sql, r.slots.map { it.resolve(captures.values) }, r.columns)
    }

    fun render(projection: LambdaExpression?, style: Placeholders, captures: Captures): Rendered {
        val sources = listOf(from) + joins.map { it.table }
        val translator = SqlTranslator(sources.map { it.alias }, qualify = sources.size > 1, style, captures)
        // Порядок трансляции = порядок в тексте, чтобы `?` совпадали с parameters.
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

/** [columns] — имена колонок результата для проекции (пусто для `SELECT *`). */
data class SqlStatement(val sql: String, val parameters: List<Any?>, val columns: List<String> = emptyList())

internal data class Column(val sql: String, val name: String)

internal enum class Placeholders {
    /** `?`; значения, от которых зависит текст (`IN`-списки, шаблоны `LIKE`), разворачиваются на месте. */
    JDBC,

    /** `$1, $2, ...`; текст не зависит от значений: `= ANY($1)`, `LIKE '%' || $1`. Одна переменная — один номер. */
    NUMBERED,
}

private class SqlTranslator(
    private val aliases: List<String>,
    private val qualify: Boolean,
    private val style: Placeholders,
    private val captures: Captures,
) {
    /** Откуда брать значение для каждого плейсхолдера, по порядку. */
    val slots = mutableListOf<ValueSource>()

    /** Параметр лямбды → алиас таблицы (по позиции). */
    private var scope: Map<ParameterExpression, String> = emptyMap()

    /**
     * Колонки SELECT:
     *  - `UserOrder(name = u.name, total = o.total * 2)` → `u.name, o.total * 2 AS total`;
     *  - `u.name to o.total` → `u.name AS first, o.total AS second`;
     *  - `o` (вся строка) → `o.*`;
     *  - любое другое выражение → одна колонка.
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
        if (e is NewExpression || e is ParameterExpression) unsupported(e) // вложенные объекты не плоские
        val sql = visit(e)
        val alias = name.toSnakeCase()
        // `u.name` под именем `name` не требует AS.
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
        // Захваченные переменные становятся параметрами запроса — защита от SQL-инъекций.
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
        else -> unsupported(e)
    }

    /**
     * `o?.total` компилируется в `if (o == null) null else o.total` — в SQL NULL распространяется
     * сам, поэтому проверку можно отбросить (в т.ч. в цепочках `p?.title?.length`).
     * Остальные `if` становятся `CASE WHEN`.
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

    /** `o == null` для строки из LEFT JOIN — «строка не найдена», проверяем её ключ `id`. */
    private fun nullable(e: Expression): String =
        if (e is ParameterExpression) qualified(scope[e] ?: unsupported(e), "id") else visit(e)

    private fun operand(e: Expression, parent: BinaryOperator, isRight: Boolean = false): String {
        val sql = visit(e)
        // Операторы левоассоциативны: `a - (b - c)` требует скобок справа при равном приоритете.
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
                        // `it.id in ids` → `id IN (?, ?, ?)` — число `?` зависит от размера списка.
                        Placeholders.JDBC -> {
                            val list = values.resolve(captures.values) as? Iterable<*> ?: unsupported(e)
                            "$column IN (${list.joinToString { bind(ValueSource.Literal(it)) }})"
                        }
                        // `it.id in ids` → `id = ANY($1)` — один параметр-массив, текст стабилен.
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
            // Склейка в SQL, а не в значении: `$1` остаётся «чистым» значением переменной.
            Placeholders.NUMBERED -> listOfNotNull(
                prefix.takeIf { it.isNotEmpty() }?.let(::literal),
                bind(value),
                suffix.takeIf { it.isNotEmpty() }?.let(::literal),
            ).joinToString(" || ")
        }
    }

    private fun column(e: MemberExpression): String {
        val receiver = e.receiver as? ParameterExpression ?: return bind(valueSource(e))
        return qualified(scope[receiver] ?: unsupported(e), e.member.toSnakeCase())
    }

    /** Алиасы-ключевые слова (`second`, `value`, `order`, ...) берутся в кавычки. */
    private fun identifier(name: String) = if (name.uppercase() in RESERVED) "\"$name\"" else name

    private fun qualified(alias: String, column: String) = if (qualify) "$alias.$column" else column

    /**
     * Рецепт значения: захваченная переменная или цепочка её свойств (`filter.range.min`).
     * Свойства захваченных объектов вычисляются на клиенте, как это делает EF Core.
     */
    private fun valueSource(e: Expression): ValueSource = when (e) {
        is CapturedExpression -> ValueSource.Capture(captures.indexOf(e))
        is MemberExpression -> ValueSource.Member(valueSource(e.receiver ?: unsupported(e)), e.member)
        else -> unsupported(e)
    }

    private fun bind(source: ValueSource): String = when (style) {
        Placeholders.JDBC -> {
            slots += ValueSource.Literal(source.resolve(captures.values))
            "?"
        }
        Placeholders.NUMBERED -> {
            // Одна и та же переменная получает один номер.
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
