plugins {
    kotlin("jvm")
    id("ru.ttraum.kexpression")
    application
}

application { mainClass.set("sample.MainKt") }

dependencies {
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    testImplementation("com.h2database:h2:2.3.232")
}

tasks.test { useJUnitPlatform() }
