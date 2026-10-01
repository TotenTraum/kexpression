package sample

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlTest {
    @Test
    fun `where with captured values`() {
        val minAge = 18
        val q = from<User>("users").where { it.age >= minAge && it.role == Role.ADMIN }.toSql()
        assertEquals("SELECT * FROM users WHERE age >= ? AND role = 'ADMIN'", q.sql)
        assertEquals(listOf<Any?>(18), q.parameters)
    }

    @Test
    fun `null checks like and order`() {
        val domain = "@example.com"
        val q = from<User>("users")
            .where { it.email != null && it.email.endsWith(domain) || it.name.startsWith("root") }
            .where { it.age < 65 }
            .orderByDescending { it.age }
            .orderBy { it.name }
            .toSql()
        assertEquals(
            "SELECT * FROM users WHERE (email IS NOT NULL AND email LIKE ? OR name LIKE ?) AND age < 65 " +
                "ORDER BY age DESC, name",
            q.sql,
        )
        assertEquals(listOf<Any?>("%@example.com", "root%"), q.parameters)
    }

    @Test
    fun `in collection and members of captured objects`() {
        val ids = listOf(1L, 2L, 3L)
        val filter = Filter(minAge = 21)
        val q = from<User>("users").where { it.id in ids && it.age > filter.minAge }.toSql()
        assertEquals("SELECT * FROM users WHERE id IN (?, ?, ?) AND age > ?", q.sql)
        assertEquals(listOf<Any?>(1L, 2L, 3L, 21), q.parameters)
    }

    data class Filter(val minAge: Int)
}
