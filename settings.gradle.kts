pluginManagement {
    // Published separately; included so that kexpression-tests and sample use the plugin as a consumer would.
    includeBuild("kexpression-gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // CI tests other Kotlin releases: ./gradlew -PkotlinVersion=2.5.0-Beta2 ...
    versionCatalogs {
        // gradle/libs.versions.toml is imported into "libs" automatically; only the override is added here.
        create("libs") {
            providers.gradleProperty("kotlinVersion").orNull?.let { version("kotlin", it) }
        }
    }
}

rootProject.name = "kexpression"

// Published libraries
include("kexpression-runtime", "kexpression-compiler-plugin")

// Tests and examples (not published)
include("kexpression-tests", "sample")
