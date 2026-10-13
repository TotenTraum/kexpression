import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Shared tests of the compiler plugin and the runtime: the same commonTest on JVM, JS, Wasm and Native.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    js { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs() }
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(project(":kexpression-runtime"))
            implementation(kotlin("test"))
        }
    }
}

// The plugin is attached to every compilation: JVM/JS/Wasm, and Native separately.
dependencies {
    "kotlinCompilerPluginClasspath"(project(":kexpression-compiler-plugin"))
    "kotlinNativeCompilerPluginClasspath"(project(":kexpression-compiler-plugin"))
}

// Changes to the plugin must recompile the tests.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool<*>>().configureEach {
    inputs.files(project(":kexpression-compiler-plugin").tasks.named("jar"))
}
