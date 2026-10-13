@file:Suppress("UNUSED")
package sample

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

object SqlFns {
    @SqlFunction("LPAD")
    fun lpad(s: String, length: Int, pad: String): String = throw UnsupportedOperationException("SQL only")

    /** Member extension: the call has two receivers — the object and the string. */
    @SqlFunction("CHAR_LENGTH")
    fun String.len(): Int = throw UnsupportedOperationException("SQL only")

    /** A call from inside the object: the receiver is the implicit `this`. */
    fun paddedNames(pad: String) = from<User>("users").where { lpad(it.name, 7, pad) == "**alice" }
}

class TextFns {
    @SqlFunction("UPPER")
    fun upper(s: String): String = throw UnsupportedOperationException("SQL only")

    companion object {
        @SqlFunction("LOWER")
        fun lower(s: String): String = throw UnsupportedOperationException("SQL only")
    }
}

object Registry {
    val text = TextFns()
}

/** `@SqlFunction`s declared below top level: the owning receiver must not end up in SQL. */
class SqlFunctionScopeTest {
    @Test
    fun `function in an object`() {
        val q = from<User>("users").where { SqlFns.lpad(it.name, 7, "*") == "**alice" }.toSql()
        assertEquals("SELECT * FROM users WHERE LPAD(name, 7, '*') = '**alice'", q.sql)
        assertEquals(emptyList(), q.parameters)
    }

    @Test
    fun `call from inside the object`() {
        val q = SqlFns.paddedNames("*").toSql()
        assertEquals("SELECT * FROM users WHERE LPAD(name, 7, ?) = '**alice'", q.sql)
        assertEquals(listOf<Any?>("*"), q.parameters)
    }

    @Test
    fun `function in a companion object`() {
        val q = from<User>("users").where { TextFns.lower(it.name) == "alice" }.toSql()
        assertEquals("SELECT * FROM users WHERE LOWER(name) = 'alice'", q.sql)
    }

    @Test
    fun `function on a class instance, captured or reached through a property`() {
        val fns = TextFns()
        assertEquals(
            "SELECT * FROM users WHERE UPPER(name) = 'ALICE'",
            from<User>("users").where { fns.upper(it.name) == "ALICE" }.toSql().sql,
        )
        // Registry.text is an evaluable subtree, but it neither ends up in SQL nor gets evaluated.
        val q = from<User>("users").where { Registry.text.upper(it.name) == "ALICE" }.toSql()
        assertEquals("SELECT * FROM users WHERE UPPER(name) = 'ALICE'", q.sql)
        assertEquals(emptyList(), q.parameters)
    }

    @Test
    fun `member extension keeps its extension receiver`() {
        val q = with(SqlFns) { from<User>("users").where { it.name.len() > 3 && it.email?.len() == 11 }.toSql() }
        assertEquals("SELECT * FROM users WHERE CHAR_LENGTH(name) > 3 AND CHAR_LENGTH(email) = 11", q.sql)
        assertEquals(emptyList(), q.parameters)
    }

    @Test
    fun `runs on a real database`() {
        DriverManager.getConnection("jdbc:h2:mem:").use { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE users(id BIGINT, name VARCHAR, age INT, email VARCHAR, role VARCHAR)")
                it.execute("INSERT INTO users VALUES (1, 'alice', 30, NULL, 'ADMIN'), (2, 'bob', 17, NULL, 'USER')")
            }
            val names = with(SqlFns) {
                from<User>("users")
                    .where { lpad(it.name, 7, "*") == "**alice" || TextFns.lower(it.name) == "bob" && it.name.len() == 3 }
                    .orderBy { it.id }
                    .select { it.name }
            }.toList(db)
            assertEquals(listOf("alice", "bob"), names)
        }
    }
}
