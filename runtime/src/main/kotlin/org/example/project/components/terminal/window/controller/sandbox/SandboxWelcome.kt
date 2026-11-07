package org.example.project.components.terminal.window.controller.sandbox

import org.example.project.AmieBuild
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The lines at the top of the sandbox's console, as Python's interpreter greets:
 *
 *     Amie pilot 1.0.0 (default, Nov  6 2026, 02:22:05) [Kotlin 2.1.20, JDK 21.0.11] on darwin
 *     Type "help", "sessions", "terminate all" or log("hi") for more information.
 *
 * and the input's >>> under them. They are no log line: Clear and a run leave them, and they never open the console.
 */
internal fun sandboxWelcome(
    version: String = AmieBuild.version,
    builtAt: Instant? = AmieBuild.builtAt,
    javaVersion: String = System.getProperty("java.version").orEmpty(),
    osName: String = System.getProperty("os.name").orEmpty(),
    lightweight: Boolean = AmieBuild.lightweight,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    // Python's own form: the month's day padded to two, "Aug  5 2026, 10:29:49"
    val built = builtAt?.let { ", " + DateTimeFormatter.ofPattern("MMM ppd yyyy, HH:mm:ss", Locale.ENGLISH).withZone(zone).format(it) }.orEmpty()
    return listOf(
        "Amie pilot $version (${if (lightweight) "lightweight" else "default"}$built) [Kotlin ${KotlinVersion.CURRENT}, JDK $javaVersion] on ${platform(osName)}",
        "Type \"help\", \"sessions\", \"terminate all\" or log(\"hi\") for more information.",
    ).joinToString("\n")
}

/** The system as Python's sys.platform names it */
internal fun platform(osName: String): String = osName.lowercase().let {
    when {
        "mac" in it || "darwin" in it -> "darwin"
        "win" in it -> "win32"
        "linux" in it -> "linux"
        else -> it.replace(" ", "")
    }
}
