package org.example.project.components.terminal.window.controller.export

import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan

/**
 * What a script reaches, read from its text before anything of it runs: what the export has to carry. Literal names
 * are found through the calls of the script API; a name the text can't tell (`initMod(pick())`) is a [Dynamic], which
 * the export refuses until a `// export: model intents` line names what it will be. The directive lines are
 * `// export: model|group|package|database|file|template <name>`.
 */
data class ScriptNeeds(
    val env: EnvPlan,
    val models: Set<String> = emptySet(),
    // Written by the script itself (modelBuilder, groups.create): fine when there is no copy to carry yet
    val builtModels: Set<String> = emptySet(),
    val createdGroups: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
    val packages: Set<String> = emptySet(),
    val databases: Set<String> = emptySet(),
    // Started with coroutineTemplate or template: named as it was saved or by its id
    val templates: Set<String> = emptySet(),
    // What it runs through shell and shellScript, as the script says it
    val shellCommands: Set<String> = emptySet(),
    // A file API was given this path: missing, the script can't work
    val files: Set<String> = emptySet(),
    // Any other string of the script; a path only when such a file is there
    val literals: Set<String> = emptySet(),
    val dynamic: List<Dynamic> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val envVars: Set<String> = emptySet(),
    val properties: Set<String> = emptySet(),
    val secretLines: List<Int> = emptyList(),
    val usesEncoder: Boolean = false,
    val usesNer: Boolean = false,
    val usesBackend: Boolean = false,
    // Every configured connection is in view (databases.print(), a name the text can't tell)
    val allDatabases: Boolean = false,
    val usesQueue: Boolean = false,
    val usesTrainingData: Boolean = false,
    val usesCorePlugins: Boolean = false,
) {
    /** This and what the template [name] it starts reaches, in this script's env; what can't go is told with its name */
    fun plus(started: ScriptNeeds, name: String) = copy(
        models = models + started.models,
        builtModels = builtModels + started.builtModels,
        createdGroups = createdGroups + started.createdGroups,
        groups = groups + started.groups,
        packages = packages + started.packages,
        databases = databases + started.databases,
        shellCommands = shellCommands + started.shellCommands,
        files = files + started.files,
        literals = literals + started.literals,
        dynamic = dynamic + started.dynamic.map { it.copy(call = "${it.call} in template '$name'") },
        unsupported = unsupported + started.unsupported.map { "$it, in template '$name'" },
        envVars = envVars + started.envVars,
        properties = properties + started.properties,
        usesEncoder = usesEncoder || started.usesEncoder,
        usesNer = usesNer || started.usesNer,
        usesBackend = usesBackend || started.usesBackend,
        allDatabases = allDatabases || started.allDatabases,
        usesQueue = usesQueue || started.usesQueue,
        usesTrainingData = usesTrainingData || started.usesTrainingData,
        usesCorePlugins = usesCorePlugins || started.usesCorePlugins,
    )
}

/** A name only known when the script runs: [kind] is model, group, package, database, template or file */
data class Dynamic(val kind: String, val call: String, val line: Int)

object ScriptScan {
    // Each takes a model's name first
    private val modelCalls = setOf(
        "initMod", "sessionMod", "sharedModel", "validateMetrics", "metricsChart", "metricsHistory", "confusionMatrix", "mistakes", "suspects",
        "calibrationChart", "trainingCurves", "fineTune", "calibrate", "versions", "rollback", "textsToLabel", "teach", "queue",
        "planFineTune", "regradeQueue", "serve", "stopServing", "servingUrl", "accessLog",
    )

    // These read and write what is in the model's folder and never run it, so they need no encoder
    private val fileOnlyModelCalls = setOf(
        "validateMetrics", "metricsChart", "metricsHistory", "confusionMatrix", "mistakes", "suspects", "calibrationChart",
        "trainingCurves", "versions", "rollback", "teach", "queue", "stopServing", "servingUrl", "accessLog",
    )

    private val queueCalls = setOf("teach", "queue", "regradeQueue", "planFineTune", "fineTune")
    private val trainingDataCalls = setOf("fineTune", "planFineTune", "calibrate")
    private val backendCalls = setOf("saveState", "loadState", "listStates")

    // Path first; relate(train, "test.csv") and data.relate("test.csv") take it second and first, sorted out below
    private val fileCalls = setOf("source", "sourceFile", "importModelOnnx")

    // Classes a bundle doesn't carry, since nothing in a script's run needs them unless the script imports them itself
    private val leftOutPackages = listOf(
        "androidx." to "Compose", "org.jetbrains.compose" to "Compose", "org.jetbrains.skia" to "Compose", "org.jetbrains.jewel" to "Compose",
        "org.tensorflow" to "TensorFlow", "org.jetbrains.kotlinx.dl" to "KotlinDL", "org.gradle" to "the Gradle API", "groovy." to "Groovy",
    )

    private val call = Regex("""(?<![\w.])((?:amie\.)?(?:(packages|groups|databases|sessions|embeddingCache)\.)?)([A-Za-z_]\w*)\s*\(""")
    private val relateCall = Regex("""\.relate\s*\(""")
    private val constant = Regex("""\b(?:val|const\s+val)\s+([A-Za-z_]\w*)\s*(?::\s*String\s*)?=\s*"""")
    private val directive = Regex("""^//\s*export:\s*(model|group|package|database|file|template)\s+(.+?)\s*$""")
    private val importLine = Regex("""^\s*import\s+([\w.]+)""", RegexOption.MULTILINE)
    private val getenv = Regex("""System\s*\.\s*getenv\s*\(""")
    private val getProperty = Regex("""System\s*\.\s*getProperty\s*\(""")
    private val secretWord = Regex("""(?i)(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|_key$)""")
    private val assignment = Regex("""(?i)(?:val|var)?\s*[\w.]*(password|passwd|secret|apikey|api_key|token)\w*\s*=\s*"""")
    private val credentialShape = Regex("""^(sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\..*|xox[bp]-[A-Za-z0-9-]{10,})$""")

    fun scan(code: String): ScriptNeeds {
        val text = Lexed.of(code)
        val masked = text.masked
        val constants = constant.findAll(masked).mapNotNull { found ->
            val literal = text.literalAt(found.range.last) ?: return@mapNotNull null
            // `val x = "web-" + id` says nothing of what x is
            val rest = masked.substring(literal.end.coerceAtMost(masked.length)).substringBefore('\n').trim().removePrefix("\"").trim().removeSuffix(";")
            if (literal.templated || rest.isNotEmpty()) null else found.groupValues[1] to literal.value
        }.toMap()

        val models = linkedSetOf<String>()
        val built = linkedSetOf<String>()
        val createdGroups = linkedSetOf<String>()
        val groups = linkedSetOf<String>()
        val packages = linkedSetOf<String>()
        val databases = linkedSetOf<String>()
        val templates = linkedSetOf<String>()
        val shellCommands = linkedSetOf<String>()
        val files = linkedSetOf<String>()
        val dynamic = mutableListOf<Dynamic>()
        val unsupported = mutableListOf<String>()
        val envVars = linkedSetOf<String>()
        val properties = linkedSetOf<String>()
        var usesEncoder = false
        var usesNer = false
        var usesBackend = false
        var allDatabases = false
        var usesQueue = false
        var usesTrainingData = false
        var usesCorePlugins = false

        fun modelOrJar(name: String, api: String) {
            if (isJar(name)) packages += name else {
                models += name
                if (api !in fileOnlyModelCalls) usesEncoder = true
            }
            if (api in queueCalls) usesQueue = true
            if (api in trainingDataCalls) usesTrainingData = true
        }

        call.findAll(masked).forEach { found ->
            val receiver = found.groupValues[2]
            val name = found.groupValues[3]
            val open = found.range.last
            val line = text.lineOf(found.range.first)
            val args = text.arguments(open)
            val first = args.firstOrNull()?.let { text.argumentName(it, constants) }
            fun dynamicName(kind: String) { dynamic += Dynamic(kind, "$name(…)", line) }

            when {
                receiver == "packages" -> when (name) {
                    "open", "meta", "test", "fetch", "check", "resolve" -> {
                        if (first != null) packages += first else if (args.isNotEmpty()) dynamicName("package")
                        if (name == "fetch") usesBackend = true
                    }
                    "available" -> usesBackend = true
                }
                receiver == "groups" -> when (name) {
                    "create", "add" -> {
                        when (first) {
                            null -> dynamicName("group")
                            else -> createdGroups += first
                        }
                        // Every other string in the call is a model of the group
                        args.drop(1).forEach { arg ->
                            val named = text.literalsIn(arg)
                            if (named.isEmpty()) dynamicName("model")
                            named.forEach { literal -> if (literal.templated) dynamicName("model") else { models += literal.value; usesEncoder = true } }
                        }
                    }
                    "open" -> if (first != null) groups += first else dynamicName("group")
                }
                receiver == "databases" -> when {
                    name == "open" && first != null -> databases += first
                    name == "open" && args.isEmpty() -> databases += "main"
                    else -> allDatabases = true
                }
                receiver.isNotEmpty() -> Unit
                name == "database" -> when {
                    args.isEmpty() -> databases += "main"
                    first != null -> databases += first
                    else -> { allDatabases = true; dynamicName("database") }
                }
                name == "group" -> if (first != null) groups += first else dynamicName("group")
                name == "initMod" && args.isEmpty() -> usesNer = true
                name == "initAmie" -> usesNer = true
                name == "coroutineTemplate" || name == "template" -> if (first != null) templates += first else dynamicName("template")
                name == "shell" -> shellCommands += first ?: "a command made when it runs (line $line)"
                name == "shellScript" -> {
                    if (first != null) files += first else dynamicName("file")
                    shellCommands += first ?: "a script named when it runs (line $line)"
                }
                name == "forwardContext" -> usesEncoder = true
                name == "modelBuilder" -> {
                    if (first != null) built += first else dynamicName("model")
                    usesEncoder = true
                }
                name == "importPlugin" -> {
                    usesCorePlugins = true
                    if (first != null) packages += first else dynamicName("package")
                }
                name == "importModelTensorflow" -> unsupported += "importModelTensorflow (line $line): a bundle doesn't carry TensorFlow, which is over 300 MB and the only thing that call needs it for"
                name == "relate" -> args.getOrNull(1)?.let { arg ->
                    text.argumentName(arg, constants)?.let { files += it } ?: run { dynamicName("file") }
                }
                name in fileCalls -> if (first != null) files += first else if (args.isNotEmpty()) dynamicName("file")
                name in modelCalls -> {
                    if (first != null) modelOrJar(first, name) else if (args.isNotEmpty()) dynamicName("model")
                    if (name == "textsToLabel" || name == "fineTune" || name == "calibrate" || name == "planFineTune") {
                        args.getOrNull(1)?.let { arg -> text.argumentName(arg, constants)?.let { files += it } }
                    }
                }
                name in backendCalls -> usesBackend = true
            }
        }
        relateCall.findAll(masked).forEach { found ->
            val arg = text.arguments(found.range.last).firstOrNull() ?: return@forEach
            text.argumentName(arg, constants)?.let { files += it } ?: run { dynamic += Dynamic("file", "relate(…)", text.lineOf(found.range.first)) }
        }

        getenv.findAll(masked).forEach { found -> text.arguments(found.range.last).firstOrNull()?.let { text.argumentName(it, constants) }?.let { envVars += it } }
        getProperty.findAll(masked).forEach { found -> text.arguments(found.range.last).firstOrNull()?.let { text.argumentName(it, constants) }?.let { properties += it } }

        importLine.findAll(masked).forEach { found ->
            val path = found.groupValues[1]
            // The builders called directly, as a host that trains for its visitors does
            if (".jetbrainDLBuilder." in path) usesEncoder = true
            leftOutPackages.firstOrNull { (prefix, _) -> path.startsWith(prefix) }?.let { (_, what) ->
                unsupported += "import $path (line ${text.lineOf(found.range.first)}): a bundle doesn't carry $what"
            }
        }

        // What the lines of the script say it will reach, for the names the text can't tell
        val said = mutableSetOf<String>()
        text.comments.forEach { comment ->
            val (kind, value) = directive.find(comment.trim())?.destructured ?: return@forEach
            said += kind
            when (kind) {
                "model" -> { models += value; usesEncoder = true }
                "group" -> groups += value
                "package" -> packages += value
                "database" -> databases += value
                "file" -> files += value
                "template" -> templates += value
            }
        }

        // Whatever a script's strings say, a value that looks like a credential is one more thing to point out
        val secretLines = (assignment.findAll(masked).filter { found ->
            text.literalAt(found.range.last)?.let { it.value.isNotBlank() && !it.templated && !it.value.startsWith("\$ENV:") } == true
        }.map { text.lineOf(it.range.first) } + text.literals.filter { credentialShape.matches(it.value) }.map { it.line })
            .distinct().sorted().toList()

        val names = models + built + createdGroups + groups + packages + databases + templates + envVars + properties
        return ScriptNeeds(
            env = EnvPlan.scan(code),
            models = models,
            builtModels = built,
            createdGroups = createdGroups,
            groups = groups,
            packages = packages,
            databases = databases,
            templates = templates,
            shellCommands = shellCommands,
            files = files,
            literals = text.literals.filter { !it.templated }.map { it.value }.filter { it !in names && it !in files && looksLikePath(it) }.toSet(),
            dynamic = dynamic.filter { it.kind !in said },
            unsupported = unsupported,
            envVars = envVars,
            properties = properties,
            secretLines = secretLines,
            // A model named when it runs has an encoder as any other has
            usesEncoder = usesEncoder || dynamic.any { it.kind == "model" },
            usesNer = usesNer,
            usesBackend = usesBackend,
            allDatabases = allDatabases,
            usesQueue = usesQueue,
            usesTrainingData = usesTrainingData,
            usesCorePlugins = usesCorePlugins,
        )
    }

    fun isJar(name: String): Boolean = name.trim().endsWith(".jar", ignoreCase = true)

    // A line of prose or a sentence of SQL is no file name
    private fun looksLikePath(value: String): Boolean =
        value.length in 3..400 && '\n' !in value && !value.contains(": ") && value.count { it == ' ' } <= 3 && (value.contains('.') || value.contains('/'))

    /** Whether a variable's name says it holds a secret */
    fun looksSecret(name: String): Boolean = secretWord.containsMatchIn(name)
}

/** A string of the script: where it starts (its opening quote), what it says, whether a `$` makes it known only at run time */
internal data class Literal(val start: Int, val end: Int, val value: String, val templated: Boolean, val line: Int)

/**
 * The script with what isn't code blanked out: the insides of strings and comments are spaces in [masked], at the same
 * offsets, so the patterns above never match inside them. The strings and comments are kept apart.
 */
internal class Lexed private constructor(private val code: String, val masked: String, val literals: List<Literal>, val comments: List<String>) {
    private val lineStarts: IntArray = buildList {
        add(0)
        code.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    fun lineOf(offset: Int): Int = lineStarts.indexOfLast { it <= offset } + 1

    /** The string opening at [offset] */
    fun literalAt(offset: Int): Literal? = literals.firstOrNull { it.start == offset }

    /** The ranges of the arguments of the call whose `(` is at [open]; none for `()` */
    fun arguments(open: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var depth = 0
        var start = open + 1
        var i = open
        while (i < masked.length) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        if (masked.substring(start, i).isNotBlank() || literalsIn(start until i).isNotEmpty()) ranges += start until i
                        return ranges
                    }
                }
                ',' -> if (depth == 1) { ranges += start until i; start = i + 1 }
            }
            i++
        }
        return ranges
    }

    fun literalsIn(range: IntRange): List<Literal> = literals.filter { it.start in range }

    /**
     * The string an argument comes to when the text can tell: a literal, `name = "x"`, or a `val` of the script holding one.
     */
    fun argumentName(range: IntRange, constants: Map<String, String>): String? {
        val raw = masked.substring(range.first.coerceAtMost(masked.length), (range.last + 1).coerceAtMost(masked.length)).trim()
        val value = raw.replace(Regex("""^\w+\s*=\s*"""), "")
        if (value.startsWith("\"")) {
            val literal = literalsIn(range).firstOrNull() ?: return null
            if (literal.templated) return null
            // "x" + more, or "x".trim(): only a string on its own is that name
            return literal.value.takeIf { value.trimEnd().endsWith("\"") && value.count { it == '"' } == 2 || value.startsWith("\"\"\"") }
        }
        return constants[value]
    }

    companion object {
        fun of(code: String): Lexed {
            val out = StringBuilder(code)
            val literals = mutableListOf<Literal>()
            val comments = mutableListOf<String>()
            var line = 1
            var i = 0
            fun blank(from: Int, until: Int) {
                for (at in from until until.coerceAtMost(out.length)) if (out[at] != '\n') out[at] = ' '
            }
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '\n' -> { line++; i++ }
                    code.startsWith("//", i) -> {
                        val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                        comments += code.substring(i, end)
                        blank(i, end)
                        i = end
                    }
                    code.startsWith("/*", i) -> {
                        // Kotlin's block comments nest
                        var depth = 1
                        var at = i + 2
                        while (at < code.length && depth > 0) {
                            when {
                                code.startsWith("/*", at) -> { depth++; at += 2 }
                                code.startsWith("*/", at) -> { depth--; at += 2 }
                                else -> at++
                            }
                        }
                        comments += code.substring(i, at)
                        line += code.substring(i, at).count { it == '\n' }
                        blank(i, at)
                        i = at
                    }
                    code.startsWith("\"\"\"", i) -> {
                        val close = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it }
                        var end = close + 3
                        while (end < code.length && code[end] == '"') end++
                        val value = code.substring(i + 3, close.coerceAtMost(code.length))
                        literals += Literal(i, end, value, templated = hasTemplate(value), line = line)
                        line += value.count { it == '\n' }
                        blank(i + 1, end.coerceAtMost(code.length) - 1)
                        i = end
                    }
                    c == '"' -> {
                        val (value, templated, end) = readString(code, i)
                        literals += Literal(i, end, value, templated, line)
                        blank(i + 1, end - 1)
                        i = end
                    }
                    c == '\'' -> {
                        // A character: 'a', '\n', 'A'
                        var at = i + 1
                        if (at < code.length && code[at] == '\\') at++
                        val end = code.indexOf('\'', at + 1).let { if (it < 0) code.length else it + 1 }
                        blank(i, end)
                        i = end
                    }
                    else -> i++
                }
            }
            return Lexed(code, out.toString(), literals, comments)
        }

        private fun hasTemplate(value: String): Boolean = Regex("""\$[A-Za-z_{]""").containsMatchIn(value)

        // From the opening quote to past the closing one; a template's ${...} may itself hold strings
        private fun readString(code: String, start: Int): Triple<String, Boolean, Int> {
            val value = StringBuilder()
            var templated = false
            var i = start + 1
            while (i < code.length) {
                val c = code[i]
                when {
                    c == '"' -> return Triple(value.toString(), templated, i + 1)
                    c == '\n' -> return Triple(value.toString(), templated, i)
                    c == '\\' && i + 1 < code.length -> {
                        when (val next = code[i + 1]) {
                            'n' -> value.append('\n')
                            't' -> value.append('\t')
                            'r' -> value.append('\r')
                            'b' -> value.append('\b')
                            'u' -> if (i + 5 < code.length) {
                                code.substring(i + 2, i + 6).toIntOrNull(16)?.let { value.append(it.toChar()) }
                                i += 4
                            }
                            else -> value.append(next)
                        }
                        i += 2
                    }
                    c == '$' && code.getOrNull(i + 1) == '{' -> {
                        templated = true
                        var depth = 0
                        do {
                            when (code[i]) {
                                '{' -> depth++
                                '}' -> depth--
                            }
                            i++
                        } while (i < code.length && depth > 0)
                    }
                    c == '$' && code.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                        templated = true
                        i++
                    }
                    else -> {
                        value.append(c)
                        i++
                    }
                }
            }
            return Triple(value.toString(), templated, i)
        }
    }
}
