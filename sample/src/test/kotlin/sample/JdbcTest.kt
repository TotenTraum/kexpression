package sample

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdbcTest {
    private lateinit var db: Connection

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:h2:mem:")
        db.createStatement().use { st ->
            st.execute("CREATE TABLE users(id BIGINT, name VARCHAR, age INT, email VARCHAR, role VARCHAR)")
            st.execute("CREATE TABLE orders(id BIGINT, user_id BIGINT, product_id BIGINT, total INT)")
            st.execute(
                """INSERT INTO users VALUES
                   (1, 'alice', 30, 'alice@a.com', 'ADMIN'),
                   (2, 'bob',   17, NULL,          'USER'),
                   (3, 'carol', 45, 'carol@b.org', 'USER')""",
            )
            st.execute("INSERT INTO orders VALUES (10, 1, 100, 250), (11, 1, 101, 40), (12, 3, 100, 500)")
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `toList materializes entities`() {
        val minAge = 18
        val users =
            from<User>("users")
                .where { it.age >= minAge }
                .orderBy { it.name }
                .toList(db)
        assertEquals(
            listOf(
                User(1, "alice", 30, "alice@a.com", Role.ADMIN),
                User(3, "carol", 45, "carol@b.org", Role.USER),
            ),
            users,
        )
    }

    data class Row(val customer: String, val orderTotal: Int, val vip: Boolean = false)

    @Test
    fun `projection over join with default constructor arguments`() {
        val min = 100
        val rows = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
            .where { _, o -> o.total >= min }
            .orderByDescending { _, o -> o.total }
            .select { u, o -> Row(u.name, o.total) }
            .toList(db)
        assertEquals(listOf(Row("carol", 500), Row("alice", 250)), rows)
    }

    @Test
    fun `pairs, single values and whole rows`() {
        assertEquals(
            listOf("alice" to 30, "bob" to 17),
            from<User>("users").where { it.age < 40 }.orderBy { it.id }.select { it.name to it.age }
                .toList(db),
        )
        assertEquals(
            listOf("alice@a.com", "-", "carol@b.org"),
            from<User>("users").orderBy { it.id }.select { it.email ?: "-" }.toList(db),
        )
        assertEquals(
            listOf(Order(12, 3, 100, 500)),
            from<User>("users", "u")
                .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
                .where { u, _ -> u.name == "carol" }
                .select { _, o -> o }
                .toList(db),
        )
    }

    @Test
    fun `cached $n statements run on JDBC including ANY and LIKE`() {
        val cache = SqlCache()
        fun byIds(ids: List<Long>, domain: String) =
            from<User>("users").where { it.id in ids && it.email?.endsWith(domain) == true }
                .select { it.name }.toList(db, cache)

        assertEquals(listOf("alice"), byIds(listOf(1L, 2L), "@a.com"))
        assertEquals(listOf("carol"), byIds(listOf(1L, 3L), ".org"))
        assertEquals(1, cache.hits)
    }

    @Test
    fun `toSequence is lazy and closeable`() {
        val seq = from<User>("users").orderBy { it.id }.toSequence(db)
        assertEquals("SELECT * FROM users ORDER BY id", seq.sql.sql)
        val first =
            seq.use { rows -> rows.map { it.name }.first() } // the partially read ResultSet is closed
        assertEquals("alice", first)
        assertFailsWith<IllegalStateException> { seq.toList() } // iterating again is not allowed
    }

    @Test
    fun `dollar parameters are expanded for JDBC`() {
        val s = SqlStatement(
            "SELECT '$1' FROM t WHERE a > $1 AND b < $2 AND c = $1",
            listOf(10, 20)
        ).toJdbc()
        assertEquals("SELECT '$1' FROM t WHERE a > ? AND b < ? AND c = ?", s.sql)
        assertEquals(listOf<Any?>(10, 20, 10), s.parameters)
        assertTrue(s.columns.isEmpty())
    }
}
