package sample

import kexpression.*

enum class Role { ADMIN, USER }

data class User(val id: Long, val name: String, val age: Int, val email: String?, val role: Role)

data class Order(val id: Long, val userId: Long, val productId: Long, val total: Int)

data class Product(val id: Long, val title: String)

fun main() {
    // 1. Expression<Func<int, int, int>> add = (a, b) => a + b * 2;
    val add: Expr<(Int, Int) -> Int> = expr { a, b -> a + b * 2 }
    println("Дерево:     $add")
    println("Тело:       ${add.body}")
    println("Оператор:   ${(add.body as BinaryExpression).operator}")
    println("Вызов:      ${add(1, 3)}")

    // 2. LINQ-подобный запрос: лямбды транслируются в SQL по дереву выражения.
    val minAge = 18
    val domain = "@example.com"
    val sql = from<User>("users")
        .where { it.age >= minAge && it.role == Role.ADMIN }
        .where { it.email != null && it.email.endsWith(domain) || it.name.startsWith("root") }
        .orderByDescending { it.age }
        .toSql()
    println(sql.sql)
    println(sql.parameters)

    // 3. Join: i-й параметр лямбды — i-я таблица запроса.
    val join = from<User>("users", "us")
        .join(table<Order>("orders", "o")) { user, o -> user.id == o.userId }
        .leftJoin(table<Product>("products", "p")) { _, o, p -> o.productId == p.id }
        .where { u, o, p -> o.total > 100 && p?.title != null && u.role == Role.USER }
        .orderByDescending { _, o, _ -> o.total }
        .toSql()
    println(join.sql)
    println(join.parameters)

    // 4. Проекция: колонки берутся из аргументов конструктора.
    val report = from<User>("users", "u")
        .join(table<Order>("orders", "o")) { u, o -> u.id == o.userId }
        .where { _, o -> o.total > 100 }
        .select { u, o -> OrderReport(u.name, o.total * 9 / 10) }
        .toSql()

    println(report.sql)
    println(report.columns)
}

data class OrderReport(val customer: String, val discountedTotal: Int)
