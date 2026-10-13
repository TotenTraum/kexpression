package kexpression.tests

import kexpression.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

object Shouter {
    fun String.shout(): String = uppercase() + "!"
    val String.twice: String get() = this + this
    fun plain(s: String): String = s
}

/** Calls with two receivers (member extensions) and with one. */
class ReceiversTest {
    @Test
    fun memberExtensionKeepsBothReceivers() {
        val e: Expr<(User) -> String> = with(Shouter) { expr { u -> u.name.shout() } }

        val call = assertIs<CallExpression>(e.body)
        assertEquals(MemberExpression(ParameterExpression("u", "kexpression.tests.User"), "name", "kexpression.tests.User", "kotlin.String"), call.extensionReceiver)
        // The implicit receiver from `with(Shouter)` is a value captured by the closure.
        assertSame(Shouter, assertIs<CapturedExpression>(call.dispatchReceiver).value)
        assertSame(call.extensionReceiver, call.receiver)
        assertEquals(true, call.isExtension)
        assertEquals("{ u: User -> u.name.shout() }", e.toString())
        assertEquals("BOB!", e(User(1, "bob", 20, null, Role.USER)))
    }

    @Test
    fun memberExtensionPropertyUsesExtensionReceiver() {
        val e: Expr<(User) -> String> = with(Shouter) { expr { u -> u.name.twice } }
        val member = assertIs<MemberExpression>(e.body)
        assertIs<MemberExpression>(member.receiver)
        assertEquals("{ u: User -> u.name.twice }", e.toString())
    }

    @Test
    fun objectFunctionHasOnlyDispatchReceiver() {
        val e: Expr<(User) -> String> = expr { u -> Shouter.plain(u.name) }
        val call = assertIs<CallExpression>(e.body)
        assertIs<ConstantExpression>(call.dispatchReceiver)
        assertNull(call.extensionReceiver)
        assertEquals(false, call.isExtension)
    }

    @Test
    fun topLevelExtensionHasOnlyExtensionReceiver() {
        val e: Expr<(User) -> Boolean> = expr { u -> u.name.startsWith("b") }
        val call = assertIs<CallExpression>(e.body)
        assertNull(call.dispatchReceiver)
        assertIs<MemberExpression>(call.extensionReceiver)
    }

    @Test
    fun rewriteVisitsBothReceivers() {
        val e: Expr<(User) -> String> = with(Shouter) { expr { u -> u.name.shout() } }
        val renamed = e.body.rewrite { if (it is ParameterExpression) it.copy(name = "v") else null }
        assertEquals("v.name.shout()", renamed.toString())
        assertEquals(2, assertIs<CallExpression>(renamed).children.size)
    }
}
