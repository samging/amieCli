/*
 * amie: an interpreter for Kotlin scripts that train, ask, group and serve intent models
 * Copyright (C) 2026 the amie authors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details. You should have received a copy of the GNU General Public License along with this program. If not, see
 * <https://www.gnu.org/licenses/>.
 */
package org.amie.cli

import org.example.project.amp.ModelsMigration
import org.example.project.getDirectory
import java.io.File

/**
 * The AMP folder the interpreter works in: --amp, else AMIE_AMP_DIR, else the user's data folder
 * (`~/Library/Application Support/amie pilot/AMP` on macOS, `%APPDATA%\amie pilot\AMP` on Windows,
 * `$XDG_DATA_HOME/amie-pilot/AMP` or `~/.local/share/amie-pilot/AMP` elsewhere).
 */
object AmpFolder {
    /** Settles the folder before anything reads it; the models move into AMP/models first */
    fun resolve(chosen: String?): File {
        when {
            chosen != null -> System.setProperty("amie.amp.dir", File(chosen).absolutePath)
            System.getenv("AMIE_AMP_DIR").isNullOrBlank() && System.getProperty("amie.amp.dir") == null -> System.setProperty("amie.amp.dir", appDataFolder.path)
        }
        val dir = File(getDirectory().toString())
        if (System.getenv("DJL_CACHE_DIR") == null) System.setProperty("DJL_CACHE_DIR", File(dir, "cache/djl").path)
        ModelsMigration.runOnce()
        return dir
    }

    /** The default AMP folder on this machine, in the user's data folder */
    val appDataFolder: File get() {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            os.contains("mac") -> File(home, "Library/Application Support/amie pilot")
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "amie pilot")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "amie-pilot")
        }
        return File(base, "AMP")
    }

    /** The folder in use */
    val dir: File get() = File(getDirectory().toString())
}
