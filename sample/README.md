# sample: a SQL provider built on kexpression

A mini LINQ for SQL: queries are written as plain Kotlin lambdas and turned into SQL via their expression trees — like
`IQueryable<T>` in C#. This is an example consumer of [kexpression](../README.md) trees. It runs on the JVM only:
execution goes through JDBC, and result rows are assembled into objects with `kotlin-reflect`.

```kotlin
val minAge = 18
from<User>("users").where { it.age >= minAge && it.role == Role.ADMIN }.toSql()
// SELECT * FROM users WHERE age >= ? AND role = 'ADMIN'      parameters = [18]
```

## Queries

```kotlin
from<User>("users")
    .where { it.email != null && it.email.endsWith(domain) || it.name.startsWith("root") }
    .orderByDescending { it.age }
// SELECT * FROM users WHERE email IS NOT NULL AND email LIKE ? OR name LIKE ? ORDER BY age DESC
```

How Kotlin constructs are translated:
- **captured variables** become query parameters (protection against SQL injection);
- **`x == null`** → `x IS NULL`;
- **`startsWith`, `endsWith`, `contains`** → `LIKE`;
- **`it.id in ids`** → `IN (...)`;
- **`?:`** → `COALESCE`;
- **`?.`** — no extra `CASE`: NULL propagates in SQL on its own;
- **column names** are the property names in `snake_case`.

## Joins

The i-th lambda parameter is the i-th table of the query: `Query<A>` → `Query2<A, B>` → `Query3<A, B, C>`.

```kotlin
from<User>("users", "u")
    .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
    .leftJoin(table<Product>("products", "p")) { _, o, p -> o.productId == p.id }   // from here on p: Product?
    .where { _, o, p -> o.total > minTotal && p?.title != null }
// SELECT * FROM users u JOIN orders o ON u.id = o.user_id
//   LEFT JOIN products p ON o.product_id = p.id WHERE o.total > ? AND p.title IS NOT NULL
```

In `leftJoin`, the `ON` condition sees the table as non-null, and later lambdas see it as nullable. A self-join needs distinct aliases.

## Projection

Columns come from the constructor arguments (parameter names are available in `NewExpression.parameterNames`):

```kotlin
.select { u, o -> OrderReport(u.name, o.total * 9 / 10) }
// SELECT u.name AS customer, o.total * 9 / 10 AS discounted_total FROM ...
```

Supported: a constructor (including one with default parameters), `a to b`, a whole entity (`{ _, o -> o }` → `o.*`), and a single value. Aliases that are keywords (`second`, `value`) are quoted.

## Query-shape cache and `$n`

`toCachedSql()` builds the text once per query shape. Later calls only extract the values again, and they are numbered `$1, $2, ...`:

```kotlin
fun adults(minAge: Int, role: Role) = from<User>("users")
    .where { it.age >= minAge && it.role == role && it.age < minAge + 50 }
    .toCachedSql()
// SELECT * FROM users WHERE age >= $1 AND role = $2 AND age < $1 + 50
// adults(18, ADMIN) → [18, ADMIN] (miss), adults(30, USER) → [30, USER] (hit)
```

To keep the text independent of the values:
- **`IN`** → `= ANY($1)` with an array;
- **`LIKE`** is concatenated in SQL: `LIKE '%' || $1`;
- **a variable gets one number** within a single lambda. Across lambdas the same name may refer to different variables, so the numbers differ.

## Execution over JDBC

```kotlin
val rows: List<Row> = from<User>("users", "u")
    .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
    .select { u, o -> Row(u.name, o.total) }
    .toList(connection)

from<User>("users").toSequence(connection).use { users -> users.first { it.age > 18 } }
```

`toSequence` runs the query lazily. The sequence can be iterated only once and must be closed (`use`) if it isn't read to the end.

## Client-side evaluation

Subtrees without lambda parameters (`EvaluableExpression`) become query parameters:

```kotlin
from<User>("users").where { it.id in repo.activeIds() && it.age >= repo.minAge() }
// SELECT * FROM users WHERE id IN (?, ?) AND age >= ?
```

- **When evaluators run:** `where { }` evaluates nothing. Evaluators run on every execution (`toSql`, `toList`); an identical subtree within one lambda runs once.
- **The default policy (`SqlEvaluation.Default`):** arithmetic over values stays in SQL (`$1 + 50`), while properties and calls on captured objects are evaluated on the client.
- **A custom policy:** pass it to `toSql(policy)` and `toCachedSql(cache, policy)`. The policy is part of the cache key.

## Database functions: `@SqlFunction`

```kotlin
@SqlFunction("LPAD")
fun lpad(s: String, length: Int, pad: String): String = throw UnsupportedOperationException("SQL only")

from<User>("users").where { lpad(it.name, 7, pad) == "**alice" }
// SELECT * FROM users WHERE LPAD(name, 7, $1) = '**alice'
```

- **The call is never evaluated on the client**, even when all its arguments are known. Its arguments are translated as usual.
- **Schema:** `@SqlFunction("week_start", schema = "reporting")` → `reporting.week_start(...)`.
- **Where to declare them:** at top level, in an `object`, a `companion object`, or a class. The owning object never ends up in SQL.
- **Extension functions**, including member extensions: the receiver becomes the first argument. For example, `with(SqlFns) { it.name.len() }` → `CHAR_LENGTH(name)`.
- **Put `with(...)` outside the query lambda.** Inside it you get a `with` call with a nested lambda, which can't be translated.

## Files

| File | Contents |
|---|---|
| `Sql.kt` | `Query`, `Query2`, `Query3`, `SelectQuery`, the translator, `@SqlFunction`, `SqlEvaluation` |
| `SqlCache.kt` | the query-shape cache, numbering of captures and evaluators |
| `Jdbc.kt` | `toList`, `toSequence`, row mapping, `$n` → `?` |
| `Main.kt` | a demo |

Tests (in-memory H2):
- `SqlTest`, `JoinTest`, `SelectTest`, `SqlCacheTest`, `JdbcTest`;
- `FuncletizerSqlTest`, `SqlFunctionScopeTest`, `JoinFunctionsTest`.

## Running

```bash
./gradlew :sample:test
```

```bash
./gradlew :sample:run
```

## Limitations

- **JVM only:** JDBC, and `kotlin-reflect` to assemble objects.
- **Read only:** no `insert`, `update`, `delete`, `groupBy` or aggregates.
- **Column names:** `snake_case` of the property names; custom mapping isn't configurable.
- **`o == null`** for a row from a `LEFT JOIN` checks `o.id IS NULL`, i.e. it assumes an `id` primary key.
- **Hand-built trees** that access a property of a captured object (without an evaluator) are rejected: there's no way to evaluate them without reflection.
