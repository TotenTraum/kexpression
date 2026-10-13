package sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SqlCacheTest {
    private val cache = SqlCache()

    /** The same line of code, called with different values. */
    private fun adults(minAge: Int, role: Role) = from<User>("users")
        .where { it.age >= minAge && it.role == role && it.age < minAge + 50 }
        .toCachedSql(cache)

    @Test
    fun `same call site hits cache, values are re-extracted`() {
        val first = adults(18, Role.ADMIN)
        val second = adults(30, Role.USER)

        assertEquals("SELECT * FROM users WHERE age >= $1 AND role = $2 AND age < $1 + 50", first.sql)
        // One variable (`minAge`) gets one number, even when used twice.
        assertEquals(listOf<Any?>(18, Role.ADMIN), first.parameters)
        assertEquals(listOf<Any?>(30, Role.USER), second.parameters)
        assertSame(first.sql, second.sql)
        assertEquals(1, cache.misses)
        assertEquals(1, cache.hits)
    }

    @Test
    fun `different shapes get different entries`() {
        val x = 1
        from<User>("users").where { it.age > x }.toCachedSql(cache)
        from<User>("users").where { it.age < x }.toCachedSql(cache)
        from<User>("people").where { it.age > x }.toCachedSql(cache)
        from<User>("users").where { it.age > x }.toCachedSql(cache)
        assertEquals(3, cache.size)
        assertEquals(1, cache.hits)
    }

    private fun byIds(ids: List<Long>) = from<User>("users").where { it.id in ids }.toCachedSql(cache)

    @Test
    fun `IN list does not change the text - uses ANY with an array`() {
        val a = byIds(listOf(1L, 2L))
        val b = byIds(listOf(3L, 4L, 5L))
        assertEquals("SELECT * FROM users WHERE id = ANY($1)", a.sql)
        assertSame(a.sql, b.sql)
        assertEquals(listOf<Any?>(3L, 4L, 5L), (b.parameters.single() as Array<*>).toList())
        assertEquals(1, cache.hits)
    }

    private fun search(domain: String, filter: SqlTest.Filter) = from<User>("users", "u")
        .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
        .where { u, o -> u.email?.endsWith(domain) == true && u.name.startsWith("a") && o.total > filter.minAge }
        .select { u, o -> UserOrder(u.name, o.total) }
        .toCachedSql(cache)

    @Test
    fun `LIKE patterns, members of captured objects, joins and projections`() {
        val first = search("@a.com", SqlTest.Filter(10))
        val second = search("@b.org", SqlTest.Filter(20))
        assertEquals(
            "SELECT u.name, o.total AS order_total FROM users u JOIN orders o ON u.id = o.user_id " +
                "WHERE u.email LIKE '%' || $1 AND u.name LIKE 'a%' AND o.total > $2",
            first.sql,
        )
        assertEquals(listOf<Any?>("@a.com", 10), first.parameters)
        assertEquals(listOf<Any?>("@b.org", 20), second.parameters)
        assertEquals(listOf("name", "order_total"), second.columns)
        assertEquals(1, cache.hits)
    }

    @Test
    fun `numbering follows text order across clauses`() {
        val bonus = 5
        val min = 18
        val q = from<User>("users").where { it.age > min }.select { UserOrder(it.name, it.age + bonus) }.toCachedSql(cache)
        assertEquals("SELECT name, age + $1 AS order_total FROM users WHERE age > $2", q.sql)
        assertEquals(listOf<Any?>(5, 18), q.parameters)
    }

    @Test
    fun `cached and uncached translations agree`() {
        val min = 21
        val q = from<User>("users").where { it.age > min || it.name == "x" }
        assertEquals("SELECT * FROM users WHERE age > ? OR name = 'x'", q.toSql().sql)
        assertEquals("SELECT * FROM users WHERE age > $1 OR name = 'x'", q.toCachedSql(cache).sql)
        assertEquals(q.toSql().parameters, q.toCachedSql(cache).parameters)
    }
}
