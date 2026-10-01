# kexpression

Аналог C# `Expression<Func<...>>` для Kotlin: компиляторный плагин (K2, IR) **во время компиляции**
превращает лямбда-литерал в код, строящий дерево выражения. Рефлексии и разбора байткода в рантайме нет.

```kotlin
// C#: Expression<Func<int, int, int>> add = (a, b) => a + b * 2;
val add: Expr<(Int, Int) -> Int> = expr { a, b -> a + b * 2 }
add.body          // BinaryExpression(PLUS, a, BinaryExpression(TIMES, b, 2))
add.compile()(1, 3) // 7 — как Compile(), но лямбда уже скомпилирована статически
```

LINQ-подобные API — через аннотацию `@Quoted` на параметре функционального типа:

```kotlin
fun where(@Quoted predicate: (T) -> Boolean) = ... predicate.toExpression() ...

from<User>("users").where { it.age >= minAge && it.email?.endsWith(domain) == true }
```

Join'ы: i-й параметр лямбды соответствует i-й таблице запроса (`Query<A>` → `Query2<A, B>` → `Query3<A, B, C>`):

```kotlin
from<User>("users", "u")
    .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
    .leftJoin(table<Product>("products", "p")) { _, o, p -> o.productId == p.id }   // дальше p: Product?
    .where { _, o, p -> o.total > minTotal && p?.title != null }
// SELECT * FROM users u JOIN orders o ON u.id = o.user_id
//   LEFT JOIN products p ON o.product_id = p.id WHERE o.total > ? AND p.title IS NOT NULL
```

Проекция — колонки берутся из аргументов конструктора (имена параметров есть в `NewExpression.parameterNames`):

```kotlin
    .select { u, o -> OrderReport(u.name, o.total * 9 / 10) }
// SELECT u.name AS customer, o.total * 9 / 10 AS discounted_total FROM ...
```

Кэш SQL по форме запроса — `toCachedSql()` вместо `toSql()`: текст строится один раз на форму,
значения захваченных переменных извлекаются заново и нумеруются `$1, $2, ...`:

```kotlin
fun adults(minAge: Int, role: Role) = from<User>("users")
    .where { it.age >= minAge && it.role == role && it.age < minAge + 50 }
    .toCachedSql()
// SELECT * FROM users WHERE age >= $1 AND role = $2 AND age < $1 + 50
// adults(18, ADMIN) → [18, ADMIN] (промах), adults(30, USER) → [30, USER] (попадание)
```

Выполнение через JDBC — `toList(connection)` и ленивый закрываемый `toSequence(connection)`;
объекты собираются по дереву проекции (конструктор, `Pair`, сущность целиком или одно значение):

```kotlin
val rows: List<Row> = from<User>("users", "u")
    .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
    .select { u, o -> Row(u.name, o.total) }
    .toList(connection)

from<User>("users").toSequence(connection).use { users -> users.first { it.age > 18 } }
```

## Модули
- `kexpression-runtime` — узлы дерева (`Expression` и наследники), `Expr<F>`, `expr {}`, `@Quoted`.
  Kotlin Multiplatform: JVM, JS, Wasm, macOS/iOS, Linux, Windows.
- `kexpression-compiler-plugin` — IR-плагин (`IrGenerationExtension`): заменяет `expr { }` на
  `quoteExpr(lambda, <дерево>)`, а лямбды в `@Quoted`-параметрах — на `quoteFunction(lambda, <дерево>)`.
- `kexpression-tests` — общие тесты плагина (`commonTest`), запускаются на JVM, JS, Wasm и Native.
- `sample` — мини SQL-провайдер (только JVM) (`Query.where/orderBy` → `SELECT ... WHERE ...`) и тесты.

## Kotlin Multiplatform
Плагин работает с IR, который одинаков для всех бэкендов, поэтому его достаточно подключить ко всем компиляциям:

```kotlin
dependencies {
    "kotlinCompilerPluginClasspath"(project(":kexpression-compiler-plugin"))        // JVM, JS, Wasm
    "kotlinNativeCompilerPluginClasspath"(project(":kexpression-compiler-plugin"))  // Native
}
```

На JS классу нельзя реализовать функциональный тип, поэтому `@Quoted`-лямбда не оборачивается:
дерево хранится в свойстве самой JS-функции (и ограничения в 4 параметра там нет).

```
./gradlew :kexpression-tests:jvmTest :kexpression-tests:jsNodeTest :kexpression-tests:wasmJsNodeTest
./gradlew :kexpression-tests:macosArm64Test   # нужен полный Xcode
```

## Поддерживается
константы, параметры, замыкания (захват по значению, `this`), арифметика/битовые операции,
`== != === < <= > >=` (включая `Comparable`), `&& || !`, `!!`, `?:`, `?.`, `if`/`when`, `is/as/as?`,
свойства, вызовы функций/extension-функций, конструкторы, строковые шаблоны, vararg, вложенные лямбды,
локальные `val` (подставляются). Как и в C#, тело должно быть выражением: `var`, циклы, присваивания
дают ошибку компиляции `[kexpression] Cannot convert lambda to expression tree`.

## Запуск
```
./gradlew :sample:test
./gradlew :sample:run
```

Подключение плагина в своём модуле:
```kotlin
dependencies {
    implementation(project(":kexpression-runtime"))
    kotlinCompilerPluginClasspath(project(":kexpression-compiler-plugin"))
}
```
