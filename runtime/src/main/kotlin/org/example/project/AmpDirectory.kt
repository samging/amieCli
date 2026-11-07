package org.example.project

import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The AMP folder, where everything the app writes lives. A packaged app and a Gradle run (`./gradlew :desktopApp:run`)
 * both have the app's resources folder (`compose.application.resources.dir`, which the run task passes too), so both
 * keep AMP in the user's application-data folder (`~/Library/Application Support/amie pilot/AMP` on macOS,
 * `%APPDATA%\amie pilot\AMP` on Windows, `$XDG_DATA_HOME/amie-pilot/AMP` or `~/.local/share/amie-pilot/AMP`
 * elsewhere), seeded on the first start from the `AMP/` folder shipped in the app's resources (made from
 * `amieApplication/desktopApp/AMP/`). Started without that folder, such as `MainKt` run straight from the IDE, it
 * keeps `AMP/` in the working directory instead.
 * `AMIE_AMP_DIR` names another folder in every case; the tests point it at `build/test-amp`. The `amie.amp.dir` system
 * property does the same where the environment is not the program's to set: a bundle's runner uses it for its own AMP.
 */
fun getDirectory(): Path = ampDirectory.toOkioPath()

private val ampDirectory: File by lazy {
    val override = (System.getenv("AMIE_AMP_DIR")?.takeIf { it.isNotBlank() } ?: System.getProperty("amie.amp.dir"))?.let(::File)
    val resources = System.getProperty("compose.application.resources.dir")?.let(::File)
    val dir = when {
        override != null -> override
        resources == null -> File(System.getProperty("user.dir"), "AMP")
        else -> {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            val base = when {
                os.contains("mac") -> File(home, "Library/Application Support/amie pilot")
                os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "amie pilot")
                else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "amie-pilot")
            }
            File(base, "AMP")
        }
    }
    if (!dir.isDirectory) {
        dir.mkdirs()
        // First start with the app's resources (a packaged app or a Gradle run): the shipped AMP (backend address,
        // plugin dir, sample data) is copied in once
        val seed = resources?.let { File(it, "AMP") }
        if (seed != null && seed.isDirectory) runCatching { seed.copyRecursively(dir, overwrite = false) }
    }
    dir
}
