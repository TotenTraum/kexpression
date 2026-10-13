package kexpression.tests

import kexpression.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExprTest {
    private fun <F : Function<*>> Expr<F>.text() = toString()

    @Test
    fun arithmeticTreeAndCompile() {
        val e: Expr<(Int, Int) -> Int> = expr { a, b -> a + b * 2 }
        assertEquals(listOf(ParameterExpression("a", "kotlin.Int"), ParameterExpression("b", "kotlin.Int")), e.parameters)
        assertEquals(
            BinaryExpression(
                BinaryOperator.PLUS,
                ParameterExpression("a", "kotlin.Int"),
                BinaryExpression(
                    BinaryOperator.TIMES,
                    ParameterExpression("b", "kotlin.Int"),
                    ConstantExpression(2, "kotlin.Int"),
                    "kotlin.Int",
                ),
                "kotlin.Int",
            ),
            e.body,
        )
        assertEquals("{ a: Int, b: Int -> a + (b * 2) }", e.text())
        assertEquals(7, e.compile()(1, 3))
        assertEquals(7, e(1, 3))
    }

    @Test
    fun explicitParameterTypesInferF() {
        val e = expr { s: String -> s.length > 3 }
        assertEquals("{ s: String -> s.length > 3 }", e.text())
        assertTrue(e("abcd"))
    }

    @Test
    fun comparisonsEqualityAndLogic() {
        val e: Expr<(User) -> Boolean> = expr { u -> u.age >= 18 && u.name != "bob" || !(u.role == Role.ADMIN) }
        assertEquals("{ u: User -> ((u.age >= 18) && (u.name != \"bob\")) || !(u.role == Role.ADMIN) }", e.text())
        val or = assertIs<BinaryExpression>(e.body)
        assertEquals(BinaryOperator.OR, or.operator)
    }

    @Test
    fun comparableCompareToIsRestoredAsBinary() {
        val e: Expr<(String, String) -> Boolean> = expr { a, b -> a < b }
        assertEquals("{ a: String, b: String -> a < b }", e.text())
    }

    @Test
    fun closuresAreCapturedByValue() {
        val limit = 10
        val name = "x"
        val e: Expr<(Int) -> String> = expr { it -> if (it > limit) name else "small" }
        val cond = assertIs<ConditionalExpression>(e.body)
        val gt = assertIs<BinaryExpression>(cond.test)
        assertEquals(CapturedExpression("limit", 10, "kotlin.Int"), gt.right)
        assertEquals(CapturedExpression("name", "x", "kotlin.String"), cond.ifTrue)
        assertEquals("x", e(11))
    }

    @Test
    fun implicitItParameterWithExpectedType() {
        val e: Expr<(Int) -> Int> = expr { it * it }
        assertEquals("{ it: Int -> it * it }", e.text())
        assertEquals(49, e(7))
    }

    @Test
    fun callsTemplatesNullsAndElvis() {
        val e: Expr<(User) -> String> = expr { u -> "${u.name.uppercase()}: ${u.email ?: "none"}" }
        assertEquals("{ u: User -> \"\${u.name.uppercase()}: \${u.email ?: \"none\"}\" }", e.text())
        val call = expr { u: User -> u.email!!.contains("@") }
        val c = assertIs<CallExpression>(call.body)
        assertEquals("contains", c.function)
        assertTrue(c.isExtension)
        assertIs<UnaryExpression>(c.receiver)
    }

    @Test
    fun nestedLambdas() {
        val threshold = 5
        val e: Expr<(List<Int>) -> Boolean> = expr { xs -> xs.any { x -> x > threshold } }
        assertEquals("{ xs: List -> xs.any({ x: Int -> x > threshold }) }", e.text())
        val inner = assertIs<LambdaExpression>(assertIs<CallExpression>(e.body).arguments.single())
        assertEquals(listOf(ParameterExpression("x", "kotlin.Int")), inner.parameters)
        assertTrue(e(listOf(1, 9)))
    }

    @Test
    fun whenAndTypeOperators() {
        val e: Expr<(Any) -> Int> = expr { v ->
            when (v) {
                is String -> v.length
                is Int -> v
                else -> -1
            }
        }
        // A smart cast is an implicit conversion; it's not in the tree.
        assertEquals("{ v: Any -> if (v is String) v.length else if (v is Int) v else -1 }", e.text())
        assertEquals(3, e("abc"))
        assertEquals(-1, e(true)) // not a number: on JS `2.5 is Int` is true — every number there is a JS number
    }

    @Test
    fun localValsAreInlined() {
        val e: Expr<(Int) -> Int> = expr { x ->
            val y = x + 1
            y * y
        }
        assertEquals("{ x: Int -> (x + 1) * (x + 1) }", e.text())
        assertEquals(9, e(2))
    }

    @Test
    fun constructorCallsAndReceiverThis() {
        val e = Box(3).sumExpr()
        assertEquals("{ y: Int -> Box(this.value + y) }", e.text())
        assertEquals(Box(5), e(2))
    }

    @Test
    fun quotedParameter() {
        assertNull({ x: Int -> x }.toExpressionOrNull())
        val tree = treeOf { x: Int -> x % 2 == 0 }
        assertEquals("{ x: Int -> (x % 2) == 0 }", tree.toString())
    }

    @Test
    fun quotedMultipleParametersAndReceiver() {
        val f = threeArgs { n, s, b -> if (b) s.repeat(n) else s }
        assertEquals("abab", f(2, "ab", true))
        assertEquals("{ n: Int, s: String, b: Boolean -> if (b) s.repeat(n) else s }", f.toExpression().toString())

        val g = withReceiver { i -> this[i] }
        assertEquals('b', "abc".g(1))
        assertEquals("{ this: String, i: Int -> this.get(i) }", g.toExpression().toString())
    }

    @Test
    fun enumConstantsPrintPortably() {
        val e = expr { u: User -> u.role == Role.USER }
        assertEquals("{ u: User -> u.role == Role.USER }", e.text())
    }

    @Test
    fun quotedFunctionIsStillCallable() {
        val f = passThrough { x: Int -> x + 100 }
        assertEquals(101, f(1))
        assertEquals("{ x: Int -> x + 100 }", f.toExpression().toString())
    }
}

enum class Role { ADMIN, USER }

data class User(val id: Long, val name: String, val age: Int, val email: String?, val role: Role)

data class Box(val value: Int) {
    fun sumExpr(): Expr<(Int) -> Box> = expr { y -> Box(value + y) }
}

fun treeOf(@Quoted f: (Int) -> Boolean): LambdaExpression = f.toExpression()

fun passThrough(@Quoted f: (Int) -> Int): (Int) -> Int = f

fun threeArgs(@Quoted f: (Int, String, Boolean) -> String): (Int, String, Boolean) -> String = f

fun withReceiver(@Quoted f: String.(Int) -> Char): String.(Int) -> Char = f
