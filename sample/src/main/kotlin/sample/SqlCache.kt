@file:Suppress("UNUSED")
package sample

import kexpression.*
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A SQL cache keyed by query *shape* — like the compiled query cache in EF Core.
 *
 * The plugin builds the same tree every time the same line of code runs; only the values in
 * [CapturedExpression] change. Therefore:
 *  1. the cache key is the query with the captured values erased from its trees;
 *  2. the cache holds the SQL text with `$1, $2, ...` and a recipe for each number ([ValueSource]);
 *  3. on a cache hit no SQL is translated: the recipes extract the values from the new tree.
 *
 * Text with `$n` doesn't depend on values, so the database server can reuse
 * the prepared statement / query plan as well.
 */
class SqlCache(private val maxSize: Int = 1_000) {
    private val entries = ConcurrentHashMap<Any, Rendered>()
    private val hitCount = AtomicLong()
    private val missCount = AtomicLong()

    val hits: Long get() = hitCount.get()
    val misses: Long get() = missCount.get()
    val size: Int get() = entries.size

    internal fun statement(model: SelectModel, projection: LambdaExpression?, policy: EvaluationPolicy): SqlStatement {
        val captures = Captures(model.lambdas(projection))
        val key = shape(model, projection, policy)
        val rendered = entries[key]?.also { hitCount.incrementAndGet() } ?: run {
            missCount.incrementAndGet()
            model.render(projection, Placeholders.NUMBERED, captures, policy).also {
                if (entries.size < maxSize) entries.putIfAbsent(key, it)
            }
        }
        return SqlStatement(rendered.sql, rendered.slots.map { it.resolve(captures) }, rendered.columns)
    }

    fun clear() {
        entries.clear()
        hitCount.set(0)
        missCount.set(0)
    }

    companion object {
        val Default = SqlCache()

        /** Everything the SQL text depends on: tables, join kinds, ordering, the policy, and the trees without values. */
        private fun shape(model: SelectModel, projection: LambdaExpression?, policy: EvaluationPolicy): Any = listOf(
            policy,
            model.from.name, model.from.alias,
            model.joins.map { listOf(it.kind, it.table.name, it.table.alias) },
            model.order.map { it.second },
            projection != null,
            model.lambdas(projection).map { it.eraseCapturedValues() },
        )

        private val ERASED: () -> Any? = { null }

        private fun Expression.eraseCapturedValues(): Expression = rewrite {
            when (it) {
                is CapturedExpression -> it.copy(value = null)
                is EvaluableExpression -> it.copy(evaluate = ERASED) // the evaluator is a new object on every call
                else -> null
            }
        }
    }
}

/**
 * Captured values and evaluators of all the query's lambdas in canonical order (lambdas in
 * [SelectModel.lambdas] order, depth-first within each). For the same query shape the order is always the same,
 * so the indices in cached recipes fit any new tree of that shape.
 *
 * Each evaluator is called at most once per SQL build (the result is memoized).
 */
internal class Captures(lambdas: List<LambdaExpression>) {
    val values = mutableListOf<Any?>()
    private val indices = IdentityHashMap<Expression, Int>()
    private val evaluators = mutableListOf<() -> Any?>()
    private val evaluated = mutableMapOf<Int, Any?>()

    init {
        lambdas.forEachIndexed { lambdaIndex, lambda ->
            // Within one lambda one name = one variable (local vals are inlined, parameters are not captures),
            // and identical structure of an evaluable subtree = the same value.
            val byName = mutableMapOf<String, Int>()
            val byOriginal = mutableMapOf<Expression, Int>()
            for (node in lambda.walk()) {
                when (node) {
                    is CapturedExpression ->
                        indices[node] = byName.getOrPut(node.name) { values.size.also { values += node.value } }
                    is EvaluableExpression ->
                        indices[node] = byOriginal.getOrPut(node.original) { evaluators.size.also { evaluators += node.evaluate } }
                    else -> {}
                }
            }
        }
    }

    fun indexOf(e: Expression): Int = indices[e] ?: error("Unknown value node: $e")

    fun evaluate(index: Int): Any? = evaluated.getOrPut(index) { evaluators[index]() }
}

/** How to obtain a query parameter value from the captured values. */
internal sealed interface ValueSource {
    fun resolve(captures: Captures): Any?

    data class Capture(val index: Int) : ValueSource {
        override fun resolve(captures: Captures) = captures.values[index]
    }

    /** The value of a compiler-generated evaluator for a subtree without parameters. */
    data class Evaluate(val index: Int) : ValueSource {
        override fun resolve(captures: Captures) = captures.evaluate(index)
    }

    /** Collection → array for `= ANY($n)`. */
    data class AsArray(val source: ValueSource) : ValueSource {
        override fun resolve(captures: Captures): Any? = when (val v = source.resolve(captures)) {
            is Collection<*> -> v.toTypedArray()
            else -> v
        }
    }

    data class Pattern(val prefix: String, val source: ValueSource, val suffix: String) : ValueSource {
        override fun resolve(captures: Captures) = "$prefix${source.resolve(captures)}$suffix"
    }

    data class Literal(val value: Any?) : ValueSource {
        override fun resolve(captures: Captures) = value
    }
}
