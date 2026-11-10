package org.example.project.components.terminal.window.controller.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The bundle's `bundle.json`: what went in, what a deployer has to supply and what the runner needs to know */
@Serializable
data class BundleManifest(
    val format: Int = FORMAT,
    val name: String,
    // main.kts, a template's id or a file, as it was chosen
    val source: String,
    val scriptSha256: String,
    val exportedAt: String,
    val javaMin: Int = JAVA_MIN,
    val javaMax: Int = JAVA_MAX,
    val platforms: List<String> = PLATFORMS,
    // "compiled": lib/scripts.jar holds the script and its templates as classes, and no compiler goes along;
    // "script": the Kotlin compiler goes along and compiles main.kts when the bundle starts
    val mode: String = MODE_SCRIPT,
    // In a compiled bundle, the class of each entry point: "main" and each template's id
    val classes: Map<String, String> = emptyMap(),
    // What each template of [templates] is called, by its id, for --template and --list
    val templateNames: Map<String, String> = emptyMap(),
    val env: BundleEnv,
    val models: List<BundleModel> = emptyList(),
    val builtModels: List<String> = emptyList(),
    // Encoder folders under models/bert/
    val encoders: List<String> = emptyList(),
    val nerModel: Boolean = false,
    val groups: List<String> = emptyList(),
    val data: List<String> = emptyList(),
    val databases: List<BundleDatabase> = emptyList(),
    val packages: List<String> = emptyList(),
    // The ids of the templates it starts, in AMP/templates/
    val templates: List<String> = emptyList(),
    val secrets: List<BundleSecret> = emptyList(),
    // Environment variables and system properties the script reads that aren't secrets
    val envVars: List<String> = emptyList(),
    // A path or a jar spec the script names, and where the file is under AMP/; the runner puts the real path in its strings
    val paths: Map<String, String> = emptyMap(),
    // AMP/relations.json with paths relative to AMP/, written again with this machine's paths at the start of a run
    val relations: Map<String, String> = emptyMap(),
    val leftOut: List<String> = emptyList(),
    // What the scan could not find, with Export anyway or --allow-missing; the script fails or does less without it
    val missing: List<String> = emptyList(),
    // Exported without its jars (--no-jars): each file the bundle needs put in place, also listed in require.txt
    val required: List<BundleRequirement> = emptyList(),
    val notes: List<String> = emptyList(),
    val files: List<BundleFile> = emptyList(),
) {
    val compiled: Boolean get() = mode == MODE_COMPILED

    /** The Java versions it runs on, as the README and the start scripts say them */
    val javaRange: String get() = if (javaMax >= JAVA_ANY) "$javaMin or newer" else "$javaMin to $javaMax"

    companion object {
        const val FORMAT = 1
        const val JAVA_MIN = 21
        // Kotlin 2.1's script compiler stops on Java 25 with an IllegalArgumentException of the version
        const val JAVA_MAX = 24
        // A compiled bundle has no compiler to stop on a newer Java: anything from JAVA_MIN runs it
        const val JAVA_ANY = 99
        const val FILE_NAME = "bundle.json"
        const val MODE_COMPILED = "compiled"
        const val MODE_SCRIPT = "script"
        val PLATFORMS = listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64")

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun read(file: File): BundleManifest = json.decodeFromString(serializer(), file.readText())
        fun write(manifest: BundleManifest, file: File) = file.writeText(json.encodeToString(serializer(), manifest))
    }
}

@Serializable
data class BundleEnv(val name: String, val profile: String, val declared: List<String> = emptyList(), val packages: Int = 0)

@Serializable
data class BundleModel(val name: String, val version: Int? = null, val encoder: String? = null, val labels: Int = 0)

/** Metadata only: a password is a `$ENV:NAME` by now, and [secrets] names what it reads */
@Serializable
data class BundleDatabase(val name: String, val url: String, val user: String, val secrets: List<String> = emptyList())

/** Something the deployer supplies: an environment variable, or a line of secrets.env */
@Serializable
data class BundleSecret(val name: String, val why: String, val required: Boolean = true)

@Serializable
data class BundleFile(val path: String, val size: Long, val sha256: String)

/** A file left out of the bundle on purpose that it needs to run: [path] under the bundle, [what] names it (a Maven coordinate, a package) */
@Serializable
data class BundleRequirement(val path: String, val what: String)
