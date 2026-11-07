# Cookbook: scripts to copy

Every script on this page runs as it is in the sandbox: paste it into `main.kts`, press **Run** (⌘↵), read the
console. The shorter versions are one click away in the app: the disk icon on the strip over the editor (templates) lists them
as starter templates and **Load** puts one in the editor (your own script is kept under `AMP/pluginDir/backup/`). Nothing here needs an account;
the one call that does, `saveState`, says so. They are ordered from the first thing to try to the things you will do once a model exists. The exact
signatures are in the [Scripting API](sandbox-scripting.md); this page is about *what to type*.

One habit that saves time: open a model or a database once at the top of the script and keep it open between
runs (see [Running sessions](sessions.md)). The first run loads, every later run is instant.

## 1. Logging: four levels, four toggles

```kotlin
log("plain line")                       // info: the script's own output
info("same as log")
data("rows: 3, columns: text,label")    // data: what the script produced, kept apart from chatter
error("something is off")               // error: red, and the error toggle turns on by itself when a run fails
log("explicit", LogLevel.INTERNAL)      // internal: what the app itself says (sessions, builds, env); LogLevel is in scope
```

- The console shows **info** only when the app starts; the other three toggles on the console strip have counts,
  so `error 1` means one line is waiting behind a toggle. **all** turns everything on.
- Everything, whatever the toggles, goes to `AMP/logs.txt`.
- Every `inference` of a model is also a **data** line (`dummy "sunny" -> lights 0.671, weather 0.496, …`), so
  `data` is where to look when a model answers.

## 2. Ask a model: initMod and its interface

Every fresh `AMP/` has a model called **dummy** (labels `greeting`, `lights`, `weather`), so this runs before
anything is trained:

```kotlin
val m = initMod("dummy")                      // the model's session: opened now, or the one already open

log(m.predict("turn the lights on"))          // lights            the label (the argmax)
log(m.scores("turn the lights on").toString()) // {lights=0.67, weather=0.50, greeting=0.33}
m.predictions("hello there").forEach { p -> log("${p.label} ${p.score}") }
m.inference("hello there").forEach { log(it) } // the raw lines: label:score, best first
log(m.getStatus().toString())                 // the manifest: encoder, dims, labels, description

// Leave it open while you iterate, or:
// m.close()
```

What `initMod` gives you is an `InteractionModel`:

| Member | Returns | Use it for |
|---|---|---|
| `predict(text)` | `String` | The label. Throws when the model answers nothing |
| `scores(text)` | `Map<String, Float>` | Every label with its score, best first; a confidence check |
| `predictions(text)` | `List<Prediction>` | `label` and `score` as values, in the model's order |
| `inference(text)` | `List<String>` | The raw answer: `label:score` for a classifier, a tag per token for NER, whatever `run(text)` returned for a package |
| `getStatus()` | `Any` | The manifest of a model, the metadata of the NER model, the status map of a package |
| `close()` | | Frees the session; the Models page's Terminate does the same |

The three forms of `initMod`:

| Call | What it opens | How many |
|---|---|---|
| `initMod("name")` | A model exported to `AMP/models/intents/<name>` | **One per model.** A second call returns the open session |
| `initMod("name.jar")` | A package jar (env, `AMP/pluginDir`, a path, or fetched from the server) | **A new instance every call**; close what you open |
| `initMod()` | The bundled BERT NER model | A plain object you own; close it yourself |

A confidence gate, the most common thing to write around `scores`:

```kotlin
val m = initMod("dummy")
listOf("turn the lights on", "what's the weather", "asdf qwerty").forEach { text ->
    val scores = m.scores(text)
    val (label, score) = scores.entries.first()
    if (score < 0.5f) log("$text -> not sure ($label ${"%.2f".format(score)})")
    else log("$text -> $label ${"%.2f".format(score)}")
}
```

## 3. Train your own model from a csv

`AMP/sample-intents.csv` (45 rows, `text,label`) is written on first start; any csv with a text column and a
label column works (the columns are found by their headers: `text`, `label`, `intent`, …).

```kotlin
val train = source("sample-intents.csv")           // looked up as given, then in AMP/, then AMP/pluginDir/
relate(train, "intents-test.csv")                  // optional: validate on this file instead of a 20% split

val built = modelBuilder("intents", train)         // cleans the rows, embeds them, tries four heads, keeps the best, exports ONNX
built.modelStatus()                                // the validation table: precision, recall, F1 per label
built.modelChart()                                 // the same as ASCII bars, the build history, the confusion matrix

val m = initMod("intents")
log(m.predict("switch off the kitchen light"))     // lights
```

Before building, `checkData(train)` prints what the preprocessor would clean, drop and refuse, and whether a build
could start. The console tells you what the preprocessor did to the rows (what it dropped and why), how every
candidate head scored, which one was kept, and what the validation says about it (a head that memorises, one
that barely beats the majority label, labels it confuses). When the data cannot give a classifier the build stops with the reason in red
(`label 'weather' has 3 rows after cleaning, at least 5 are needed…`); the reasons and what to do about each are
in [Models and metrics](models.md#before-training-the-preprocessor). Your own heads instead of the four defaults:

```kotlin
val train = source("sample-intents.csv")
val options = buildOptions(
    candidates = listOf(
        architecture(hidden = listOf(32, 16), epochs = 200, learningRate = 0.005),
        architecture(hidden = emptyList(), epochs = 100),             // a linear head
        architecture(hidden = listOf(64), activation = "Tanh"),
    ),
    pickBy = "accuracy",
    balanceClasses = true,          // a rare label weighs as much as a frequent one
    seed = 7,                       // the same data and seed always give the same model
)
val built = modelBuilder("intents-custom", train, options)
built.modelStatus()
```

Later, without rebuilding:

```kotlin
validateMetrics("intents")                         // the stored validation
metricsChart("intents", labels = listOf("lights")) // one label's bars
metricsHistory("intents")                          // accuracy and macro F1 across builds
confusionMatrix("intents")
```

The build's phases and progress show on the console strip; the Models page lists the model with its metrics as
plots once it is exported. What happens inside: [Building a model](sandbox-scripting.md#what-modelbuilder-does).

## 4. Named entities: initAmie and initMod()

Both use the bundled BERT NER model (people, organisations, places, misc). `initAmie` opens and closes the model
for one text, so it is the right call for a one-off; `initMod()` keeps it open for many texts.

```kotlin
val tags = initAmie("Angela Merkel met Tim Cook in Berlin")   // printed, and returned: one tag per token
log(tags.joinToString(" "))                                    // O B-PER I-PER O B-PER I-PER O B-LOC O

val ner = initMod()                                            // the same model, kept open
listOf("Apple opened a store in Paris", "Marie Curie worked in Warsaw").forEach { text ->
    log("$text -> ${ner.inference(text).joinToString(" ")}")
}
ner.close()                                                    // not listed on the Models page, so close it here
```

The tags line up with the tokenizer's tokens: `[CLS]` and `[SEP]` at the ends (always `O`) and one tag per word
piece, so a name split into `Mer` and `##kel` gets `B-PER` and `I-PER`. The token ids of a text come from
`forwardContext(text) { ids -> … }` when you need to line them up yourself.

## 5. Embeddings: forwardContext

`forwardContext` runs the encoder and returns the hidden state `[batch][token][hidden]`. Mean-pool it to get one
vector per text; that is exactly what `modelBuilder` feeds its head with.

```kotlin
fun embed(text: String): FloatArray? {
    val hidden = forwardContext(text) { ids -> log("${ids.size} tokens", LogLevel.INTERNAL) } ?: return null
    val tokens = hidden[0]                                 // [token][hidden]
    val dim = tokens[0].size
    return FloatArray(dim) { i -> tokens.sumOf { it[i].toDouble() }.toFloat() / tokens.size }
}

fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return dot / (Math.sqrt(na) * Math.sqrt(nb))
}

val lights = embed("turn the lights on")!!
listOf("switch the lamp off", "will it rain tomorrow", "good morning").forEach { text ->
    log("$text -> ${"%.3f".format(cosine(lights, embed(text)!!))}")
}
```

`forwardContext` returns null (with an Error line) when the text is blank or the encoder failed.

## 6. Any ONNX model: importModelOnnx

A model file with the same `inference / getStatus / close` interface. A transformer (an `input_ids` input) needs
`tokenizer.json` next to it; labels come from a `config.json` (`id2label`) next to it when there is one; a model
with one float input takes the text as comma-separated numbers.

```kotlin
val m = importModelOnnx("/Users/me/models/sentiment/model.onnx")
if (m == null) {
    error("the model could not be opened; the console says why")
} else {
    log(m.getStatus().toString())                          // inputs, outputs, labels, producer
    log(m.predict("what a great day"))                     // POSITIVE
    m.scores("what a great day").forEach { (label, p) -> log("$label ${"%.3f".format(p)}") }
    m.close()
}
```

Its session appears under ORT sessions (`sessions.print()`), not on the Models page.

## 7. Sessions: see what is open, close what you don't need

```kotlin
sessions.print()                         // every ONNX Runtime session: name, inputs, outputs, runs, busy or not
packages.print()                         // the open package instances
ortSessions().forEach { log(it) }        // the same as sessions.print(), as a list

sessions.cancel("module:dummy")          // ends that ORT session and what runs on it; the model opens it again on its next text
packages.closeAll()                      // every package instance this or any script opened
sessions.cancelAll()                     // everything, encoders included; open models load them again on their next text
```

A build, a fine-tune or texts to label running texts through an encoder stops at a cancel of it, also one that
lands between two of its texts; one started after the cancel runs as usual.

A model has one session at most, so running a script ten times does not pile up ten copies. Packages are the
opposite: every `initMod("x.jar")` is a new instance, so a script that opens one should close it, or start with
`packages.closeAll()`.

`sessionMod("name")` is a model to ask and nothing more, a snapshot of the head it has now: it can't train or change
the model, writes nothing to its log, needs nothing else open and stays on that head through a fine-tune.

```kotlin
val m = sessionMod("dummy")             // goes when the run ends, or with m.close()
log(m.predict("turn the lights on"))
log("v${m.version}: ${m.labels}")

val label = sessionMod("dummy") {       // for one block: the model's functions at hand, gone when it ends
    log(scores("turn the lights on").toString())
    predict("turn the lights on")        // what the call returns
}
log(label)
```

The Sessions tab lists it as a *Session model*, and over the sessions **Scripts**: what each script running holds and
has used. More in [Running sessions](sessions.md#ask-without-touching-the-model-sessionmod).

## 8. Packages: jars with run(text) and close()

```kotlin
packages.available().forEach { log("${it.name} ${it.version ?: ""}") }   // what the server offers
packages.fetch("amie-demo-1.0.0.jar")                                     // into AMP/pluginDir; null if not there

val echo = runCatching { initMod("amie-demo-1.0.0.jar") }      // a new instance in its own class loader
    .onFailure { error("package not opened: ${it.message}") }   // not found anywhere, or no class with run(String)/close()
    .getOrNull()
if (echo != null) {
    try {
        echo.run("hello there").forEach { log(it) }   // echo.answer("...") is the first answer
        log(echo.getStatus().toString())             // id, jar, class, source, url, page, runs
    } finally {
        echo.close()
    }
}
```

A jar that only has a static entry point (`run()` or `main`) is a *plugin*, run once with `importPlugin`; nothing is opened, nothing to close:

```kotlin
importPlugin("tools.jar")                    // never throws: what its run() returned, or the failure, is one console line
```

Building a package of your own: [Building a package](sandbox-scripting.md#building-a-package).

## 9. A library from Maven Central

Declare it at the top (the line is read before the script compiles), then import as usual. The Models page's
**Add → From Maven Central…** does the same with a search box.

```kotlin
implementation("com.google.code.gson:gson:2.11.0")
import com.google.gson.GsonBuilder

val gson = GsonBuilder().setPrettyPrinting().create()
val m = initMod("dummy")
val answer = mapOf("text" to "turn the lights on", "scores" to m.scores("turn the lights on"))
log(gson.toJson(answer))
```

The env keeps what was ever imported (`internalEnv()` prints it), so the second run downloads nothing. A
`dependency("…")` is another name for `implementation`; `environment(SettingsEnv("name", "profile"))` selects
another env.

## 10. Remember something between runs: saveState and loadState

The sandbox has no variables that outlive a run. `saveState` keeps a JSON text under a name **on the Amie backend,
per signed-in user**, so it follows you to another machine and survives a fresh `AMP/`; think "the labels I
settled on", "the last csv I used", "my serving port". A local file does the same thing without signing in.

```kotlin
// On the backend (signed in): one JSON document per name
saveState("intents-config", """{"model": "intents", "threshold": 0.6}""")
listStates().forEach { log(it) }                       // devices, intents-config, …
val config = loadState("intents-config")               // the text, or null when there is none (or not signed in)
log(config ?: "nothing saved yet")

// Locally: a file of your own
val file = java.io.File(System.getProperty("user.home"), "intents-config.json")
file.writeText("""{"threshold": 0.6}""")
log(file.readText())
```

A failure (a guest, an unreachable backend, a bad name) is one Error line and `loadState` returns null, so a
script can fall back:

```kotlin
val threshold = loadState("intents-config")?.substringAfter("\"threshold\": ")?.substringBefore("}")?.toFloatOrNull() ?: 0.5f
```

## 11. Postgres: database("main")

The connections live in `AMP/databases.json`, written with one `main` entry the first time anything asks for it.
Edit it (the file is not committed to git):

```json
{
  "main": {
    "url": "jdbc:postgresql://localhost:5432/postgres",
    "user": "postgres",
    "password": "$ENV:PGPASSWORD",
    "description": "the local one"
  },
  "warehouse": { "url": "jdbc:postgresql://db.example.com:5432/analytics", "user": "amie", "password": "$ENV:WAREHOUSE_PW" }
}
```

`$ENV:NAME` is read from the environment variable NAME when the connection opens, so the file holds no secret.
Then, in a script:

```kotlin
databases.print()                                       // every entry: url, user, open or not
val db = database("main")                               // the same object for the same name while the app runs

db.tables().forEach { log(it) }                         // public.messages, …
db.print("select id, text from messages order by id desc limit 10")      // a table in the console (data lines)

val rows = db.query("select text from messages where label is null limit ?", 50)   // ? placeholders, in order
val m = initMod("intents")
rows.forEach { row ->
    val text = row["text"].toString()
    db.execute("update messages set label = ?, score = ? where text = ?", m.predict(text), m.scores(text).values.first(), text)
}
log("labeled ${rows.size} rows; ${db.scalar("select count(*) from messages where label is null")} left")
```

| Call | Returns | Notes |
|---|---|---|
| `query(sql, params…)` | `List<Map<String, Any?>>` | One map per row, columns in the query's order; values as JDBC gives them (`Int`, `String`, `java.sql.Timestamp`, …) |
| `scalar(sql, params…)` | `Any?` | The first column of the first row |
| `execute(sql, params…)` | `Int` | Rows touched by INSERT / UPDATE / DELETE, 0 for DDL |
| `print(sql, params…)` | the rows | Also prints them as a table, at most 200 rows |
| `tables()` | `List<String>` | `schema.table`, system schemas left out |
| `close()` | | The app closes open connections on exit; `databases.closeAll()` closes every one now |

Every failure is one Error line (`database 'main': relation "messages" does not exist [42P01] in: select …`) and a
`DatabaseException` the script can catch:

```kotlin
val db = database("main")
try {
    db.execute("create table if not exists predictions (text text, label text, score real, at timestamptz default now())")
    db.execute("insert into predictions (text, label, score) values (?, ?, ?)", "hi", "greeting", 0.91f)
} catch (e: Exception) {
    error("database: ${e.message}")      // a bad host, a wrong password, a typo in the SQL: all land here
}
```

A wrong host or password shows as `database 'main': can't connect to jdbc:postgresql://… as 'postgres': …` on
the first statement, not when `database("main")` is called.

## 12. Serve a model and call it over HTTP

A served model answers `POST {"text": "…"}` (or a plain-text body) and `GET ?text=…` at its predict URL with
`{"model": "intents", "predictions": [{"label": "lights", "score": 0.97}, …]}`. The Models page's **Listen**
does it with a switch; a script does it with `serve`:

```kotlin
val url = serve("dummy", port = 8765)               // http://127.0.0.1:8765/models/dummy/predict; null if it could not start
    ?: throw IllegalStateException("serve did not start; the console says why: port taken, path served already, model can't load")
log("serving at $url")
log(servingUrl("dummy") ?: "not serving")            // the same URL while it listens

// Call it from the script, with the errors handled
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

val http = HttpClient.newHttpClient()
fun ask(text: String): String {
    val request = HttpRequest.newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"text": "$text"}"""))
        .build()
    return try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            200 -> response.body()
            400 -> "no text in the request: ${response.body()}"          // {"error": "send text as {\"text\": ...}"}
            405 -> "method not allowed: ${response.body()}"               // the methods list excludes it
            500 -> "the model failed: ${response.body()}"                 // inference threw; also counted as an error on the page
            else -> "unexpected ${response.statusCode()}: ${response.body()}"
        }
    } catch (e: java.net.ConnectException) {
        "nothing listens at $url (${e.message})"
    } catch (e: Exception) {
        "request failed: ${e.message}"
    }
}

log(ask("turn the lights on"))
log(ask(""))                                        // 400
stopServing("dummy")                                // and it stays off on the next start
```

Every request is appended to `AMP/models/intents/<name>/incoming.jsonl` and counted on the Models page. Host, port,
path, methods and a min score passed to `serve` are saved to `serving.json`, so the page shows them; `serve("dummy")` with no
arguments reuses whatever the page saved. The HTTP side is described in [Serving a model](serving.md).

A client that sees a wrong answer can correct the model over the same port and have it retrained while it keeps
answering, with the new head swapped in when it is ready:

```kotlin
val base = url.removeSuffix("/predict")
fun send(method: String, route: String, body: String? = null): String {
    val request = HttpRequest.newBuilder(URI.create("$base/$route")).header("Content-Type", "application/json")
        .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
    return http.send(request, HttpResponse.BodyHandlers.ofString()).body()
}
log(send("POST", "teach", """{"text": "lamp please", "label": "lights"}"""))   // {"model": …, "label": "lights", "labeled": 1}
log(send("DELETE", "teach", """{"text": "lamp please"}"""))                     // {"removed": true, …}
log(send("POST", "teach", """{"text": "lamp please", "label": "lights"}"""))
log(send("POST", "retrain?wait=true"))                                          // {"status": "done", "from": 1, "to": 2, "detail": "fine-tune v1 → v2 · …"}
log(ask("lamp please"))                                                         // answered by v2: "version": 2
```

`retrain` without `wait` answers `202` at once; `GET …/retrain` says `running`, `done` or `failed`. The rows go to
the model's queue, `AMP/<name>-to-label.csv`, the same one **Teach** on the page fills, and the retrain is a
fine-tune on it: [Teaching a model while it listens](serving.md#teaching-a-model-while-it-listens).

## 13. Teach a model more: fine-tune, versions, rollback

A trained model learns a new label, or a few corrections, without a new build. `fineTune` keeps the head it has,
gives each new label an output of its own, and replays the rows it was trained on so it keeps what it knew
(details in [Fine-tuning](models.md#fine-tuning-teaching-a-model-more)). This one teaches "intents" from §3 a
label it doesn't know:

```kotlin
val music = listOf(
    "play some jazz" to "music",
    "put on my running playlist" to "music",
    "next song please" to "music",
    "turn the music up" to "music",
    "play the new album" to "music",
    "pause the song" to "music",
    "what is this song" to "music",
    "shuffle my favourites" to "music",
)
val tuned = fineTune("intents", music)              // replays sample-intents.csv, so the old labels stay
tuned.modelStatus()                                 // after, and the head before on the same rows

val m = initMod("intents")                          // opens on the new head
log(m.predict("play something relaxing"))           // music
log(m.predict("switch off the kitchen light"))      // still lights

versions("intents").forEach { log(it.describe()) }  // v2 · fine-tune of v1 …, v1 · build …
```

A correction is a row it gets wrong with the right label; the new row wins over the one it learned. The model it
replaced is one call away:

```kotlin
fineTune("intents", listOf("is it bright outside" to "weather"), fineTuneOptions(epochs = 60))

if (!rollback("intents", 1)) error("no v1 to go back to")   // v1's head again, as the next version
log(initMod("intents").predict("play some jazz"))           // not music any more: v1 never learned it
```

## 14. Scores you can trust: cross-validation, calibration, mistakes

On a small file one validation split is luck, and a softmax head is surer than it is right. Cross-validation
compares the heads on rows they did not see, five times over; calibration makes a score of 0.8 right about 80% of
the time; the analysis says which rows it got wrong and which labels look wrong. The tables are **internal** lines
(turn the level on, on the console strip):

```kotlin
val train = source("sample-intents.csv")
val options = buildOptions(
    candidates = searchSpace(hidden = listOf(emptyList(), listOf(32)), dropouts = listOf(0.0, 0.2), patience = 10),
    folds = 5,
    calibrate = true,
)
modelBuilder("intents-checked", train, options)
suspects("intents-checked")                         // training rows whose label looks wrong
mistakes("intents-checked")                         // validation rows it got wrong, the surest first
calibrationChart("intents-checked")                 // how often each score is right, and a gate worth using
trainingCurves("intents-checked")                   // loss and accuracy by epoch

// The gate the chart suggests: below it, say so instead of guessing
val m = initMod("intents-checked")
val (label, score) = m.scores("dim the lights a bit").entries.first()
log(if (score >= 0.8) label else "not sure ($label ${"%.2f".format(score)})")
```

What to label next: the texts it is least sure about, with its guess, in `AMP/<name>-to-label.csv`.

```kotlin
val asked = java.io.File.createTempFile("asked", ".txt")
asked.writeText("is it bright out\ndim it a little\nhey\nwill I need sunglasses\nmake it cosy in here")
val file = textsToLabel("intents", asked.path, limit = 3)   // diverse = true: three unlike each other among its closest calls
log(file ?: "nothing to label")
// fill in its label column, then: fineTune("intents", source("intents-to-label.csv"))
```

The Improve tab's teach loop, from a script: a correction goes to the top of the queue, labeled, as the chips under
the Try line put it there; the plan says in the strip's words what a fine-tune on the labeled rows would do, training
nothing (an internal line, and an error line when it would not start); after the fine-tune the queue is graded again
by the head that answers since:

```kotlin
val queued = teach("intents", "put the radio on", "music")   // the queue's path; null for a model that keeps no texts
teach("intents", "play my road trip songs", "music")
teach("intents", "some music for the kitchen", "music")
queue("intents").forEach { log("${it.label.ifBlank { "?" }} · ${it.text}") }

val plan = planFineTune("intents")                           // on the labeled rows of its queue
if (plan.ok && queued != null) {
    fineTune("intents", source(queued))                      // as Fine-tune on the Improve tab's queue
    val graded = regradeQueue("intents")                     // what the new head answers to each text of the queue
    log("${graded.count { it.labeled && it.guess == it.label }} of ${graded.count { it.labeled }} corrections answered right")
} else log("not yet: ${plan.problems + plan.tooFew}")
```

`planFineTune("intents", source("more.csv"))` plans on a file instead, as the strip does before a fine-tune on one.

The vectors of every text embedded so far are kept, so the second build of the same file is mostly training:
`embeddingCache.print()` shows them, `embeddingCache.clear()` frees the disk.

## 15. When something goes wrong

- A script that throws stops with `Error: …` in red and `failed after 3s` on the console strip; the error level
  turns itself on. The tooltip of the status keeps the last error.
- A model that is not there: `initMod("nope")` throws `Module 'nope' not found in …/AMP/models/intents`. Wrap it when the
  script should go on:

```kotlin
val m = runCatching { initMod("intents") }.getOrElse { e ->
    error("no 'intents' model yet (${e.message}); building one")
    modelBuilder("intents", source("sample-intents.csv"))
    initMod("intents")
}
```

- A terminated session: an `inference` on a session terminated from the Models page throws
  `Model intents (#2) is closed`; call `initMod` again to get a fresh one.
- A second **Run** while a script runs only logs "Script is already running"; there is no stop. A script that
  loops forever is stopped by terminating the session it uses, or by closing the app.
- `importPlugin`, `saveState`, `loadState`, `listStates`, `packages.fetch` and `importModelOnnx` never throw:
  they log one Error line and return nothing (`null`, an empty list). Database calls and `initMod` do throw, so
  that the script does not go on with no data; the examples above show the `try`.

## 16. Shell commands: look something up on the computer

`shell` runs a command in your login shell and gives back what it printed. A command that fails is not an exception,
so the script decides what it means. The console shows each command as an info line; turn on **data** and **error**
to read its output there as it runs.

```kotlin
val du = shell("du -sh .", dir = "~/Documents", timeoutSeconds = 60)
log("Documents: ${du.out}")

val found = shell("grep -rl TODO ~/notes")
when {
    found.timedOut -> error("grep took too long")
    found.exit == 1 -> log("nothing found")             // how grep says so
    !found.ok -> error("grep failed: ${found.err}")
    else -> found.lines.forEach { log(it) }
}
```

A script file gets its arguments one by one, whatever is in them. Use it for text the script did not write itself,
which in the command line of `shell` would be read as a command:

```kotlin
val text = queue("intents").firstOrNull()?.text ?: "nothing yet"
val r = shellScript("~/bin/lookup.sh", listOf(text, "it's a second argument"))
if (r.ok) log("$r") else error("lookup.sh: exit ${r.exit}: ${r.err}")
```

A template from someone else runs its commands with your rights, so read it before you press **Run**.
