plugins {
    kotlin("jvm")
    alias(libs.plugins.publishPlugin)
}

description = "Kotlin compiler plugin that turns lambda literals into kexpression trees at compile time"

dependencies {
    compileOnly(kotlin("compiler-embeddable"))
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.util.UnsafeDuringIrConstructionAPI")
        optIn.add("org.jetbrains.kotlin.config.MessageCollectorAccess")
    }
}
