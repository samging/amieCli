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

import org.example.project.AmieBuild

/** What the prompt opens with, and what --version says */
object Banner {
    val ASCII: String = """
 ______     __    __     __     ______
/\  __ \   /\ "-./  \   /\ \   /\  ___\
\ \  __ \  \ \ \-./\ \  \ \ \  \ \  __\
 \ \_\ \_\  \ \_\ \ \_\  \ \_\  \ \_____\
  \/_/\/_/   \/_/  \/_/   \/_/   \/_____/
""".trimStart('\n').trimEnd()

    const val LICENSE_LINE = "License GPL-3.0-or-later: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>. This is free software: you are free to change and redistribute it. There is no warranty."

    /** The script compiler's version, the Kotlin a script is written in; the standard library's when it can't be read */
    private val kotlinVersion: String = runCatching {
        Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion").getField("VERSION").get(null) as String
    }.getOrDefault(KotlinVersion.CURRENT.toString())

    /** `amie 1.0.0 (Kotlin 2.1.0) on OpenJDK 64-Bit Server VM 21.0.5, Mac OS X aarch64` */
    val versionLine: String get() = "amie ${AmieBuild.version} (Kotlin $kotlinVersion) on " +
        "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

    /** The banner, the version line and how to get help, as python's greeting goes */
    fun greetingFor(amp: String): String = Style.pride(ASCII) + "\n" + versionLine + "\n" +
        Style.dim("Type :help for the commands, :api for the script's functions, :learn or :docs for the book, :quit to leave. AMP: $amp")
}
