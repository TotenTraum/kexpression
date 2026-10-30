// A separate (included) build: the root build applies this plugin to its own modules (kexpression-tests, sample),
// and a plugin can't be applied from a subproject of the same build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            // Same override as in the root build: the plugin must be compiled against the tested Kotlin.
            providers.gradleProperty("kotlinVersion").orNull?.let { version("kotlin", it) }
        }
    }
}

rootProject.name = "kexpression-gradle-plugin"
