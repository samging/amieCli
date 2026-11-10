package org.example.project.components.terminal.window.controller.script

/**
 * The script API as the editor sees it: one entry per public member of [ScriptLogger] (keep them in sync).
 * Feeds three things: the completion dropdown, the API colour in the highlighter and the prelude that
 * lets a script call `log("hi")` or `source("train.csv")` without the `amie.` prefix.
 */
object SandboxApi {

    enum class Kind { FUNCTION, PROPERTY }

    /** Parameter of a function entry; types are fully qualified so the prelude compiles without extra imports */
    data class Param(val name: String, val type: String, val default: String? = null)

    /**
     * [target] is the `amie` member the prelude delegate calls (null when the name gets no top-level delegate,
     * like `import`, which the script parser would read as an import directive at the start of a statement).
     */
    data class Entry(
        val name: String,
        val kind: Kind,
        val description: String,
        val params: List<Param> = emptyList(),
        val returns: String? = null,
        val target: String? = name,
        val aliasOf: String? = null,
        /** The type parameters of a generic function, which its delegate declares: `T` for `fun <T> ortClosable` */
        val typeParams: List<String> = emptyList(),
        /** A member of a model, reached as `m.name` or inside the block of a sessionMod: it has no delegate and no `amie.` */
        val member: Boolean = false,
        /** The name that took this one's place: the dropdown leaves the entry out, and its delegate is deprecated too, so a saved script still runs */
        val deprecated: String? = null,
    ) {
        /** Display form: `source(path: String): DataSource?` or `sessions: OrtSessions` */
        val signature: String
            get() = when (kind) {
                Kind.PROPERTY -> "$name: ${short(returns ?: "Any")}"
                Kind.FUNCTION -> name + params.joinToString(", ", "(", ")") { p ->
                    "${p.name}: ${short(p.type)}" + (p.default?.let { " = ${short(it)}" } ?: "")
                } + (returns?.let { ": ${short(it)}" } ?: "")
            }

        /**
         * Text the dropdown inserts. A function whose last required parameter is a block comes with its braces, as a
         * script writes it: `sessionMod("") {  }`, `ortClosable {  }`; what else it requires shows by name, to fill in.
         */
        val insert: String
            get() {
                val callable = if (target == null && aliasOf == null && !member) "amie.$name" else name
                if (kind == Kind.PROPERTY) return callable
                val required = params.filter { it.default == null }
                val block = required.lastOrNull()?.takeIf { BLOCK in it.type } ?: return callable + if (required.firstOrNull()?.type == STRING) "(\"\")" else "()"
                val before = required - block
                return callable + (if (before.isEmpty()) "" else before.joinToString(", ", "(", ")") { if (it.type == STRING) "\"\"" else it.name }) + " {  }"
            }

        /** How far back from the end of [insert] the caret lands: in its first empty quotes or parentheses, else in the braces */
        val caretBack: Int
            get() {
                val text = insert
                val quotes = text.indexOf("\"\"")
                val parens = text.indexOf("()")
                return when {
                    quotes >= 0 -> text.length - quotes - 1
                    parens >= 0 -> text.length - parens - 1
                    text.endsWith(" {  }") -> 2
                    else -> 0
                }
            }

        /** The prelude line delegating to `amie`, or null when the name is not exposed prefix-free */
        fun delegate(): String? {
            val member = target ?: return null
            return when (kind) {
                Kind.PROPERTY -> "val $name get() = amie.$member"
                Kind.FUNCTION -> {
                    val declared = params.joinToString(", ") { p -> "${p.name}: ${p.type}" + (p.default?.let { " = $it" } ?: "") }
                    val passed = params.joinToString(", ") { it.name }
                    val generics = if (typeParams.isEmpty()) "" else typeParams.joinToString(", ", "<", "> ")
                    val note = deprecated?.let { "@Suppress(\"DEPRECATION\") @Deprecated(\"$name is $it now\") " }.orEmpty()
                    "${note}fun $generics$name($declared) = amie.$member($passed)"
                }
            }
        }
    }

    private const val PKG = "org.example.project.components.terminal.window.controller"
    private const val BLOCK = "->"
    private const val STRING = "String"
    private const val ANY = "Any?"
    private const val PATH = "java.nio.file.Path"
    private const val LOG_LEVEL = "$PKG.LogLevel"
    private const val INTERACTION_MODEL = "org.example.project.components.terminal.integrations.amiePilot.InteractionModel"
    private const val DATA_SOURCE = "$PKG.sourceFile.DataSource"
    private const val METRICS_REPORT = "$PKG.modules.MetricsReport"
    private const val BUILD_SETTINGS = "$PKG.envBuilder.BuildSettings"
    private const val SETTINGS_ENV = "$PKG.envBuilder.SettingsEnv"
    private const val ORT_SESSIONS = "$PKG.modelLoaders.OrtSessions"
    private const val ORT_SCOPES = "$PKG.modules.OrtScopes"
    private const val SESSION_MOD = "$PKG.modules.SessionMod"
    private const val MODEL_JOB = "$PKG.modules.ModelJob"
    private const val ORT_JOB = "$PKG.modules.OrtJob"
    private const val PREDICTION = "org.example.project.components.terminal.integrations.amiePilot.Prediction"
    private const val TENSOR = "org.example.project.components.terminal.integrations.amiePilot.Tensor"
    private const val JAR_PACKAGES = "$PKG.pluginEngine.JarPackages"
    private const val MODEL_SWARM = "$PKG.modules.ModelSwarm"
    private const val MODEL_GROUPS = "$PKG.modules.ModelGroups"
    private const val HUGGING_FACE = "$PKG.huggingFace.HuggingFaceScript"
    private const val MODEL_INTERFACE = "$PKG.script.ModelCreationalInterface"
    private const val ARCHITECTURE = "$PKG.jetbrainDLBuilder.Architecture"
    private const val BUILD_OPTIONS = "$PKG.jetbrainDLBuilder.BuildOptions"
    private const val FINE_TUNE_OPTIONS = "$PKG.jetbrainDLBuilder.FineTuneOptions"
    private const val EMBEDDING_CACHE = "$PKG.jetbrainDLBuilder.EmbeddingCache"
    private const val VERSION = "$PKG.modules.ModelVersions.Version"
    private const val QUEUE_ROW = "$PKG.jetbrainDLBuilder.LabelQueue.Row"
    private const val FINE_TUNE_PLAN = "$PKG.jetbrainDLBuilder.FineTunePlan"
    private const val DATABASE = "$PKG.databases.Database"
    private const val DATABASES = "$PKG.databases.Databases"
    private const val SHELL_RESULT = "$PKG.script.ShellResult"
    private const val TEMPLATE_RUN = "$PKG.script.TemplateRun"
    private const val TEMPLATE_DELEGATE = "$PKG.script.TemplateDelegate"
    private const val TEMPLATE_RUNS = "$PKG.script.TemplateRuns"

    private fun fn(name: String, description: String, vararg params: Param, returns: String? = null, target: String? = name, aliasOf: String? = null, typeParams: List<String> = emptyList(), deprecated: String? = null) =
        Entry(name, Kind.FUNCTION, description, params.toList(), returns, target, aliasOf, typeParams, deprecated = deprecated)

    private fun prop(name: String, type: String, description: String) = Entry(name, Kind.PROPERTY, description, returns = type)

    private fun member(name: String, description: String, vararg params: Param, returns: String? = null) =
        Entry(name, Kind.FUNCTION, description, params.toList(), returns, target = null, member = true)

    private fun memberProp(name: String, type: String, description: String) = Entry(name, Kind.PROPERTY, description, returns = type, target = null, member = true)

    /** Every member of [ScriptLogger], in its order, plus the prelude-only aliases at the end */
    val entries: List<Entry> = listOf(
        fn("log", "Prints a line in the console at the given level", Param("message", STRING), Param("level", LOG_LEVEL, "$LOG_LEVEL.INFO")),
        fn("info", "Prints an info line in the console", Param("message", STRING)),
        fn("error", "Prints an error line in the console", Param("message", STRING)),
        fn("data", "Prints a data line in the console", Param("message", STRING)),
        fn("log", "Prints any value in the console at the given level: a list or an array one element a line (log(pkg.run(\"x\")) prints every answer), a map key: value", Param("message", ANY), Param("level", LOG_LEVEL, "$LOG_LEVEL.INFO")),
        fn("info", "Prints any value as info lines, a list one element a line", Param("message", ANY)),
        fn("error", "Prints any value as error lines, a list one element a line", Param("message", ANY)),
        fn("data", "Prints any value as data lines, a list one element a line", Param("message", ANY)),
        fn("initAmie", "Named entities with the bundled BERT NER model: one tag per token (O, B-PER, I-ORG…), printed and returned; a one-off call, initMod() keeps the model open", Param("text", STRING), returns = "List<String>"),
        fn("initMod", "Opens the default interaction model", returns = INTERACTION_MODEL),
        fn("initMod", "The session of the module modelBuilder exported to AMP/models/intents/<modelName>: the one already open on it, else a new one; one that lacks something it needs is refused, naming it. A new instance of a package jar when the name ends in .jar (local, else fetched from the Amie server or the GitHub repositories of AMP/github.json; \"github:owner/repo/x.jar[@ref]\" takes it from that repository). A fetched jar is kept only when it has a package class. A model: .predict(text) is the label, .scores(text) every label with its score, .run(text) the raw label:score lines. A package: .run(text) is every answer of its run(text), .answer(text) the first, null when none. After those, a model of AMP/models/imported/<name> (a Hugging Face repo or a folder copied in) or a model by its path (\"/path/model.pt\"), as importModel opens it: ONNX, TorchScript or a TensorFlow SavedModel, text in with its tokenizer.json, labels from its config.json, tensors through .inputs, .outputs and .run(floatArrayOf(…)). A folder of several formats opens its ONNX, else the format its source.json names, else the first that runs here", Param("modelName", STRING), returns = INTERACTION_MODEL),
        fn("sessionMod", "A model to ask and nothing more: a snapshot of the head it has now. .predict(text), .scores(text), .run(text), .answer(text), .predictions(text), .version, .labels. It trains nothing, changes nothing, needs no session of the model and stays on its head when the model is trained again; it goes when the run ends, or with .close(). val m by sessionMod(\"name\") works too", Param("modelName", STRING), returns = SESSION_MOD),
        fn("sessionMod", "The same for one block, the model's functions at hand: sessionMod(\"name\") { predict(\"text\") }. It goes when the block ends; returns the block's value",
            Param("modelName", STRING), Param("block", "$SESSION_MOD.() -> T"), returns = "T", typeParams = listOf("T")),
        fn("sharedModel", "The name sessionMod had before: a saved script still runs, and the console says the new name", Param("modelName", STRING), returns = SESSION_MOD, deprecated = "sessionMod"),
        fn("sharedModel", "The name sessionMod had before: a saved script still runs, and the console says the new name",
            Param("modelName", STRING), Param("block", "$SESSION_MOD.() -> T"), returns = "T", typeParams = listOf("T"), deprecated = "sessionMod"),
        prop("packages", JAR_PACKAGES, "Package jar sessions: packages.available(), packages.fetch(\"x.jar\"), packages.print(), packages.closeAll(); packages.github() the jars of the GitHub repositories in AMP/github.json (packages.github(\"owner/repo\") one repository), packages.check(\"x.jar\") whether a jar has a package class (no code of it runs); packages.meta(\"x.jar\") what the jar says it needs (config files, env, Java, tests); packages.config(\"x.jar\") the keys and values of its config files, packages.setConfig(\"x.jar\", \"key\" to \"value\") sets keys in the file that has them; packages.test(\"x.jar\") runs the tests it declares on a fresh session and prints the report"),
        fn("group", "The swarm of a model group of the env, the models asked together: .predict(text) the best label across them, .scores(text) each label's best score, .each(text) every model's label, .vote(text) the label most models give, .ask(text) every raw answer, .combined(text) each model's labels with the merge and the vote from one asking, .close()", Param("name", STRING), returns = MODEL_SWARM),
        prop("groups", MODEL_GROUPS, "The model groups of the envs: groups.print(), groups.names(), groups.create(\"name\", listOf(\"a\", \"b\")), groups.add(\"name\", \"model\"), groups.remove(\"name\", \"model\"), groups.delete(\"name\"), groups.closeAll()"),
        fn("huggingFace", "A model of the Hugging Face Hub as AMP/models/imported/<name>, by its repo id (owner/name) or huggingface.co address; returns the name, so initMod(huggingFace(\"owner/repo\")) opens it. The first call downloads the file the Models page would pick (a plain model.onnx when there is one) with its config and tokenizer files; later calls find the folder and ask nothing. name is the folder's name, blank for the repo's last part; a private or gated repo needs HF_TOKEN in the app's environment", Param("repo", STRING), Param("name", STRING, "\"\""), returns = STRING),
        prop("huggingFace", HUGGING_FACE, "The Hugging Face Hub searched for models initMod opens: huggingFace.search(\"bert\", format = \"onnx\") the repo ids, the most downloaded first, each printed with its task, downloads and formats; format is onnx, torchscript, tensorflow, tflite or all (the default), limit how many (20), task a Hugging Face task such as text-to-speech or text-classification (or a word for it: tts, ner, embeddings) or any (the default). An id goes on to huggingFace(\"…\")"),
        fn("saveState", "Stores a JSON text as the signed-in user's saved state <name> on the Amie backend (uploads/<user>/state/<name>.json)", Param("name", STRING), Param("json", STRING)),
        fn("loadState", "The JSON text of the saved state <name> on the Amie backend, null when there is none", Param("name", STRING), returns = "String?"),
        fn("listStates", "Names of the signed-in user's saved states on the Amie backend", returns = "List<String>"),
        fn("importModelOnnx", "A fixed ONNX model with initMod's interface; its session is managed by sessions", Param("it", PATH), returns = "$INTERACTION_MODEL?"),
        fn("importModelOnnx", "A fixed ONNX model with initMod's interface, by file path", Param("path", STRING), returns = "$INTERACTION_MODEL?"),
        fn("importModel", "Any model by its path with initMod's interface: an .onnx file, a TorchScript file (.pt, .pth, .torchscript), a TensorFlow SavedModel folder, or a folder holding one such as a Hugging Face repo. Text in when a tokenizer.json is next to it, labels from its config.json, tensors through .inputs, .outputs, .run(floatArrayOf(…)), .run(longArrayOf(…)) and .run(mapOf(\"name\" to values)). PyTorch's and TensorFlow's natives are downloaded on first use. TFLite, frozen graphs and weights alone are refused with what would work: an error line and null", Param("path", PATH), returns = "$INTERACTION_MODEL?"),
        fn("importModel", "Any model by its path with initMod's interface: ONNX, TorchScript, a TensorFlow SavedModel or a folder holding one; an error line and null when it can't be opened", Param("path", STRING), returns = "$INTERACTION_MODEL?"),
        prop("sessions", ORT_SESSIONS, "Global ORT sessions: sessions.print(), sessions.cancel(\"name\"), sessions.cancelAll()"),
        fn("ortSessions", "Reads every open ORT session: inputs, outputs, runs and whether it is busy", returns = "List<String>"),
        fn("ortClosable", "What the block opens is for that block alone: closed when it ends, however it ends, the last opened first, so a handle kept past it fails as closed. A template started in the block works inside it and ends with it, and what it opened closes too. A session open before the block is used and left open. Returns the block's value",
            Param("name", STRING, "\"closable\""), Param("block", "() -> T"), returns = "T", typeParams = listOf("T")),
        fn("ortAsync", "ortClosable run in the background: the block runs on a thread of its own and the call returns a job at once. What the block opens is closed when it ends, however it ends, and a template started in it ends with it. The job ends with the script, however that ends, and with an ortClosable block around the call: .await() is the block's value and rethrows its failure, .await(millis) waits that long at most (null if it hasn't ended), .done, .state, .cancel()",
            Param("name", STRING, "\"async\""), Param("block", "() -> T"), returns = "$ORT_JOB<T>", typeParams = listOf("T")),
        fn("ortStatic", "What the block opens stays open across runs until it is terminated or freed, and a session open before the block is kept too. Returns the block's value",
            Param("name", STRING, "\"static\""), Param("block", "() -> T"), returns = "T", typeParams = listOf("T")),
        fn("ortRequired", "Kept like ortStatic while anything else is in session (a model, package, group, template or background job the required scopes don't hold themselves; the encoders models share don't count); once nothing else is open it is freed, and the block opens it again when next run. For what other models need beside them. Returns the block's value",
            Param("name", STRING, "\"required\""), Param("block", "() -> T"), returns = "T", typeParams = listOf("T")),
        fn("ortLimited", "Kept like ortStatic, at most max sessions under the name: when one more is opened the oldest is closed first. Returns the block's value",
            Param("name", STRING), Param("max", "Int"), Param("block", "() -> T"), returns = "T", typeParams = listOf("T")),
        fn("ortForever", "The name ortStatic had before: a saved script still runs, and the console says the new name",
            Param("name", STRING, "\"static\""), Param("block", "() -> T"), returns = "T", typeParams = listOf("T"), deprecated = "ortStatic"),
        prop("ortScopes", ORT_SCOPES, "What the static, timed and limited blocks keep: ortScopes.print(), ortScopes.free(\"name\") closes what one holds, ortScopes.freeAll() what all of them hold; both return how many were closed"),
        fn("handleBy", "Runs the block and returns its value; when it throws, handler's value for what it threw instead, and the run goes on: handleBy({ e -> log(e.message); \"\" }) { m.predict(text) }. A stop, a cancel and an Error such as OutOfMemoryError are never handled: they go on as they would",
            Param("handler", "(Throwable) -> T"), Param("block", "() -> T"), returns = "T", typeParams = listOf("T")),
        fn("handleBy", "What the local text model makes of an error: what is wrong and how to fix it, from the message, main.kts and the env, printed and returned. It only answers; the script stays as it is: handleBy(\"Unresolved reference: dumy\")",
            Param("message", STRING), returns = STRING),
        fn("handleBy", "The same for what was thrown: handleBy({ e -> handleBy(e) }) { m.predict(text) } asks the local text model about what the block threw",
            Param("error", "Throwable"), returns = STRING),
        fn("handleBy", "The same for error lines, as errorLog() gives them: the last ten go to the model with main.kts and the env; with none it says so and asks nothing",
            Param("lines", "List<String>"), returns = STRING),
        fn("handleBy", "The error terminal's last lines handed to the local text model: handleBy() after a command or a run failed, and the console says why",
            returns = STRING),
        fn("errorLog", "The error terminal's log: the last error lines the console showed, oldest first: a run's failures, what a command printed on stderr and why it failed, the app's own error lines. Emptied with the console, which a run does when it starts",
            Param("last", "Int", "20"), returns = "List<String>"),
        fn("importModelTensorflow", "The name importModel had for TensorFlow alone: a saved script still runs, and the console says the new name", Param("it", PATH), returns = "$INTERACTION_MODEL?", deprecated = "importModel"),
        fn("forwardContext", "Runs text through the context model; tokens receives the input ids, returns last_hidden_state",
            Param("text", STRING), Param("tokens", "(LongArray) -> Unit", "{}"), returns = "Array<Array<FloatArray>>?"),
        fn("validateMetrics", "Validates a model file on a train and a test file", Param("modelPath", PATH), Param("train", PATH), Param("test", PATH)),
        fn("validateMetrics", "Prints the last stored validation of AMP/models/intents/<modelName>; labels narrows the table",
            Param("modelName", STRING), Param("labels", "List<String>", "emptyList()"), returns = METRICS_REPORT),
        fn("metricsChart", "Draws ASCII bar charts of the stored validation; labels narrows the chart",
            Param("modelName", STRING), Param("labels", "List<String>", "emptyList()"), returns = METRICS_REPORT),
        fn("hasModel", "Whether AMP has a model of that name, built or a text model: if (!hasModel(\"x\")) modelBuilder(\"x\", data)", Param("modelName", STRING), returns = "Boolean"),
        fn("metricsHistory", "Draws an ASCII chart of accuracy and macro F1 across builds", Param("modelName", STRING), returns = METRICS_REPORT),
        fn("confusionMatrix", "Draws the confusion matrix of the stored validation as an ASCII grid", Param("modelName", STRING), returns = METRICS_REPORT),
        fn("mistakes", "The validation rows the last run got wrong, the surest first: label, answer, both scores and the text (only when the model keeps texts)", Param("modelName", STRING), returns = METRICS_REPORT),
        fn("suspects", "The training rows whose label the heads that didn't see them doubt, found by a build with buildOptions(folds = 5)", Param("modelName", STRING), returns = METRICS_REPORT),
        fn("calibrationChart", "How far the scores can be trusted: how often each score bin was right, the calibration error and what a score gate keeps", Param("modelName", STRING), returns = METRICS_REPORT),
        fn("trainingCurves", "Loss and accuracy by epoch of the chosen head of the last build, or of the last fine-tune", Param("modelName", STRING), returns = METRICS_REPORT),
        fn("implementation", "Evaluates Gradle build settings", Param("it", BUILD_SETTINGS)),
        fn("import", "Selects the package environment the script is sourced with (read before the run)", Param("it", SETTINGS_ENV), target = null),
        fn("import", "Gradle-style dependency \"group:module:version\" or a jar file name, sourced before the script compiles", Param("notation", STRING), target = null),
        fn("implementation", "Gradle-style dependency \"group:module:version\" or a jar file name, sourced before the script compiles", Param("notation", STRING)),
        fn("internalEnv", "Prints the env being sourced, what this run sourced and everything in it; returns its packages", returns = "List<String>"),
        fn("sourceFile", "Rows of the file keyed by row index with their count; a named column keeps only that cell per row",
            Param("it", STRING), Param("column", STRING, "\"\""), returns = "Pair<Int, Map<Int, List<String>>>"),
        fn("source", "Labeled data file with its text/label columns inferred; log(it) or it.info() lays it out as pandas' df.info() does, no !! needed", Param("path", STRING), returns = "$DATA_SOURCE?"),
        fn("logInfo", "Logs a data file as pandas' df.info() lays it out: logInfo(source(\"x.csv\")), the same as log(source(\"x.csv\"))", Param("source", "$DATA_SOURCE?")),
        fn("relate", "Gives the model a test file to validate on after training on train", Param("train", "$DATA_SOURCE?"), Param("test", STRING), returns = "$DATA_SOURCE?"),
        fn("modelBuilder", "Preprocesses the data, trains the candidate heads, keeps the best and exports it to AMP/models/intents/<modelName>",
            Param("modelName", STRING), Param("data", "$DATA_SOURCE?"), returns = MODEL_INTERFACE),
        fn("modelBuilder", "The same build with your own options: buildOptions(candidates = listOf(architecture(...)), pickBy, preprocess, validationFraction)",
            Param("modelName", STRING), Param("data", "$DATA_SOURCE?"), Param("options", BUILD_OPTIONS), returns = MODEL_INTERFACE),
        fn("architecture", "One head to try: hidden layer widths (empty list = linear), epochs, batch size, learning rate, Relu or Tanh; weight decay, dropout and label smoothing against memorising, a constant, cosine or linear rate with a warm-up, early stopping after patience epochs without progress, a name of your own",
            Param("hidden", "List<Int>", "listOf(16)"), Param("epochs", "Int", "150"), Param("batchSize", "Int", "16"), Param("learningRate", "Double", "0.01"), Param("activation", STRING, "\"Relu\""),
            Param("weightDecay", "Double", "0.0"), Param("dropout", "Double", "0.0"), Param("labelSmoothing", "Double", "0.0"), Param("schedule", STRING, "\"constant\""), Param("warmupEpochs", "Int", "0"), Param("patience", "Int", "0"), Param("name", "String?", "null"), returns = ARCHITECTURE),
        fn("buildOptions", "How a build goes: the heads to compare, macroF1 or accuracy to pick by, preprocessing on or off, the validation share, keepTexts off for other people's data (no row text in the logs); folds cross-validates every head and finds rows that look mislabeled, calibrate makes the scores honest, cacheEmbeddings keeps the sentence vectors on disk",
            Param("candidates", "List<$ARCHITECTURE>", "$ARCHITECTURE.DEFAULT_CANDIDATES"), Param("pickBy", STRING, "\"macroF1\""), Param("preprocess", "Boolean", "true"), Param("validationFraction", "Double", "0.2"), Param("seed", "Int", "42"), Param("balanceClasses", "Boolean", "false"), Param("keepTexts", "Boolean", "true"),
            Param("folds", "Int", "0"), Param("calibrate", "Boolean", "false"), Param("cacheEmbeddings", "Boolean", "true"), Param("keepMistakes", "Int", "25"), returns = BUILD_OPTIONS),
        fn("searchSpace", "Heads for buildOptions(candidates = ...) from every combination of the values given, or trials of them drawn at random",
            Param("hidden", "List<List<Int>>", "$PKG.jetbrainDLBuilder.SEARCH_HIDDEN"), Param("learningRates", "List<Double>", "$PKG.jetbrainDLBuilder.SEARCH_RATES"), Param("epochs", "List<Int>", "listOf(150)"), Param("batchSizes", "List<Int>", "listOf(16)"),
            Param("activations", "List<String>", "listOf(\"Relu\")"), Param("dropouts", "List<Double>", "listOf(0.0)"), Param("weightDecays", "List<Double>", "listOf(0.0)"), Param("patience", "Int", "0"), Param("trials", "Int", "0"), Param("seed", "Int", "42"), returns = "List<$ARCHITECTURE>"),
        fn("fineTune", "Teaches a trained model more without starting over: rows of labels it knows correct it, a new label is added to its head, its earlier data is replayed so it keeps what it knew; the model before stays a version (versions, rollback)",
            Param("modelName", STRING), Param("data", "Any?"), Param("options", FINE_TUNE_OPTIONS, "$FINE_TUNE_OPTIONS()"), returns = MODEL_INTERFACE),
        fn("fineTuneOptions", "How a fine-tune goes: epochs and rate, how near the head stays to where it was (stayNear), replaying the earlier data, the first layers kept as they are (freeze)",
            Param("epochs", "Int", "40"), Param("learningRate", "Double", "0.002"), Param("batchSize", "Int", "16"), Param("stayNear", "Double", "0.01"), Param("replay", "Boolean", "true"), Param("replayPerLabel", "Int", "200"), Param("freeze", "Int", "0"), Param("balanceClasses", "Boolean", "true"),
            Param("validationFraction", "Double", "0.2"), Param("seed", "Int", "42"), Param("preprocess", "Boolean", "true"), Param("patience", "Int", "0"), Param("calibrate", "Boolean", "false"), Param("cacheEmbeddings", "Boolean", "true"), Param("keepMistakes", "Int", "25"), Param("minNewLabelRows", "Int", "3"), returns = FINE_TUNE_OPTIONS),
        fn("calibrate", "Fits a temperature on labeled rows the model hasn't seen, so a score of 0.8 is right about 80% of the time; the answers stay the same", Param("modelName", STRING), Param("data", "Any?"), returns = MODEL_INTERFACE),
        fn("versions", "The model's versions, the current one first, then the earlier heads kept in versions/ (the ten newest)", Param("modelName", STRING), returns = "List<$VERSION>"),
        fn("rollback", "Makes an earlier version the model again, as a new version (a rollback can be undone); true when it did", Param("modelName", STRING), Param("version", "Int"), returns = "Boolean"),
        fn("textsToLabel", "The texts the model is least sure about (a csv's text column, a text file's lines, else what it was asked over HTTP), written with its guess to AMP/<modelName>-to-label.csv; returns the file. diverse = true picks texts unlike each other among its closest calls",
            Param("modelName", STRING), Param("path", "String?", "null"), Param("limit", "Int", "50"), Param("diverse", "Boolean", "false"), returns = "String?"),
        fn("teach", "A text the model answers wrongly with the label it should give, written labeled at the top of its queue (AMP/<modelName>-to-label.csv) for the next fineTune on it; returns the queue's path, null when it isn't written (a model that keeps no texts has no queue)",
            Param("modelName", STRING), Param("text", STRING), Param("label", STRING), returns = "String?"),
        fn("queue", "The rows of the model's queue in order, labeled or not, with what the model answered: .text, .label (empty while unlabeled), .labeled, .guess, .score, .runnerUp; printed as a table",
            Param("modelName", STRING), returns = "List<$QUEUE_ROW>"),
        fn("planFineTune", "What a fine-tune on the data (without it, the labeled rows of the model's queue) would do, in the words of the Models page's plan strip; nothing is trained. .ok says whether fineTune would start; .rows, .newLabels, .tooFew, .nearMisses, .replayRows, .problems",
            Param("modelName", STRING), Param("data", "Any?", "null"), Param("options", FINE_TUNE_OPTIONS, "$FINE_TUNE_OPTIONS()"), returns = FINE_TUNE_PLAN),
        fn("regradeQueue", "What the model answers now to every text of its queue, after a fineTune, rollback or calibrate: each row's guess, score and runner-up written again, the labels kept; returns the rows",
            Param("modelName", STRING), returns = "List<$QUEUE_ROW>"),
        prop("embeddingCache", EMBEDDING_CACHE, "Sentence vectors kept on disk so a text is embedded once: embeddingCache.print(), embeddingCache.clear(), embeddingCache.sizeOnDisk()"),
        fn("checkData", "Runs the preprocessor without training: what it would clean, drop and refuse, and whether a build could start", Param("data", "Any?"), returns = "Boolean"),
        fn("importPlugin", "Runs a plugin jar's static run() or main; a missing jar or a failure is one Error line, never an exception", Param("plugin", STRING)),
        fn("database", "A database from AMP/databases.json: .query(sql, params), .execute(sql, params), .print(sql), .scalar(sql), .tables(), .close()", Param("name", STRING, "\"main\""), returns = DATABASE),
        prop("databases", DATABASES, "The configured databases: databases.print(), databases.names(), databases.closeAll()"),
        fn("shell", "Runs a command through your login shell and waits for it; the console shows it and what it prints at the internal level, stderr as error lines. A non-zero exit is no exception: .ok, .exit, .out, .err, .lines, .timedOut, .cancelled. dir is where it runs (your home folder unless given, ~/ works), timeoutSeconds 0 means no limit",
            Param("command", STRING), Param("dir", "String?", "null"), Param("timeoutSeconds", "Int", "120"), Param("env", "Map<String, String>", "emptyMap()"), returns = SHELL_RESULT),
        fn("shellScript", "Runs a script file (~/ works) with its arguments passed one by one, never joined into a command line: directly when it is executable, through your shell otherwise. Returns what shell returns",
            Param("path", STRING), Param("args", "List<String>", "emptyList()"), Param("dir", "String?", "null"), Param("timeoutSeconds", "Int", "120"), Param("env", "Map<String, String>", "emptyMap()"), returns = SHELL_RESULT),
        fn("exportJvm", "Exports a script as a bundle that runs without the app, as Export… does: main.kts, a template's name or id, or a file, into a new or empty folder (the first free one under ~/amie-bundles when empty). What can't be included stops it unless anyway = true; planOnly only says what would go in; compiled = false carries the text and the compiler. Returns the bundle's folder, null when nothing was written",
            Param("script", STRING, "\"main.kts\""), Param("to", STRING, "\"\""), Param("anyway", "Boolean", "false"), Param("planOnly", "Boolean", "false"), Param("compiled", "Boolean", "true"), returns = "String?"),
        fn("serve", "Serves the model over HTTP like the Models page's Listen; null keeps serving.json's host/port/path/methods/min score. Under minScore it abstains, answering fallbackLabel or no label. Returns the predict URL, null when it could not start",
            Param("modelName", STRING), Param("port", "Int?", "null"), Param("host", "String?", "null"), Param("path", "String?", "null"), Param("methods", "List<String>?", "null"),
            Param("minScore", "Double?", "null"), Param("fallbackLabel", "String?", "null"), returns = "String?"),
        fn("stopServing", "Stops serving the model and remembers it", Param("modelName", STRING)),
        fn("servingUrl", "The predict URL of a model being served now, null when it is not", Param("modelName", STRING), returns = "String?"),
        fn("accessLog", "The last requests its listener answered (AMP/models/intents/<name>/access.log), printed: time, id, client, method, path, status, ms, bytes, outcome", Param("modelName", STRING), Param("last", "Int", "20"), returns = "List<String>"),
        fn("modelBuilder", "Trains a classifier on the labeled data the lambda supplies", Param("modelName", STRING), Param("it", "(Any) -> Any"), returns = MODEL_INTERFACE),
        fn("modelBuilder", "The lambda form with options", Param("modelName", STRING), Param("options", BUILD_OPTIONS), Param("it", "(Any) -> Any"), returns = MODEL_INTERFACE),
        fn("coroutineTemplate", "Starts a saved template in the background, by name or id, and the script goes on; input waits for it to read. The run answers to .send(text), .receive() (its next output, null once it has ended; .receive(millis) waits that long), .closeInput(), .await(), .cancel() and .state, and outlives the script",
            Param("name", STRING), Param("input", "List<String>", "emptyList()"), returns = TEMPLATE_RUN),
        fn("template", "A saved template to call like a function: val reader by template(\"name\"), then reader(\"text\") starts it on the first call, gives it the line and returns its next output, on the same run each time; it throws when the template failed. reader.close() closes its input and waits, reader.run is the run. It is closed for the script when that ends, and stops with it", Param("name", STRING), returns = TEMPLATE_DELEGATE),
        prop("templateRuns", TEMPLATE_RUNS, "What runs in the background: templateRuns.print(), templateRuns.cancelAll()"),
        fn("input", "The next line, waiting for one; null once input is closed. In main.kts a line typed in the console, or of standard input in an exported bundle; in a template the next line main.kts gave it", returns = "String?"),
        fn("output", "In a template: its next answer to job.receive() in main.kts; a foreground run logs the text", Param("text", STRING)),
        fn("output", "The same for any value, as its text: a list one element a line", Param("value", ANY)),
        // prelude-only aliases: `import` cannot be a top-level function name in a script
        fn("dependency", "Alias of implementation: adds \"group:module:version\" or a jar file name to the environment", Param("notation", STRING), target = "implementation", aliasOf = "implementation"),
        fn("environment", "Alias of amie.import(SettingsEnv(...)): selects the package environment the script is sourced with", Param("it", SETTINGS_ENV), target = "import", aliasOf = "import"),
    )

    private val text = Param("text", STRING)

    /** What a model answers, one entry per function of ModelAnswers but the overload of inference that says what for */
    val answerEntries: List<Entry> = listOf(
        member("predict", "The predicted label: the best answer without its score", text, returns = STRING),
        member("scores", "Every label with its score, best first", text, returns = "Map<String, Float>"),
        member("predictions", "The answers parsed into label and score, best first", text, returns = "List<$PREDICTION>"),
        member("run", "Every raw answer as it came: a model's label:score lines, a package's answers", text, returns = "List<String>"),
        memberProp("abilities", "Set<String>", "What it can be asked to do, in the words can takes"),
        member("can", "Whether it can do that: chat, generate, classify, fineTune, tag, hear, speak or run", Param("what", "String"), returns = "Boolean"),
        member("answer", "The first raw answer, score and all; null when there was none", text, returns = "String?"),
        member("inference", "The model's label:score lines, best first", text, returns = "List<String>"),
        member("getStatus", "What the model is: its manifest, with the encoder, the labels and the description", returns = "Any"),
        member("inBackground", "Asks on a thread of its own and returns at once with a job: .await() is the raw answers, .await(millis) waits that long at most, .done, .state, .cancel(). It waits its turn among the background answers and ends with the script, its ortClosable block or the model", text, returns = MODEL_JOB),
    )

    /** What initMod("name") gives: the answers, the tensors of a model from a file, and the session's close */
    val sessionEntries: List<Entry> = answerEntries + listOf(
        memberProp("inputs", "List<org.example.project.components.terminal.integrations.amiePilot.TensorSpec>", "The tensors a model from a file takes: name, shape (-1 open) and type; empty for a model that takes text only"),
        memberProp("outputs", "List<org.example.project.components.terminal.integrations.amiePilot.TensorSpec>", "The tensors it gives; a TorchScript model knows them after its first run"),
        member("run", "A model from a file run on floats as its one input, in the shape it declares: every output by name", Param("values", "FloatArray"), returns = "Map<String, $TENSOR>"),
        member("run", "The same on longs, such as token ids", Param("values", "LongArray"), returns = "Map<String, $TENSOR>"),
        member("run", "One value per input name: an array in the shape the input declares, or Tensor.of(values, shape)", Param("feeds", "Map<String, Any>"), returns = "Map<String, $TENSOR>"),
        member("close", "Closes the session; the next initMod opens a new one"),
    )

    /** What sessionMod("name") gives: the answers, what head it is, and letting go */
    val sharedEntries: List<Entry> = answerEntries + listOf(
        memberProp("version", "Int", "The number of the head it answers with, which stays what it was when it was taken"),
        memberProp("labels", "List<String>", "The labels of that head"),
        memberProp("name", STRING, "The model's name"),
        member("close", "Lets go of the snapshot, the last holder closing it; the model isn't touched"),
    )

    private val identifierEnd = Regex("""([\p{L}_][\p{L}\p{N}_]*)\.$""")
    private val sharedBlock = Regex("""\b(?:sessionMod|sharedModel)\(\s*"[^"]*"\s*\)\s*$""")

    private fun takenAs(name: String, code: String, call: String) =
        Regex("""\b(?:val|var)\s+${Regex.escape(name)}\s*(?::[^=]*)?(?:=|\bby\b)\s*(?:$RECEIVER\.)?$call\(""").containsMatchIn(code)

    // Whether [before] ends inside the block of a sessionMod, at any depth: a lambda in it still has the model at hand.
    // Braces in strings count too; the worst that does is a list that offers a little more or less
    private fun inSharedBlock(before: String): Boolean {
        var depth = 0
        for (i in before.indices.reversed()) {
            when (before[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else if (sharedBlock.containsMatchIn(before.substring(0, i))) return true
            }
        }
        return false
    }

    /**
     * The members of the model standing before [start] in [code]: after `m.` for an `m` the script took with sessionMod
     * or initMod (not a package's, which has no labels), and inside the block of a sessionMod. Empty anywhere else.
     */
    fun membersAt(code: String, start: Int, prefix: String): List<Entry> {
        val before = code.substring(0, start)
        val owner = identifierEnd.find(before)?.groupValues?.get(1)
        val members = when {
            owner == null -> if (inSharedBlock(before)) sharedEntries else emptyList()
            takenAs(owner, before, "(?:sessionMod|sharedModel)") -> sharedEntries
            takenAs(owner, before, """initMod(?!\(\s*"[^"]*\.jar")""") -> sessionEntries
            else -> emptyList()
        }
        // One row a name, as the top-level list has it: run(text) for the run overloads
        return members.filter { it.name.startsWith(prefix, ignoreCase = true) }.groupBy { it.name }.values.map(::likeliest)
    }

    /** The object itself, offered by the dropdown and coloured like the API */
    const val RECEIVER = "amie"

    val receiverEntry = Entry(RECEIVER, Kind.PROPERTY, "The script API object; every name here is also reachable as amie.name", returns = "ScriptLogger", target = null)

    val names: Set<String> = entries.map { it.name }.toSet()
    val functionNames: Set<String> = entries.filter { it.kind == Kind.FUNCTION }.map { it.name }.toSet()
    val propertyNames: Set<String> = entries.filter { it.kind == Kind.PROPERTY }.map { it.name }.toSet()

    /**
     * Entries whose name starts with [prefix] (case-insensitive), the deprecated left out, one row per name: a name with
     * overloads shows the one a script writes most ([likeliest]), so info is offered once, not as info(String) and
     * info(Any?)
     */
    fun complete(prefix: String, afterReceiver: Boolean = false, limit: Int = 8): List<Entry> {
        val candidates = entries.filter { entry ->
            entry.name.startsWith(prefix, ignoreCase = true) && (!afterReceiver || entry.aliasOf == null) && entry.deprecated == null
        }.groupBy { it.name }.values.map(::likeliest)
        return candidates.sortedWith(compareBy({ !it.name.startsWith(prefix) }, { it.name.length })).take(limit)
    }

    /**
     * The overload a script writes most: one that takes something over none (initMod("") over initMod()), then one whose
     * first argument is a text, since it inserts with its quotes (info(""), import("")), then the one listed first
     */
    private fun likeliest(overloads: List<Entry>): Entry {
        fun required(entry: Entry) = entry.params.filter { it.default == null }
        return overloads.sortedWith(compareBy({ required(it).isEmpty() }, { required(it).firstOrNull()?.type != STRING })).first()
    }

    /**
     * Top-level declarations placed after `val amie = ...` in the assembled script so every API member can be
     * called without the prefix. Overloads each get a delegate; defaults are kept; `amie.` still works.
     */
    fun prelude(): String =
        entries.mapNotNull { it.delegate() }.distinct().joinToString("\n", postfix = "\n")

    /**
     * `java.nio.file.Path` -> `Path`, `org...LogLevel.INFO` -> `LogLevel.INFO`, `org...jetbrainDLBuilder.LabelQueue.Row`
     * -> `LabelQueue.Row`: a package is any segment that starts in lower case; generic and function types keep their shape
     */
    private fun short(type: String): String = type.replace(Regex("""\b(?:[a-z_][A-Za-z0-9_]*\.)+"""), "")
}
