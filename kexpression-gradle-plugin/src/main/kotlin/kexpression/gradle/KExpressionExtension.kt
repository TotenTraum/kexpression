package kexpression.gradle

import org.gradle.api.provider.Property

/**
 * ```kotlin
 * kexpression {
 *     addRuntimeDependency = false  // declare kexpression-runtime yourself
 * }
 * ```
 */
interface KExpressionExtension {
    /** Adds `kexpression-runtime` of the plugin's version to `commonMain` (KMP) or `implementation`. Default: `true`. */
    val addRuntimeDependency: Property<Boolean>
}
