import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.publishPlugin)
}

description = "Runtime of kexpression: expression tree nodes, expr {}, @Quoted, evaluation policies"

kotlin {
    jvm()
    js { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { nodejs() }
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // nonJs = everything except JS: there lambdas are wrapped in classes implementing the function type.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("nonJs") {
                withJvm()
                withWasmJs()
                group("native") { withNative() }
            }
        }
    }
}
