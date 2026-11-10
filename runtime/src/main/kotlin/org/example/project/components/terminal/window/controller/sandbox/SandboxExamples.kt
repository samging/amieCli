package org.example.project.components.terminal.window.controller.sandbox

/**
 * The scripts behind the sandbox's Examples menu: complete, they run as they are, and none of them needs an
 * account or a server. Every one is compiled by the unit tests (CookbookTest), so they cannot go stale.
 */
object SandboxExamples {

    /**
     * [since] is the seeding that brought it, or brought it under this title: a folder seeded earlier gets it once, from
     * that seeding on. [formerly] are the titles it had before: a template under one of them that is still the starter
     * (never saved over) is renamed and brought up to date instead of a second one being added
     */
    class Example(val title: String, val group: String, val code: String, val since: Int = 1, val formerly: List<String> = emptyList())

    val all: List<Example> = listOf(
        Example("Hello: log at every level", "First steps", """
            |// Run this (⌘↵ / Ctrl+Enter). The console opens by itself and shows the info lines;
            |// the data, error and internal toggles on its strip count what they hide.
            |log("hello from main.kts")
            |info("the same level as log")
            |data("what the script produced, kept apart from chatter")
            |error("how a problem looks (the error toggle turns on when a run fails)")
            |log("what the app itself says", LogLevel.INTERNAL)
            |""".trimMargin()),

        Example("Ask the dummy model", "First steps", """
            |// Every AMP folder has a model called dummy (labels greeting, lights, weather),
            |// so this works before anything is trained. One session per model: run it twice, it stays open.
            |val m = initMod("dummy")
            |
            |log(m.predict("turn the lights on"))                   // the label (the argmax)
            |log(m.scores("turn the lights on").toString())         // every label with its score, best first
            |m.inference("hello there").forEach { log(it) }         // the raw label:score lines
            |log(m.getStatus().toString())                          // encoder, dims, labels, description
            |
            |// Open Models > Sessions to see it; m.close() or Terminate there closes it.
            |""".trimMargin()),

        Example("A confidence gate", "First steps", """
            |// Say "not sure" instead of guessing when the best score is low.
            |val m = initMod("dummy")
            |val threshold = 0.5f
            |listOf("turn the lights on", "what's the weather", "asdf qwerty").forEach { text ->
            |    val scores = m.scores(text)
            |    val (label, score) = scores.entries.first()
            |    if (score < threshold) log("${'$'}text -> not sure (${'$'}label ${'$'}{"%.2f".format(score)})")
            |    else log("${'$'}text -> ${'$'}label ${'$'}{"%.2f".format(score)}")
            |}
            |""".trimMargin()),

        Example("Train a model from a csv", "Models", """
            |// AMP/sample-intents.csv (45 rows: text,label) is written on first start; any csv with a
            |// text column and a label column works. The build's progress shows on the console strip.
            |val train = source("sample-intents.csv")
            |val built = modelBuilder("intents", train)     // embeds with BERT, trains a small head, exports ONNX
            |built.modelStatus()                             // precision, recall, F1 per label
            |built.modelChart()                              // bars, build history, confusion matrix
            |
            |val m = initMod("intents")
            |log(m.predict("switch off the kitchen light"))  // lights
            |""".trimMargin()),

        Example("Train with your own heads", "Models", """
            |// modelBuilder cleans the rows first (the console says what it dropped and why), then tries every head
            |// in the options on the same rows and keeps the best. Without options it tries linear, dense[16],
            |// dense[32] and dense[64, 16]; here are three of your own, picked by accuracy.
            |val train = source("sample-intents.csv")
            |val options = buildOptions(
            |    candidates = listOf(
            |        architecture(hidden = listOf(32, 16), epochs = 200, learningRate = 0.005),
            |        architecture(hidden = emptyList(), epochs = 100),          // a linear head
            |        architecture(hidden = listOf(64), activation = "Tanh"),
            |    ),
            |    pickBy = "accuracy",
            |)
            |val built = modelBuilder("intents-custom", train, options)
            |built.modelStatus()                             // the chosen head's validation table
            |""".trimMargin()),

        Example("Fine-tune: teach it a new label", "Models", """
            |// A trained model learns more without starting over: a label it doesn't know yet ("music") is added to
            |// its head, rows of labels it knows correct it. Its training rows are replayed so it keeps what it knew,
            |// and the model before stays a version: versions() lists them, rollback() brings one back.
            |modelBuilder("intents", source("sample-intents.csv"))   // drop this line once "intents" is built
            |val music = listOf(
            |    "play some jazz" to "music",
            |    "put on my running playlist" to "music",
            |    "next song please" to "music",
            |    "turn the music up" to "music",
            |    "play the new album" to "music",
            |    "pause the song" to "music",
            |    "what is this song" to "music",
            |    "shuffle my favourites" to "music",
            |)
            |val tuned = fineTune("intents", music)
            |tuned.modelStatus()                             // scores after, and before on the same rows (internal lines)
            |versions("intents").forEach { log(it.describe()) }
            |log(initMod("intents").predict("play something relaxing"))   // music
            |// rollback("intents", 1) makes the model before the fine-tune the model again
            |""".trimMargin()),

        Example("Search heads, cross-validated", "Models", """
            |// Every head of the search is trained once per fold and scored on the fold it didn't see; the best mean
            |// wins, steadier than one split on a small file. Rows the heads doubt are kept as suspects, and
            |// calibrate makes a score of 0.8 right about 80% of the time. The tables are internal lines.
            |val train = source("sample-intents.csv")
            |val options = buildOptions(
            |    candidates = searchSpace(
            |        hidden = listOf(emptyList(), listOf(32)),
            |        learningRates = listOf(0.01, 0.003),
            |        dropouts = listOf(0.0, 0.2),
            |    ),
            |    folds = 5,
            |    calibrate = true,
            |)
            |val built = modelBuilder("intents-searched", train, options)
            |built.modelStatus()                             // the chosen head on the validation rows
            |suspects("intents-searched")                    // training rows whose label looks wrong
            |calibrationChart("intents-searched")            // how often each score is right, and a gate worth using
            |trainingCurves("intents-searched")              // loss and accuracy by epoch of the chosen head
            |""".trimMargin()),

        Example("Which texts to label next", "Models", """
            |// The texts a model is least sure about are the ones worth labeling. textsToLabel writes them with its
            |// guess to AMP/<name>-to-label.csv, closest calls first; fill in the label column and fine-tune on it.
            |// Without a file it takes what the model was asked over HTTP. Needs "intents" (Train a model from a csv).
            |val asked = java.io.File.createTempFile("asked", ".txt").apply {
            |    writeText(listOf(
            |        "is it bright out", "dim it a little", "hey", "will I need sunglasses",
            |        "make it cosy in here", "good evening", "is it stormy", "lights or no lights",
            |    ).joinToString("\n"))
            |}
            |val file = textsToLabel("intents", asked.path, limit = 5)
            |if (file != null) java.io.File(file).readLines().forEach { log(it) }
            |// once the labels are filled in: fineTune("intents", source("intents-to-label.csv"))
            |mistakes("intents")                             // what the last run got wrong (internal lines)
            |""".trimMargin()),

        Example("Read a model's metrics", "Models", """
            |// The stored validation of a model, without rebuilding it. "dummy" has numbers after
            |// Models > Metrics > Validate on the sample data; "intents" after the training example.
            |val name = "dummy"
            |validateMetrics(name)                           // the table
            |metricsChart(name)                              // bars per label
            |metricsHistory(name)                            // accuracy and macro F1 across builds
            |confusionMatrix(name)
            |""".trimMargin()),

        Example("Named entities (NER)", "Models", """
            |// The bundled BERT NER model tags people, organisations and places, one tag per token.
            |// initAmie is a one-off (opens and closes); initMod() keeps the model open for many texts.
            |val tags = initAmie("Angela Merkel met Tim Cook in Berlin")
            |log(tags.joinToString(" "))
            |
            |val ner = initMod()
            |listOf("Apple opened a store in Paris", "Marie Curie worked in Warsaw").forEach { text ->
            |    log("${'$'}text -> ${'$'}{ner.inference(text).joinToString(" ")}")
            |}
            |ner.close()                                     // not on the Models page, so close it here
            |""".trimMargin()),

        Example("Embeddings and similarity", "Models", """
            |// forwardContext runs the encoder: [batch][token][hidden]. Mean-pool it to one vector per text.
            |fun embed(text: String): FloatArray? {
            |    val hidden = forwardContext(text) ?: return null
            |    val tokens = hidden[0]
            |    return FloatArray(tokens[0].size) { i -> tokens.sumOf { it[i].toDouble() }.toFloat() / tokens.size }
            |}
            |fun cosine(a: FloatArray, b: FloatArray): Double {
            |    var dot = 0.0; var na = 0.0; var nb = 0.0
            |    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            |    return dot / (Math.sqrt(na) * Math.sqrt(nb))
            |}
            |
            |val lights = embed("turn the lights on")!!
            |listOf("switch the lamp off", "will it rain tomorrow", "good morning").forEach { text ->
            |    log("${'$'}text -> ${'$'}{"%.3f".format(cosine(lights, embed(text)!!))}")
            |}
            |""".trimMargin()),

        Example("A group of models, asked together", "Models", """
            |// A group is a named set of models kept in the env (AMP/env/<env>/groups.json). group("name")
            |// opens them as one swarm: predict is the best score across them, vote the label most give.
            |// The Models page makes groups too (a model's ⋯ menu > New group with…); this one holds the dummy.
            |groups.create("house", listOf("dummy"))
            |groups.print()
            |val swarm = group("house")
            |log(swarm.predict("turn the lights on"))                 // the label with the best score across the models
            |log(swarm.vote("turn the lights on"))                    // the label most models answer
            |swarm.each("turn the lights on").forEach { (model, label) -> log("${'$'}model says ${'$'}label") }
            |swarm.scores("turn the lights on").forEach { (label, score) -> log("${'$'}label ${'$'}score") }
            |internalEnv()                                            // the env's packages and its groups
            |swarm.close()                                            // closes the sessions it opened
            |""".trimMargin()),

        Example("A model to ask, nothing more", "Models", """
            |// sessionMod gives a snapshot of the head dummy has now, kept in memory. It can't train or change the model,
            |// writes nothing to its log and needs nothing else open. Type m. for what it can do.
            |val m = sessionMod("dummy")
            |log("${'$'}m")                                                // sessionMod dummy v1 · 3 labels
            |log(m.predict("turn the lights on"))                     // the best label
            |m.scores("turn the lights on").forEach { (label, score) -> log("${'$'}label ${'$'}score") }
            |log("head v${'$'}{m.version}, labels ${'$'}{m.labels}")
            |
            |// by works as well as =, and a second one of the same head shares the snapshot
            |val same by sessionMod("dummy")
            |log(same.answer("what is the weather").orEmpty())
            |
            |// A fine-tune now wouldn't reach them: they stay on this head. Both go when the script ends
            |""".trimMargin(), since = 5, formerly = listOf("A model to ask, nothing more")),

        Example("sessionMod as a block", "Models", """
            |// In the block the model's functions are at hand with no name in front (type pre: the dropdown has predict
            |// first), what the block ends on is what the call returns, and the model goes when the block ends.
            |val texts = listOf("turn the lights on", "what is the weather", "hello there")
            |val answers = sessionMod("dummy") {
            |    log("${'$'}name v${'$'}version knows ${'$'}{labels.joinToString()}")
            |    texts.map { text ->                                   // a lambda in the block has them too
            |        val best = scores(text).entries.first()
            |        log("${'$'}text -> ${'$'}{best.key} ${'$'}{best.value}")
            |        best.key
            |    }
            |}
            |log(answers.groupingBy { it }.eachCount().toString())    // what the block returned
            |""".trimMargin(), since = 5, formerly = listOf("sharedModel as a block")),

        Example("Talk to amie", "Models", """
            |// A small chat model from Hugging Face, Qwen3 0.6B as an ONNX Runtime GenAI export. The first run downloads
            |// about 500 MB into AMP/models/text/qwen3-0.6b; later runs find it there and start at once.
            |val name = huggingFace("Arm/qwen3-0-6b-onnx-genai-int4-kquantlast-emb-int4", "qwen3-0.6b")
            |val chat = initMod(name)
            |log(chat.answer("Say hello in one line").orEmpty())
            |
            |// And one more
            |log(chat.answer("Name three colours of the rainbow").orEmpty())
            |""".trimMargin(), since = 7),

        Example("Sessions: what is open", "Housekeeping", """
            |// Models have one session each; packages get a new instance per initMod("x.jar").
            |sessions.print()                                // every ONNX Runtime session
            |packages.print()                                // open package instances
            |
            |// packages.closeAll()                          // every package instance
            |// sessions.cancel("module:dummy")              // one session by name
            |// sessions.cancelAll()                         // everything, encoders included
            |""".trimMargin()),

        Example("Scope functions: ortClosable and ortStatic", "Housekeeping", """
            |// ortClosable frees what its block opens when the block ends, so a handle is good for that one block.
            |// ortStatic keeps what its block opens across runs, until ortScopes frees it or it is terminated.
            |// ortRequired keeps it while anything else is in session; ortLimited keeps at most so many.
            |
            |// A session open before a block is left as it is, so this starts with dummy closed.
            |initMod("dummy").close()
            |
            |// The block's value comes back; the session is closed by then, however the block ended.
            |val (label, handle) = ortClosable("one time") {
            |    val m = initMod("dummy")
            |    m.predict("turn the lights on") to m
            |}
            |log("the block answered ${'$'}label")
            |
            |// One-time use: the handle the block gave back is closed.
            |try {
            |    handle.predict("hello there")
            |} catch (e: IllegalStateException) {
            |    log("after the block: ${'$'}{e.message}")
            |}
            |
            |// Kept under a name. Stop the script here and Models > Sessions says static search on its row.
            |val search = ortStatic("search") { initMod("dummy") }
            |log(search.predict("what's the weather"))
            |ortScopes.print().forEach { log(it) }
            |
            |// Closes what search holds and says how many.
            |log("freed ${'$'}{ortScopes.free("search")} session(s)")
            |
            |// Kept while anything else is in session: once nothing else is open it is freed by itself, and opened again when next run.
            |val recent = ortRequired("recent") { initMod("dummy") }
            |log(recent.predict("hello there"))
            |
            |// At most two under the name, the oldest closed when a third is opened. A model has one session, so this
            |// takes dummy over from recent; packages and ONNX files are new on every call, so for them it fills up.
            |val pool = ortLimited("pool", 2) { initMod("dummy") }
            |ortScopes.print().forEach { log(it) }
            |
            |// Closes what every block keeps, so this script leaves nothing open.
            |log("freed ${'$'}{ortScopes.freeAll()} more")
            |""".trimMargin(), since = 8, formerly = listOf("Scope functions: ortClosable and ortForever", "Scope functions: ortClosable and ortStatic")),

        Example("What a script uses", "Housekeeping", """
            |// Run this, then open Models > Sessions. Scripts lists it under this template's name for the 30 seconds it runs:
            |// what it holds, its answers, its CPU and what it allocated. Edit a word and it goes by main.kts instead.
            |val session = initMod("dummy")                           // stays open after the run, and with it the row
            |val snapshot = sessionMod("dummy")                      // goes when the run ends
            |
            |val ends = System.currentTimeMillis() + 30_000
            |var answers = 0
            |while (System.currentTimeMillis() < ends) {
            |    session.predict("turn the lights on")
            |    snapshot.scores("what is the weather")
            |    answers += 2
            |    ByteArray(4_000_000)                                 // allocated on purpose, so that figure moves too
            |    if (answers % 50 == 0) log("${'$'}answers answers so far")
            |    Thread.sleep(100)
            |}
            |log("Done. The row stays until the session goes: Terminate on the tab, or session.close() in a script")
            |""".trimMargin(), since = 5, formerly = listOf("What a script uses")),

        Example("Background templates: coroutineTemplate", "Housekeeping", """
            |// Starts the saved template "Background template: a reader" beside this script and talks to it: send gives it a
            |// line for its input(), receive waits for what its output(...) answers. Meanwhile Models > Sessions lists the run
            |// under Scripts with what it holds, and the Templates pane's fold says it is running.
            |val job = coroutineTemplate("Background template: a reader")
            |job.send("turn the lights on")
            |log("the reader answered ${'$'}{job.receive()}")
            |job.send("what is the weather")
            |log("the reader answered ${'$'}{job.receive()}")
            |templateRuns.print()                                 // every run in the background, this one among them
            |
            |// No more input is coming: the reader's input() turns null, its loop ends and the run ends with it.
            |job.closeInput()
            |log("the reader ended: ${'$'}{job.await()}")              // finished, or failed: and the reason
            |
            |// When the run ends it prints its own resources line, as a run of main.kts does: memory, cpu, sessions. The dummy
            |// session it opened stays open, as with any script; Models > Sessions shows it until it is closed or terminated.
            |""".trimMargin(), since = 4),

        Example("Background template: a reader", "Housekeeping", """
            |// The template half of a pair. The starter "Background templates: coroutineTemplate" starts this one in the
            |// background, feeds it lines with send and reads its answers with receive. Run by itself it reads the console:
            |// each line typed there is one input(), and eof ends it.
            |val model = initMod("dummy")
            |while (true) {
            |    val text = input() ?: break          // waits for the caller's next line; null once its input is closed
            |    output(model.predict(text))          // the label goes to the caller's receive(), and to the console
            |}
            |log("input is closed, the reader ends")
            |""".trimMargin(), since = 4),

        Example("Background templates: template by delegation", "Housekeeping", """
            |// Delegation: a saved template called like a function. The first call starts "Background template: a reader", gives
            |// it the line and returns what its output(...) answers; the next calls go to the same run.
            |val reader by template("Background template: a reader")
            |log("the reader answered ${'$'}{reader("turn the lights on")}")
            |log("the reader answered ${'$'}{reader("what is the weather")}")
            |log("run #${'$'}{reader.run.id} is ${'$'}{reader.run.state}")      // reader.run is the run behind it
            |
            |// A template that fails makes the call throw, with its name and its error, so a failure never passes for an answer.
            |// close ends its input and waits. A script that doesn't is closed for when it ends, and Stop ends the reader with it.
            |reader.close()
            |log("the reader ended: ${'$'}{reader.run.state}")
            |""".trimMargin(), since = 5),

        Example("Postgres: query and update", "Data", """
            |// Connections live in AMP/databases.json, written with a "main" entry the first time this runs:
            |// edit its url, user and password (${'$'}ENV:PGPASSWORD reads an environment variable), then run again.
            |databases.print()
            |val db = database("main")
            |try {
            |    db.tables().forEach { log(it) }
            |    db.execute("create table if not exists predictions (text text, label text, score real, at timestamptz default now())")
            |    val m = initMod("dummy")
            |    listOf("turn the lights on", "good morning").forEach { text ->
            |        db.execute("insert into predictions (text, label, score) values (?, ?, ?)", text, m.predict(text), m.scores(text).values.first())
            |    }
            |    db.print("select text, label, score, at from predictions order by at desc limit 10")
            |    log("rows: ${'$'}{db.scalar("select count(*) from predictions")}")
            |} catch (e: Exception) {
            |    error("database: ${'$'}{e.message}")       // wrong host or password, a typo in the SQL: all land here
            |}
            |""".trimMargin()),

        Example("Serve a model and call it", "Serving", """
            |// Serves dummy over HTTP (like Listen on the Models page), asks it from this script, stops it.
            |import java.net.URI
            |import java.net.http.HttpClient
            |import java.net.http.HttpRequest
            |import java.net.http.HttpResponse
            |
            |val url = serve("dummy", port = 8765)
            |    ?: throw IllegalStateException("serve did not start; the console says why (port taken, model can't load)")
            |log("serving at ${'$'}url")
            |
            |val http = HttpClient.newHttpClient()
            |fun ask(text: String): String {
            |    val request = HttpRequest.newBuilder(URI.create(url))
            |        .header("Content-Type", "application/json")
            |        .POST(HttpRequest.BodyPublishers.ofString(""${'"'}{"text": "${'$'}text"}""${'"'}))
            |        .build()
            |    return try {
            |        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            |        when (response.statusCode()) {
            |            200 -> response.body()
            |            400 -> "no text sent: ${'$'}{response.body()}"
            |            405 -> "method not allowed: ${'$'}{response.body()}"
            |            500 -> "the model failed: ${'$'}{response.body()}"
            |            else -> "unexpected ${'$'}{response.statusCode()}: ${'$'}{response.body()}"
            |        }
            |    } catch (e: java.net.ConnectException) {
            |        "nothing listens at ${'$'}url"
            |    }
            |}
            |log(ask("turn the lights on"))
            |log(ask(""))                                    // 400
            |stopServing("dummy")
            |""".trimMargin()),

        Example("When something goes wrong", "Housekeeping", """
            |// initMod and database calls throw, so a script does not go on without data; wrap what may be missing.
            |val m = runCatching { initMod("intents") }.getOrElse { e ->
            |    error("no 'intents' model yet (${'$'}{e.message}); building one from the sample data")
            |    modelBuilder("intents", source("sample-intents.csv"))
            |    initMod("intents")
            |}
            |log(m.predict("good morning"))
            |
            |// importPlugin, packages.fetch, importModelOnnx, saveState/loadState never throw:
            |// they log one ERROR line and return nothing.
            |importPlugin("not-there.jar")
            |val onnx = importModelOnnx("/nowhere/model.onnx")
            |log(if (onnx == null) "no model at that path, as expected" else "opened")
            |""".trimMargin()),

        Example("Function: clean a text", "Functions", """
            |// A template written as a function: each line it reads is one call, and what it gives to output(...) is
            |// the answer. Call it from any script:
            |//     val clean by template("Function: clean a text")
            |//     log(clean("  Hello   WORLD "))
            |// Run by itself nothing feeds it, so input() is null at once and it ends right away.
            |while (true) {
            |    val text = input() ?: break
            |    output(text.trim().replace(Regex("\\s+"), " ").lowercase())
            |}
            |""".trimMargin(), since = 6),

        Example("Function: count words", "Functions", """
            |// Takes a line, answers how many words it has. Every answer is a text: the caller turns it into a number.
            |//     val count by template("Function: count words")
            |//     val words = count("turn the lights on").toInt()
            |while (true) {
            |    val text = input() ?: break
            |    output(text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }.toString())
            |}
            |""".trimMargin(), since = 6),

        Example("Function: label a text", "Functions", """
            |// A model behind a function: the model opens once, on the first call, and every call after that reuses it.
            |// Called inside ortClosable the model closes with that block; see "Functions: a pipeline in ortClosable".
            |val model = initMod("dummy")
            |while (true) {
            |    val text = input() ?: break
            |    output(model.predict(text))
            |}
            |""".trimMargin(), since = 6),

        Example("Function: label and score", "Functions", """
            |// Answers the best label with its score, as "lights 0.91". The caller splits it:
            |//     val (label, score) = labelled("turn the lights on").split(" ")
            |val model = initMod("dummy")
            |while (true) {
            |    val text = input() ?: break
            |    val (label, score) = model.scores(text).entries.first()
            |    output("${'$'}label ${'$'}{"%.2f".format(score)}")
            |}
            |""".trimMargin(), since = 6),

        Example("Function: clean, then label", "Functions", """
            |// A function made of other functions: a template can call templates too, four deep at most.
            |// The block ends when this template's input ends, and the two it called end with the block.
            |ortClosable("clean then label") {
            |    val clean by template("Function: clean a text")
            |    val label by template("Function: label and score")
            |    while (true) {
            |        val text = input() ?: break
            |        output(label(clean(text)))
            |    }
            |}
            |""".trimMargin(), since = 6),

        Example("Functions: a pipeline in ortClosable", "Functions", """
            |// Saved templates called like functions, inside ortClosable. A template called in the block works inside it:
            |// when the block ends, however it ends, the templates end first, then what they opened is closed.
            |val answers = ortClosable("pipeline") {
            |    val clean by template("Function: clean a text")
            |    val count by template("Function: count words")
            |    val label by template("Function: label a text")
            |    listOf("  Turn the LIGHTS   on ", "What is   the WEATHER").map { text ->
            |        val cleaned = clean(text)
            |        "${'$'}cleaned: ${'$'}{count(cleaned)} words, ${'$'}{label(cleaned)}"
            |    }
            |}
            |answers.forEach { log(it) }
            |
            |// Nothing is left: the fold in the Templates pane is empty and the model is closed.
            |log("still running: ${'$'}{templateRuns.active.size}")
            |""".trimMargin(), since = 6),

        Example("Functions: keep the model between calls", "Functions", """
            |// The other way round: ortStatic inside the function keeps its model for the next run, so only the first call
            |// pays for opening it. ortStatic wins over an ortClosable around the call, since the innermost block decides.
            |val labelled = ortStatic("kept label") { initMod("dummy") }
            |listOf("turn the lights on", "good morning").forEach { log("${'$'}it: ${'$'}{labelled.predict(it)}") }
            |log("kept until freed:")
            |ortScopes.print()
            |ortScopes.free("kept label")
            |""".trimMargin(), since = 6),

        Example("Functions: a command as a function", "Functions", """
            |// shell runs a command and waits. A non-zero exit is no exception, so ask .ok.
            |fun today(): String {
            |    val result = shell("date +%F")
            |    return if (result.ok) result.out.trim() else "unknown"
            |}
            |log("today is ${'$'}{today()}")
            |""".trimMargin(), since = 6),
    )

    val groups: List<String> get() = all.map { it.group }.distinct()
}
