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

import org.example.project.components.terminal.window.controller.modules.ensureDummyModule
import org.example.project.components.terminal.window.controller.modules.ensureSampleData
import org.example.project.components.terminal.window.controller.sandbox.SandboxExamples
import org.example.project.components.terminal.window.controller.script.ScriptTemplate
import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import java.io.File

/**
 * The starter scripts, what amie can do with each ready to run: the app's examples, seeded into `AMP/templates` as the
 * app does it (source "starter", tagged with their group), with what they run on beside them, the model called dummy
 * and `sample-intents.csv`, so every one runs on a fresh AMP folder.
 */
object Starters {
    val groups: List<String> get() = SandboxExamples.groups

    fun inGroup(group: String): List<SandboxExamples.Example> = SandboxExamples.all.filter { it.group == group }

    /** The starter of [title], the case apart, under its title now or one it had before */
    fun find(title: String): SandboxExamples.Example? =
        SandboxExamples.all.firstOrNull { it.title.equals(title, ignoreCase = true) || it.formerly.any { old -> old.equals(title, ignoreCase = true) } }

    /** Seeds what the starters need, once: the templates, the dummy model, the sample csv. Nothing on a folder that has them. */
    fun seed() {
        ScriptTemplates.seed(File(AmpFolder.dir, "pluginDir/main.kts").takeIf { it.isFile }?.readText())
        ensureSampleData()
        ensureDummyModule()
    }

    /** The template of [name] (a starter's title or a saved template's name, the case apart, or its id), seeded first; null when there is none */
    fun template(name: String): ScriptTemplate? {
        seed()
        return ScriptTemplates.list().firstOrNull { it.id == name || it.name.equals(name, ignoreCase = true) }
    }
}
