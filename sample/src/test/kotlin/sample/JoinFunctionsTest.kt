@file:Suppress("UNUSED")
package sample

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

object NumFns {
    @SqlFunction("MOD")
    fun mod(a: Long, b: Long): Long = throw UnsupportedOperationException("SQL only")

    /** Member extension: the receiver (a column of any table) is the first argument. */
    @SqlFunction("ABS")
    fun Int.absolute(): Int = throw UnsupportedOperationException("SQL only")
}

@SqlFunction("COALESCE")
fun coalesce(value: String?, fallback: String): String = throw UnsupportedOperationException("SQL only")

/** Evaluators and `@SqlFunction` in join queries: in ON, in where/select across tables, in LEFT JOIN. */
class JoinFunctionsTest {
    private lateinit var db: Connection

    @BeforeTest
    fun setUp() {
        db = DriverManager.getConnection("jdbc:h2:mem:")
        db.createStatement().use { st ->
            st.execute("CREATE TABLE users(id BIGINT, name VARCHAR, age INT, email VARCHAR, role VARCHAR)")
            st.execute("CREATE TABLE orders(id BIGINT, user_id BIGINT, product_id BIGINT, total INT)")
            st.execute("CREATE TABLE products(id BIGINT, title VARCHAR)")
            st.execute("INSERT INTO users VALUES (1, 'alice', 30, NULL, 'ADMIN'), (3, 'carol', 45, NULL, 'USER')")
            st.execute("INSERT INTO orders VALUES (10, 1, 100, 250), (11, 1, 101, -40), (12, 3, 999, 500)")
            st.execute("INSERT INTO products VALUES (100, 'Kotlin'), (101, 'Java')")
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `sql function and evaluator in the ON condition`() {
        val repo = Repo()
        val q = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId && NumFns.mod(o.id, 2) == 0L && o.total > repo.minAge() }
            .toSql()
        assertEquals(
            "SELECT * FROM users u JOIN orders o ON u.id = o.user_id AND MOD(o.id, 2) = 0 AND o.total > ?",
            q.sql,
        )
        assertEquals(listOf<Any?>(18), q.parameters)
        assertEquals(1, repo.calls)
    }

    @Test
    fun `member extension on columns of the joined table`() {
        val q = with(NumFns) {
            from<User>("users", "u")
                .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
                .where { u, o -> o.total.absolute() > 100 && u.age.absolute() > 18 }
                .toSql()
        }
        assertEquals(
            "SELECT * FROM users u JOIN orders o ON u.id = o.user_id WHERE ABS(o.total) > 100 AND ABS(u.age) > 18",
            q.sql,
        )
    }

    data class Line(val customer: String, val parity: Long, val product: String)

    @Test
    fun `functions over three tables with a nullable left-joined table`() {
        val q = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
            .leftJoin(table<Product>("products", "p")) { _, o, p -> o.productId == p.id }
            .where { _, _, p -> coalesce(p?.title, "none") != "Java" }
            .select { u, o, p -> Line(u.name, NumFns.mod(o.id, 2), coalesce(p?.title, "none")) }
            .toSql()
        // `p?.title` inside a function argument — no extra CASE: NULL propagates in SQL on its own.
        assertEquals(
            "SELECT u.name AS customer, MOD(o.id, 2) AS parity, COALESCE(p.title, 'none') AS product " +
                "FROM users u JOIN orders o ON u.id = o.user_id LEFT JOIN products p ON o.product_id = p.id " +
                "WHERE COALESCE(p.title, 'none') <> 'Java'",
            q.sql,
        )
    }

    @Test
    fun `numbering across ON, WHERE and SELECT in the cached form`() {
        val cache = SqlCache()
        fun query(min: Int, label: String) = from<User>("users", "u")
            .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId && o.total > min }
            .where { u, _ -> u.name != label }
            .select { u, o -> Line(u.name, NumFns.mod(o.id, 2), coalesce(null, label)) }
            .toCachedSql(cache)

        val first = query(100, "x")
        val second = query(200, "y")
        assertEquals(
            "SELECT u.name AS customer, MOD(o.id, 2) AS parity, COALESCE(NULL, $1) AS product " +
                "FROM users u JOIN orders o ON u.id = o.user_id AND o.total > $2 WHERE u.name <> $3",
            first.sql,
        )
        // `label` in select and in where are different lambdas, hence different numbers: a variable shares a number
        // only within one lambda (in different lambdas the same name may refer to different variables).
        assertEquals(listOf<Any?>("x", 100, "x"), first.parameters)
        assertEquals(listOf<Any?>("y", 200, "y"), second.parameters)
        assertEquals(1, cache.hits)
    }

    @Test
    fun `runs on a real database`() {
        val repo = Repo()
        val rows = with(NumFns) {
            from<User>("users", "u")
                .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId && o.total.absolute() > repo.minAge() }
                .leftJoin(table<Product>("products", "p")) { _, o, p -> o.productId == p.id }
                .where { _, _, p -> coalesce(p?.title, "none") != "Java" }
                .orderBy { _, o, _ -> o.id }
                .select { u, o, p -> Line(u.name, mod(o.id, 2), coalesce(p?.title, "none")) }
        }.toList(db)

        assertEquals(listOf(Line("alice", 0, "Kotlin"), Line("carol", 0, "none")), rows)
        assertEquals(1, repo.calls)
    }
}
