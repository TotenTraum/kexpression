plugins {
    kotlin("jvm")
}

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
