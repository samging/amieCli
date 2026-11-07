package org.example.project.components.terminal.window.controller.pluginRules

interface Standalone {
    fun postApi(vararg args: String, pluginAccess: String)
    fun getApi(pluginAccess: String)
    fun putApi(vararg args: String, pluginAccess: String)
    fun deleteApi(vararg args: String, pluginAccess: String)
    fun patchApi(vararg args: String, pluginAccess: String)
}