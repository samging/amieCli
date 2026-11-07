package org.example.project.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.getDirectory
import java.io.File

/** The side of the sandbox a tool window is docked at */
@Serializable
enum class ToolDock { Left, Right, Bottom }

/** Everything the Settings page and the sandbox's own switches can change */
@Serializable
data class Settings(
    // How large the whole UI is drawn, 1.0 being the system's own size
    val uiScale: Float = 1f,
    // Where the sandbox's tool windows stand, and how big the docks are, in dp
    val consoleDock: ToolDock = ToolDock.Bottom,
    val templatesDock: ToolDock = ToolDock.Left,
    // The text model handleBy(message) asks; empty, the first of AMP/models/text
    val assistantModel: String = "",
    val leftDockWidth: Float = 300f,
    val rightDockWidth: Float = 420f,
    val bottomDockHeight: Float = 260f,
    // The Models page's navigation sidebar, hidden or shown from its tab strip
    val modelsSidebar: Boolean = true,
    // The first steps over the Models tab, dismissed there and shown again from the Settings page
    val firstStepsHidden: Boolean = false,
    // The sandbox editor's text size in points, its lines spaced to match; ⌥⌘+ / ⌥⌘− in the editor change it too
    val editorFontSize: Int = AppSettings.DEFAULT_EDITOR_FONT,
    // Enter in the sandbox editor re-indents the whole script as it starts the new line
    val reindentOnEnter: Boolean = true,
    // The run's status (Running 3s, Finished in 0s) and the figures of what it used, over the console and the editor;
    // off, the console strip has only its toggles and the console only what the script logs
    val runDetails: Boolean = false,
)

/**
 * The app's settings, kept in `AMP/settings.json` and read once at start, and again by [reload]. Changing a value
 * applies at once (the window reads [current]) and writes the file, so it holds on the next start.
 */
object AppSettings {
    const val MIN_SCALE = 0.7f
    const val MAX_SCALE = 1.5f
    val presets = listOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f)
    const val DEFAULT_EDITOR_FONT = 14
    val editorFonts = 11..20

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(getDirectory().toString(), "settings.json")

    private val _current = MutableStateFlow(load())
    val current: StateFlow<Settings> = _current.asStateFlow()

    private fun load(): Settings = runCatching { json.decodeFromString<Settings>(file.readText()) }.getOrDefault(Settings())
        .let { it.copy(uiScale = it.uiScale.coerceIn(MIN_SCALE, MAX_SCALE), editorFontSize = it.editorFontSize.coerceIn(editorFonts)) }

    /** Takes in a settings.json something else wrote (Restore), which the next change would otherwise write over */
    fun reload() { _current.value = load() }

    fun setUiScale(scale: Float) = update { it.copy(uiScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)) }
    fun setConsoleDock(dock: ToolDock) = update { it.copy(consoleDock = dock) }
    fun setTemplatesDock(dock: ToolDock) = update { it.copy(templatesDock = dock) }
    fun setAssistantModel(name: String) = update { it.copy(assistantModel = name) }
    fun setDockSizes(left: Float, right: Float, bottom: Float) = update { it.copy(leftDockWidth = left, rightDockWidth = right, bottomDockHeight = bottom) }
    fun setModelsSidebar(shown: Boolean) = update { it.copy(modelsSidebar = shown) }
    fun setFirstStepsHidden(hidden: Boolean) = update { it.copy(firstStepsHidden = hidden) }
    fun setEditorFontSize(size: Int) = update { it.copy(editorFontSize = size.coerceIn(editorFonts)) }
    fun setReindentOnEnter(on: Boolean) = update { it.copy(reindentOnEnter = on) }
    fun setRunDetails(on: Boolean) = update { it.copy(runDetails = on) }

    private fun update(change: (Settings) -> Settings) {
        val next = change(_current.value)
        _current.value = next
        runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(next)) }
            // Saved here first; a logged-in person's settings go to the cloud a moment later, from a background coroutine
            .onSuccess { CloudSave.saved("settings.json") }
    }
}
