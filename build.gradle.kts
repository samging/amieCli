plugins {
    // Declared once here, apply false, so each module's classloader shares them
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}
