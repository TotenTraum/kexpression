plugins {
    kotlin("jvm")
    application
}

application { mainClass.set("sample.MainKt") }

dependencies {
    implementation(project(":kexpression-runtime"))
    implementation(kotlin("reflect"))
    // Attach our IR plugin to the main and test compilations.
    kotlinCompilerPluginClasspath(project(":kexpression-compiler-plugin"))

    testImplementation(kotlin("test"))
    testImplementation("com.h2database:h2:2.3.232")
}

tasks.test { useJUnitPlatform() }
