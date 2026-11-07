package org.example.project

/** Which build of the app this is. The lightweight one (the Raspberry Pi package, and the one an Apple Silicon Mac builds) has no TensorFlow 1.15, and Settings says so */
object AmieBuild {
    // Set by the build's launcher (-Damie.lightweight=true) when it was made with -Pamie.lightweight, on arm64 Linux or on an Apple Silicon Mac
    val lightweight: Boolean = System.getProperty("amie.lightweight") == "true"
    // Set by the build's launcher (-Damie.version=1.0.0), the packages' version; a run with no launcher says dev
    val version: String = System.getProperty("amie.version")?.takeIf { it.isNotBlank() } ?: "dev"
    /** When the app's code was built: its jar's time, or the class's when it runs from classes */
    val builtAt: java.time.Instant? by lazy {
        runCatching {
            val at = java.io.File(AmieBuild::class.java.protectionDomain.codeSource.location.toURI())
            val file = if (at.isDirectory) java.io.File(at, "org/example/project/AmieBuild.class") else at
            file.takeIf { it.exists() }?.let { java.time.Instant.ofEpochMilli(it.lastModified()) }
        }.getOrNull()
    }
}
