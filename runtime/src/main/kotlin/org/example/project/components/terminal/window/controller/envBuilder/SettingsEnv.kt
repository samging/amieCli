package org.example.project.components.terminal.window.controller.envBuilder

/** The env a script asks for: `environment(SettingsEnv("prod"))`, the profile optional through the prelude's factory */
data class SettingsEnv(
    val envName: String,
    val activeProfile: String
)

/** What a script's `implementation(BuildSettings(...))` carries */
data class BuildSettings(
    val customKeys: Map<String, String>
)
