package sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JoinTest {
    @Test
    fun `inner join with where on both tables`() {
        val minTotal = 100
        val q = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
            .where { u, o -> u.age >= 18 && o.total > minTotal }
            .orderBy { u, _ -> u.name }
            .toSql()
        assertEquals(
            "SELECT * FROM users u JOIN orders o ON u.id = o.user_id " +
                "WHERE u.age >= 18 AND o.total > ? ORDER BY u.name",
            q.sql,
        )
        assertEquals(listOf<Any?>(100), q.parameters)
    }

    @Test
    fun `parameter names do not matter - only positions`() {
        val q = from<User>("users")
            .join(table<Order>("orders")) { a, b -> a.id == b.userId }
            .toSql()
        assertEquals("SELECT * FROM users JOIN orders ON users.id = orders.user_id", q.sql)
    }

    @Test
    fun `left join exposes nullable table and safe calls`() {
        val q = from<User>("users", "u")
            .leftJoin(table<Order>("orders", "o")) { u, o -> u.id == o.userId && o.total > 0 }
            .where { _, o -> o == null || (o?.total ?: 0) < 50 }
            .toSql()
        assertEquals(
            "SELECT * FROM users u LEFT JOIN orders o ON u.id = o.user_id AND o.total > 0 " +
                "WHERE o.id IS NULL OR COALESCE(o.total, 0) < 50",
            q.sql,
        )
    }

    @Test
    fun `three tables`() {
        val q = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
            .leftJoin(table<Product>("products", "p")) { _, o, p -> o.productId == p.id }
            .where { _, _, p -> p?.title?.startsWith("Kotlin") == true }
            .toSql()
        assertEquals(
            "SELECT * FROM users u JOIN orders o ON u.id = o.user_id " +
                "LEFT JOIN products p ON o.product_id = p.id WHERE p.title LIKE ?",
            q.sql,
        )
        assertEquals(listOf<Any?>("Kotlin%"), q.parameters)
    }

    @Test
    fun `self join requires distinct aliases`() {
        assertFailsWith<IllegalArgumentException> {
            from<User>("users").join(table<User>("users")) { a, b -> a.id == b.id }
        }
        val q = from<User>("users", "a")
            .join(table<User>("users", "b")) { a, b -> a.email == b.email && a.id != b.id }
            .toSql()
        assertEquals("SELECT * FROM users a JOIN users b ON a.email = b.email AND a.id <> b.id", q.sql)
    }
}
