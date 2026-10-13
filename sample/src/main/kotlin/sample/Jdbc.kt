package sample

import kexpression.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/*
 * Query execution over JDBC — the counterpart of `ToList()` / `AsEnumerable()` in LINQ.
 *
 * SQL comes from SqlCache (query shape → text with `$n`), `$n` are turned into JDBC `?`,
 * and result rows are assembled into objects according to the projection tree:
 *  - `from<User>(...)`                     → User by column names (`order_total` → `orderTotal`);
 *  - `select { u, o -> UserOrder(...) }`   → the UserOrder constructor, arguments are the columns in order;
 *  - `select { it.name to it.age }`        → Pair;
 *  - `select { _, o -> o }`                → Order from the `o.*` columns;
 *  - `select { it.email }`                 → a single value.
 */

fun <A : Any> Query<A>.toSequence(connection: Connection, cache: SqlCache = SqlCache.Default): ResultSequence<A> {
    // Query<A> is only created from Table<A>, so the row type is A.
    @Suppress("UNCHECKED_CAST")
    val reader = EntityReader(model.from.type as KClass<A>)
    return ResultSequence(connection, toCachedSql(cache), reader::read)
}

fun <A : Any> Query<A>.toList(connection: Connection, cache: SqlCache = SqlCache.Default): List<A> =
    toSequence(connection, cache).use { it.toList() }

fun <R> SelectQuery<R>.toSequence(connection: Connection, cache: SqlCache = SqlCache.Default): ResultSequence<R> {
    val types = (listOf(model.from) + model.joins.map { it.table }).map { it.type }
    val reader = projectionReader(projection, types)
    @Suppress("UNCHECKED_CAST")
    return ResultSequence(connection, toCachedSql(cache)) { rs -> reader(rs) as R }
}

fun <R> SelectQuery<R>.toList(connection: Connection, cache: SqlCache = SqlCache.Default): List<R> =
    toSequence(connection, cache).use { it.toList() }

/**
 * A lazy query result. The query runs when iteration starts, and rows are read one at a time.
 *
 * A plain `Sequence` can't be closed: if it isn't read to the end, the `ResultSet` stays open.
 * That's why this sequence is also [AutoCloseable] (like `File.useLines`):
 * ```
 * query.toSequence(conn).use { rows -> rows.filter { ... }.take(10).toList() }
 * ```
 * Reading it to the end closes the resources automatically. It can be iterated only once.
 */
class ResultSequence<T> internal constructor(
    connection: Connection,
    statement: SqlStatement,
    private val read: (ResultSet) -> T,
) : Sequence<T>, AutoCloseable {
    val sql: SqlStatement = statement.toJdbc()
    private val prepared: PreparedStatement = connection.prepareStatement(sql.sql).also { ps ->
        sql.parameters.forEachIndexed { i, value -> ps.setObject(i + 1, toJdbcValue(connection, value)) }
    }
    private var iterated = false

    override fun iterator(): Iterator<T> {
        check(!iterated) { "ResultSequence can be iterated only once" }
        iterated = true
        val rs = prepared.executeQuery()
        return object : Iterator<T> {
            private var hasRow: Boolean? = null

            override fun hasNext(): Boolean {
                if (hasRow == null) hasRow = rs.next().also { if (!it) close() }
                return hasRow!!
            }

            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()
                hasRow = null
                return read(rs)
            }
        }
    }

    /** Closes the statement (and the ResultSet with it). Calling it again is safe. */
    override fun close() = prepared.close()
}

/**
 * `$1, $2, $1` → `?, ?, ?` with the parameter list expanded: JDBC only understands positional `?`.
 * String literals are skipped so that `'$1'` inside them is left untouched.
 */
fun SqlStatement.toJdbc(): SqlStatement {
    val params = mutableListOf<Any?>()
    val out = StringBuilder()
    var i = 0
    var inString = false
    while (i < sql.length) {
        val c = sql[i]
        if (c == '\'') inString = !inString
        if (!inString && c == '$' && i + 1 < sql.length && sql[i + 1].isDigit()) {
            var j = i + 1
            while (j < sql.length && sql[j].isDigit()) j++
            params += parameters[sql.substring(i + 1, j).toInt() - 1]
            out.append('?')
            i = j
            continue
        }
        out.append(c)
        i++
    }
    return copy(sql = out.toString(), parameters = params)
}

private fun toJdbcValue(connection: Connection, value: Any?): Any? = when (value) {
    is Enum<*> -> value.name
    is Array<*> -> connection.createArrayOf(sqlArrayType(value), value.map { toJdbcValue(connection, it) }.toTypedArray())
    else -> value
}

private fun sqlArrayType(array: Array<*>) = when (array.firstOrNull()) {
    is Long -> "BIGINT"
    is Int -> "INTEGER"
    is Double -> "DOUBLE PRECISION"
    is Boolean -> "BOOLEAN"
    else -> "VARCHAR"
}

// --- materialization ---

/** An entity from a row by column names: constructor parameter `orderTotal` ← column `order_total`. */
private class EntityReader<T : Any>(type: KClass<T>) {
    private val constructor = constructorOf(type)

    fun read(rs: ResultSet): T {
        val meta = rs.metaData
        val byLabel = (1..meta.columnCount).associateBy { meta.getColumnLabel(it).lowercase() }
        return construct(constructor, constructor.parameters.mapNotNull { p ->
            byLabel[p.name!!.toSnakeCase()]?.let { p to rs.getObject(it) }
        })
    }
}

/** A row reader built from the projection tree — columns come in the same order as in the SELECT. */
private fun projectionReader(projection: LambdaExpression, tables: List<KClass<*>>): (ResultSet) -> Any? =
    when (val body = projection.body) {
        is NewExpression -> {
            val constructor = constructorOf(classOf(body.type))
            val params = body.parameterNames.map { name -> constructor.parameters.first { it.name == name } }
            val reader: (ResultSet) -> Any? = { rs ->
                construct(constructor, params.mapIndexed { i, p -> p to rs.getObject(i + 1) })
            }
            reader
        }
        is CallExpression if body.function == "to" && body.isExtension -> {
            val first = classOf(body.receiver!!.type)
            val second = classOf(body.arguments.single().type)
            val reader: (ResultSet) -> Any? = { rs -> convert(rs.getObject(1), first) to convert(rs.getObject(2), second) }
            reader
        }
        is ParameterExpression -> EntityReader(tables[projection.parameters.indexOf(body)])::read
        else -> {
            val type = classOf(body.type)
            val reader: (ResultSet) -> Any? = { rs -> convert(rs.getObject(1), type) }
            reader
        }
    }

private val constructors = ConcurrentHashMap<KClass<*>, KFunction<*>>()

@Suppress("UNCHECKED_CAST")
private fun <T : Any> constructorOf(type: KClass<T>): KFunction<T> =
    constructors.getOrPut(type) {
        type.primaryConstructor ?: throw IllegalArgumentException("$type has no primary constructor")
    } as KFunction<T>

/** `callBy` so that parameters omitted in the projection get their default values. */
private fun <T> construct(constructor: KFunction<T>, values: List<Pair<KParameter, Any?>>): T =
    constructor.callBy(values.associate { (p, v) -> p to convert(v, p.type.classifier as KClass<*>) })

/** JDBC value → Kotlin type: numbers are converted by width, strings to enums. */
private fun convert(value: Any?, target: KClass<*>): Any? = when {
    value == null || target.isInstance(value) -> value
    value is Number -> when (target) {
        Int::class -> value.toInt()
        Long::class -> value.toLong()
        Short::class -> value.toShort()
        Byte::class -> value.toByte()
        Double::class -> value.toDouble()
        Float::class -> value.toFloat()
        else -> value
    }
    value is String && target.java.isEnum ->
        target.java.enumConstants.first { (it as Enum<*>).name == value }
    else -> value
}

private val kotlinTypes: Map<String, KClass<*>> = listOf(
    Int::class, Long::class, Short::class, Byte::class, Double::class, Float::class,
    Boolean::class, Char::class, String::class, Any::class,
).associateBy { it.qualifiedName!! }

/** A type name from the tree (`kotlin.Int?`, `sample.SelectTest.Row`) → KClass. */
private fun classOf(typeName: String): KClass<*> {
    val name = typeName.removeSuffix("?")
    kotlinTypes[name]?.let { return it }
    // Nested classes: `a.B.C` → `a.B$C`.
    var candidate = name
    while (true) {
        runCatching { return Class.forName(candidate).kotlin }
        val dot = candidate.lastIndexOf('.')
        if (dot < 0) throw ClassNotFoundException(name)
        candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1)
    }
}

private fun String.toSnakeCase() = replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
