pluginManagement {
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
