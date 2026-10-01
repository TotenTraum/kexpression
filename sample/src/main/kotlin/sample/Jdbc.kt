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
 * Выполнение запросов через JDBC — аналог `ToList()` / `AsEnumerable()` в LINQ.
 *
 * SQL берётся из SqlCache (форма запроса → текст с `$n`), `$n` переводятся в JDBC-шные `?`,
 * а строки результата собираются в объекты по дереву проекции:
 *  - `from<User>(...)`                     → User по именам колонок (`order_total` → `orderTotal`);
 *  - `select { u, o -> UserOrder(...) }`   → конструктор UserOrder, аргументы — колонки по порядку;
 *  - `select { it.name to it.age }`        → Pair;
 *  - `select { _, o -> o }`                → Order из колонок `o.*`;
 *  - `select { it.email }`                 → одно значение.
 */

fun <A : Any> Query<A>.toSequence(connection: Connection, cache: SqlCache = SqlCache.Default): ResultSequence<A> {
    // Query<A> создаётся только из Table<A>, так что тип строки — A.
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
 * Ленивый результат запроса. Запрос выполняется при начале итерации, строки читаются по одной.
 *
 * Обычный `Sequence` нельзя закрыть: если не дочитать его до конца, `ResultSet` останется открытым.
 * Поэтому последовательность — ещё и [AutoCloseable] (как `File.useLines`):
 * ```
 * query.toSequence(conn).use { rows -> rows.filter { ... }.take(10).toList() }
 * ```
 * Если дочитать до конца, ресурсы закрываются сами. Итерировать можно один раз.
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

    /** Закрывает statement (и вместе с ним ResultSet). Повторный вызов безопасен. */
    override fun close() = prepared.close()
}

/**
 * `$1, $2, $1` → `?, ?, ?` с развёрнутым списком параметров: JDBC понимает только позиционные `?`.
 * Строковые литералы пропускаются, чтобы не тронуть `'$1'` внутри них.
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

// --- материализация ---

/** Сущность из строки по именам колонок: параметр конструктора `orderTotal` ← колонка `order_total`. */
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

/** Читатель строки, построенный по дереву проекции — колонки идут в том же порядке, что и в SELECT. */
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

/** `callBy` — чтобы пропущенные в проекции параметры получили значения по умолчанию. */
private fun <T> construct(constructor: KFunction<T>, values: List<Pair<KParameter, Any?>>): T =
    constructor.callBy(values.associate { (p, v) -> p to convert(v, p.type.classifier as KClass<*>) })

/** JDBC-значение → тип Kotlin: числа приводятся по ширине, строки — в enum. */
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

/** Имя типа из дерева (`kotlin.Int?`, `sample.SelectTest.Row`) → KClass. */
private fun classOf(typeName: String): KClass<*> {
    val name = typeName.removeSuffix("?")
    kotlinTypes[name]?.let { return it }
    // Вложенные классы: `a.B.C` → `a.B$C`.
    var candidate = name
    while (true) {
        runCatching { return Class.forName(candidate).kotlin }
        val dot = candidate.lastIndexOf('.')
        if (dot < 0) throw ClassNotFoundException(name)
        candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1)
    }
}

private fun String.toSnakeCase() = replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
