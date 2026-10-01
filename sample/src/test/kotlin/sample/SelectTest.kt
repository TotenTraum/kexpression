package sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

data class UserOrder(val name: String, val orderTotal: Int, val discounted: Int = 0)

class SelectTest {
    @Test
    fun `projection into data class over join`() {
        val minTotal = 100
        val q = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
            .where { _, o -> o.total > minTotal }
            .select { u, o -> UserOrder(u.name, o.total, discounted = o.total * 9 / 10) }
            .toSql()
        assertEquals(
            "SELECT u.name, o.total AS order_total, o.total * 9 / 10 AS discounted " +
                "FROM users u JOIN orders o ON u.id = o.user_id WHERE o.total > ?",
            q.sql,
        )
        assertEquals(listOf("name", "order_total", "discounted"), q.columns)
        assertEquals(listOf<Any?>(100), q.parameters)
    }

    @Test
    fun `default constructor arguments are skipped`() {
        val q = from<User>("users").select { UserOrder(it.name.uppercase(), it.age) }.toSql()
        assertEquals("SELECT UPPER(name) AS name, age AS order_total FROM users", q.sql)
    }

    @Test
    fun `pair and single column`() {
        assertEquals(
            "SELECT name AS \"first\", email AS \"second\" FROM users",
            from<User>("users").select { it.name to it.email }.toSql().sql,
        )
        val single = from<User>("users").where { it.age > 30 }.select { it.email ?: "-" }.toSql()
        assertEquals("SELECT COALESCE(email, '-') AS \"value\" FROM users WHERE age > 30", single.sql)
    }

    @Test
    fun `whole row of one table`() {
        val q = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
            .select { _, o -> o }
            .toSql()
        assertEquals("SELECT o.* FROM users u JOIN orders o ON u.id = o.user_id", q.sql)
    }

    @Test
    fun `captured values in projection become parameters in text order`() {
        val bonus = 5
        val q = from<User>("users").where { it.age > 18 }.select { UserOrder(it.name, it.age + bonus) }.toSql()
        assertEquals("SELECT name, age + ? AS order_total FROM users WHERE age > 18", q.sql)
        assertEquals(listOf<Any?>(5), q.parameters)
    }

    @Test
    fun `right operand keeps parentheses`() {
        val q = from<Order>("orders").select { it.total - (it.id - it.productId) }.toSql()
        assertEquals("SELECT total - (id - product_id) AS \"value\" FROM orders", q.sql)
    }

    @Test
    fun `nested objects are rejected`() {
        assertFailsWith<UnsupportedOperationException> {
            from<User>("users").select { it to it.name }.toSql()
        }
    }
}
