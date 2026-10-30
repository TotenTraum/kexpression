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
}

rootProject.name = "kexpression"

// Published libraries
include("kexpression-runtime", "kexpression-compiler-plugin")

// Tests and examples (not published)
include("kexpression-tests", "sample")
