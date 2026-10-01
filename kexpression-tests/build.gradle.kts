import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Общие тесты компиляторного плагина и runtime: один и тот же commonTest на JVM, JS, Wasm и Native.
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

// Плагин подключается ко всем компиляциям: JVM/JS/Wasm и отдельно Native.
dependencies {
    "kotlinCompilerPluginClasspath"(project(":kexpression-compiler-plugin"))
    "kotlinNativeCompilerPluginClasspath"(project(":kexpression-compiler-plugin"))
}

// Изменения в плагине должны перекомпилировать тесты.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool<*>>().configureEach {
    inputs.files(project(":kexpression-compiler-plugin").tasks.named("jar"))
}
