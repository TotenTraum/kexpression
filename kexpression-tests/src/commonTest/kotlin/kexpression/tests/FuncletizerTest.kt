@file:Suppress("UNUSED")
package kexpression.tests

import kexpression.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Filter(var minAge: Int)

class Counter {
    var calls = 0
    fun next(): Int = ++calls
}

/** Like `@SqlFunction`: a function the consumer of the tree translates itself. */
@Translatable
@Target(AnnotationTarget.FUNCTION)
annotation class DbFunction(val name: String)

@DbFunction("upper")
fun dbUpper(s: String): String = throw UnsupportedOperationException("db only")

class FuncletizerTest {
    @Test
    fun closedSubtreeBecomesEvaluable() {
        val filter = Filter(18)
        val e: Expr<(User) -> Boolean> = expr { u -> u.age > filter.minAge + 1 }

        val gt = assertIs<BinaryExpression>(e.body)
        assertEquals(MemberExpression(ParameterExpression("u", "kexpression.tests.User"), "age", "kexpression.tests.User", "kotlin.Int"), gt.left)
        val evaluable = assertIs<EvaluableExpression>(gt.right)
        assertEquals("filter.minAge + 1", evaluable.original.toString())
        assertEquals("kotlin.Int", evaluable.type)
        assertEquals(19, evaluable.evaluate())
        // Printing is unchanged: the structure is printed.
        assertEquals("{ u: User -> u.age > (filter.minAge + 1) }", e.toString())
    }

    @Test
    fun valuesAreReadAtEvaluationTime() {
        val filter = Filter(18)
        val e: Expr<(User) -> Boolean> = expr { u -> u.age > filter.minAge }
        val evaluable = assertIs<EvaluableExpression>(assertIs<BinaryExpression>(e.body).right)
        filter.minAge = 30
        assertEquals(30, evaluable.evaluate())
    }

    @Test
    fun evaluatorRunsOnlyWhenCalled() {
        val counter = Counter()
        val e: Expr<(User) -> Boolean> = expr { u -> u.age > counter.next() }
        assertEquals(0, counter.calls, "building the tree runs no code")
        val evaluable = assertIs<EvaluableExpression>(assertIs<BinaryExpression>(e.body).right)
        evaluable.evaluate()
        evaluable.evaluate()
        assertEquals(2, counter.calls)
    }

    @Test
    fun leavesStayAsTheyAre() {
        val limit = 10
        val e: Expr<(User) -> Boolean> = expr { u -> u.age > limit && u.role == Role.USER }
        assertTrue(e.body.walk().none { it is EvaluableExpression }, "constants and captured variables are already values")
    }

    @Test
    fun localValsAreInlinedIntoEvaluators() {
        val filter = Filter(18)
        val e: Expr<(User) -> Boolean> = expr { u ->
            val k = filter.minAge * 2
            u.age > k
        }
        val evaluable = assertIs<EvaluableExpression>(assertIs<BinaryExpression>(e.body).right)
        assertEquals(36, evaluable.evaluate())
    }

    @Test
    fun translatableCallsStayStructureButArgumentsAreEvaluable() {
        val prefix = "ab"
        val e: Expr<(User) -> Boolean> = expr { u -> u.name == dbUpper(prefix + "x") }

        val call = assertIs<CallExpression>(assertIs<BinaryExpression>(e.body).right)
        assertEquals("upper", call.annotation<DbFunction>()?.name)
        val argument = assertIs<EvaluableExpression>(call.arguments.single())
        assertEquals("abx", argument.evaluate())
    }

    @Test
    fun localsDependingOnTranslatableCallsAreNotEvaluated() {
        val e: Expr<(User) -> Boolean> = expr { u ->
            val upper = dbUpper("x")
            u.name == upper
        }
        // `upper` can't be evaluated on the client — the call's structure is inlined.
        val call = assertIs<CallExpression>(assertIs<BinaryExpression>(e.body).right)
        assertEquals("upper", call.annotation<DbFunction>()?.name)
    }

    @Test
    fun nestedLambdasStayStructure() {
        val ids = listOf(1, 2)
        val e: Expr<(User) -> Boolean> = expr { _ -> ids.any { it > 1 } }
        val any = assertIs<CallExpression>(e.body)
        assertIs<CapturedExpression>(any.receiver)
        assertIs<LambdaExpression>(any.arguments.single())
    }

    @Test
    fun quotedParametersAreFuncletizedToo() {
        val filter = Filter(5)
        val tree = treeOf { x: Int -> x > filter.minAge }
        val evaluable = assertIs<EvaluableExpression>(assertIs<BinaryExpression>(tree.body).right)
        assertEquals(5, evaluable.evaluate())
    }

    @Test
    fun rewriteKeepsTheEvaluator() {
        val filter = Filter(7)
        val e: Expr<(User) -> Boolean> = expr { u -> u.age > filter.minAge }
        val rewritten = e.body.rewrite { if (it is ParameterExpression) it.copy(name = "v") else null }
        val evaluable = assertIs<EvaluableExpression>(assertIs<BinaryExpression>(rewritten).right)
        assertEquals(7, evaluable.evaluate())
    }
}
