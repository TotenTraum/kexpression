package sample

import kexpression.*
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Кэш SQL по *форме* запроса — как compiled query cache в EF Core.
 *
 * Плагин строит одинаковое дерево при каждом выполнении одной и той же строки кода; меняются только
 * значения в [CapturedExpression]. Поэтому:
 *  1. ключ кэша — запрос, в деревьях которого значения захваченных переменных стёрты;
 *  2. в кэше лежит текст SQL с `$1, $2, ...` и рецепт для каждого номера ([ValueSource]);
 *  3. при попадании в кэш SQL не транслируется: по рецептам из нового дерева извлекаются значения.
 *
 * Текст с `$n` не зависит от значений, поэтому и сервер БД может переиспользовать
 * подготовленный statement / план запроса.
 */
class SqlCache(private val maxSize: Int = 1_000) {
    private val entries = ConcurrentHashMap<Any, Rendered>()
    private val hitCount = AtomicLong()
    private val missCount = AtomicLong()

    val hits: Long get() = hitCount.get()
    val misses: Long get() = missCount.get()
    val size: Int get() = entries.size

    internal fun statement(model: SelectModel, projection: LambdaExpression?): SqlStatement {
        val captures = Captures(model.lambdas(projection))
        val key = shape(model, projection)
        val rendered = entries[key]?.also { hitCount.incrementAndGet() } ?: run {
            missCount.incrementAndGet()
            model.render(projection, Placeholders.NUMBERED, captures).also {
                if (entries.size < maxSize) entries.putIfAbsent(key, it)
            }
        }
        return SqlStatement(rendered.sql, rendered.slots.map { it.resolve(captures.values) }, rendered.columns)
    }

    fun clear() {
        entries.clear()
        hitCount.set(0)
        missCount.set(0)
    }

    companion object {
        val Default = SqlCache()

        /** Всё, от чего зависит текст SQL: таблицы, виды join'ов, направления сортировки и деревья без значений. */
        private fun shape(model: SelectModel, projection: LambdaExpression?): Any = listOf(
            model.from.name, model.from.alias,
            model.joins.map { listOf(it.kind, it.table.name, it.table.alias) },
            model.order.map { it.second },
            projection != null,
            model.lambdas(projection).map { it.eraseCapturedValues() },
        )

        private fun Expression.eraseCapturedValues(): Expression =
            rewrite { if (it is CapturedExpression) it.copy(value = null) else null }
    }
}

/**
 * Захваченные значения всех лямбд запроса в каноническом порядке (лямбды по [SelectModel.lambdas],
 * внутри — обход в глубину). Для одинаковой формы запроса порядок всегда одинаков, поэтому
 * индексы из закэшированных рецептов подходят к любому новому дереву той же формы.
 */
internal class Captures(lambdas: List<LambdaExpression>) {
    val values = mutableListOf<Any?>()
    private val indices = IdentityHashMap<CapturedExpression, Int>()

    init {
        lambdas.forEachIndexed { lambdaIndex, lambda ->
            // Внутри одной лямбды одно имя = одна переменная (локальные val подставлены, параметры — не захваты).
            val byName = mutableMapOf<String, Int>()
            for (node in lambda.walk()) {
                if (node !is CapturedExpression) continue
                indices[node] = byName.getOrPut(node.name) { values.size.also { values += node.value } }
            }
        }
    }

    fun indexOf(e: CapturedExpression): Int = indices[e] ?: error("Unknown captured value: $e")
}

/** Как получить значение параметра запроса из захваченных значений. */
internal sealed interface ValueSource {
    fun resolve(captures: List<Any?>): Any?

    data class Capture(val index: Int) : ValueSource {
        override fun resolve(captures: List<Any?>) = captures[index]
    }

    data class Member(val receiver: ValueSource, val name: String) : ValueSource {
        override fun resolve(captures: List<Any?>): Any? {
            val target = receiver.resolve(captures) ?: return null
            val getter = target.javaClass.methods.first {
                it.parameterCount == 0 && (it.name == "get" + name.replaceFirstChar(Char::uppercase) || it.name == name)
            }
            return getter.invoke(target)
        }
    }

    /** Коллекция → массив для `= ANY($n)`. */
    data class AsArray(val source: ValueSource) : ValueSource {
        override fun resolve(captures: List<Any?>): Any? = when (val v = source.resolve(captures)) {
            is Collection<*> -> v.toTypedArray()
            else -> v
        }
    }

    data class Pattern(val prefix: String, val source: ValueSource, val suffix: String) : ValueSource {
        override fun resolve(captures: List<Any?>) = "$prefix${source.resolve(captures)}$suffix"
    }

    data class Literal(val value: Any?) : ValueSource {
        override fun resolve(captures: List<Any?>) = value
    }
}
