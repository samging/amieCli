rootProject.name = "amieCli"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://packages.jetbrains.team/maven/p/kotlinx-dl/maven") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The engine, and the interpreter on top of it
include(":runtime")
include(":amie")
