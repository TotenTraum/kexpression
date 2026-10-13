@file:Suppress("UNUSED")
package sample

import kexpression.EvaluationPolicy
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Repo {
    var calls = 0
    fun activeIds(): List<Long> {
        calls++
        return listOf(1L, 3L)
    }

    fun minAge(): Int {
        calls++
        return 18
    }
}

// Database functions (built into H2). Stub bodies: calling them on the client is an error.
@SqlFunction("LPAD")
fun lpad(s: String, length: Int, pad: String): String = throw UnsupportedOperationException("SQL only")

@SqlFunction("CONCAT_WS")
fun concatWs(separator: String, a: String, b: String?): String = throw UnsupportedOperationException("SQL only")

@SqlFunction("CHAR_LENGTH")
fun String.charLength(): Int = throw UnsupportedOperationException("SQL only")

@SqlFunction("week_start", schema = "reporting")
fun weekStart(day: Int): Int = throw UnsupportedOperationException("SQL only")

class FuncletizerSqlTest {
    private lateinit var db: Connection

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:h2:mem:")
        db.createStatement().use { st ->
            st.execute("CREATE TABLE users(id BIGINT, name VARCHAR, age INT, email VARCHAR, role VARCHAR)")
            st.execute(
                """INSERT INTO users VALUES
                   (1, 'alice', 30, 'alice@a.com', 'ADMIN'),
                   (2, 'bob',   17, NULL,          'USER'),
                   (3, 'carol', 45, 'carol@b.org', 'USER')""",
            )
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    // --- funcletizer ---

    @Test
    fun `calls on captured objects become parameters`() {
        val repo = Repo()
        val names = listOf("alice", "bob")
        val q = from<User>("users")
            .where { it.id in repo.activeIds() && it.age >= repo.minAge() && it.name != names.first() }
            .toSql()
        assertEquals("SELECT * FROM users WHERE id IN (?, ?) AND age >= ? AND name <> ?", q.sql)
        assertEquals(listOf<Any?>(1L, 3L, 18, "alice"), q.parameters)
    }

    @Test
    fun `evaluators run once per execution and never while building`() {
        val repo = Repo()
        val q = from<User>("users").where { it.id in repo.activeIds() && it.id in repo.activeIds() }
        assertEquals(0, repo.calls, "where { } evaluates nothing")

        assertEquals(listOf("alice", "carol"), q.toList(db).map { it.name })
        assertEquals(1, repo.calls, "an identical subtree within one lambda is evaluated once")

        q.toList(db)
        assertEquals(2, repo.calls, "every execution evaluates again")
    }

    @Test
    fun `values are read at execution time`() {
        val filter = SqlTest.Filter(minAge = 18)
        val q = from<User>("users").where { it.age > filter.minAge }
        assertEquals(listOf<Any?>(18), q.toCachedSql(SqlCache()).parameters)
    }

    @Test
    fun `policy decides between client evaluation and SQL`() {
        val min = 18
        val q = from<User>("users").where { it.age < min + 50 }
        // By default, arithmetic over values stays in SQL…
        assertEquals("SELECT * FROM users WHERE age < ? + 50", q.toSql().sql)
        // …while the Always policy evaluates it on the client.
        val always = q.toSql(EvaluationPolicy.Always)
        assertEquals("SELECT * FROM users WHERE age < ?", always.sql)
        assertEquals(listOf<Any?>(68), always.parameters)
    }

    @Test
    fun `policy is part of the cache key`() {
        val cache = SqlCache()
        val min = 18
        val q = from<User>("users").where { it.age < min + 50 }
        assertEquals("SELECT * FROM users WHERE age < $1 + 50", q.toCachedSql(cache).sql)
        assertEquals("SELECT * FROM users WHERE age < $1", q.toCachedSql(cache, EvaluationPolicy.Always).sql)
        assertEquals(2, cache.size)
    }

    // --- @SqlFunction ---

    @Test
    fun `sql functions with columns, parameters and constants`() {
        val pad = "*"
        val q = from<User>("users")
            .where { lpad(it.name, 7, pad) == "**alice" && it.email?.charLength() == 11 }
            .toCachedSql(SqlCache())
        assertEquals(
            "SELECT * FROM users WHERE LPAD(name, 7, $1) = '**alice' AND CHAR_LENGTH(email) = 11",
            q.sql,
        )
        assertEquals(listOf<Any?>("*"), q.parameters)
    }

    @Test
    fun `sql function is never evaluated on the client, even with known arguments`() {
        val repo = Repo()
        val q = from<User>("users").where { it.name == lpad("a", repo.minAge(), "0") }.toSql()
        // The evaluator argument became a parameter, the call itself stayed in SQL (the stub would have thrown).
        assertEquals("SELECT * FROM users WHERE name = LPAD('a', ?, '0')", q.sql)
        assertEquals(listOf<Any?>(18), q.parameters)
    }

    @Test
    fun `schema-qualified functions`() {
        val q = from<User>("users").where { weekStart(it.age) > 0 }.toSql()
        assertEquals("SELECT * FROM users WHERE reporting.week_start(age) > 0", q.sql)
    }

    data class Label(val label: String, val nameLength: Int)

    @Test
    fun `sql functions run on a real database`() {
        val sep = " / "
        val rows = from<User>("users")
            .where { it.age > 18 }
            .orderBy { it.id }
            .select { Label(concatWs(sep, it.name, it.email), it.name.charLength()) }
            .toList(db)
        assertEquals(listOf(Label("alice / alice@a.com", 5), Label("carol / carol@b.org", 5)), rows)
    }

    @Test
    fun `hand-built member access on captured objects is rejected without reflection`() {
        val tree = kexpression.LambdaExpression(
            listOf(kexpression.ParameterExpression("u", "sample.User")),
            kexpression.BinaryExpression(
                kexpression.BinaryOperator.GREATER,
                kexpression.MemberExpression(kexpression.ParameterExpression("u", "sample.User"), "age", null, "kotlin.Int"),
                kexpression.MemberExpression(kexpression.CapturedExpression("f", SqlTest.Filter(1), "sample.SqlTest.Filter"), "minAge", null, "kotlin.Int"),
                "kotlin.Boolean",
            ),
            "kotlin.Function1",
        )
        val model = SelectModel(table<User>("users"), filters = listOf(tree))
        assertFailsWith<UnsupportedOperationException> { model.toSql(null, SqlEvaluation.Default) }
    }
}
