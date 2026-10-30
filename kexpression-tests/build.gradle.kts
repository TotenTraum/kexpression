import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Shared tests of the compiler plugin and the runtime: the same commonTest on JVM, JS, Wasm and Native.
plugins {
    kotlin("multiplatform")
    id("ru.ttraum.kexpression")
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
            implementation(kotlin("test"))
        }
    }
}

// Changes to the plugin must recompile the tests. A prebuilt plugin (binary compatibility runs) is used as is.
val prebuiltCompilerPlugin = rootProject.extra["prebuiltCompilerPlugin"] as File?
tasks.withType<org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompileTool<*>>().configureEach {
    inputs.files(prebuiltCompilerPlugin ?: project(":kexpression-compiler-plugin").tasks.named("jar"))
}
