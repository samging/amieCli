# Scripting API

Complete scripts for every call below, ready to paste, are in the [Cookbook](cookbook.md).

The same scripts run outside the app with the `amie` command line: [Learn amie](learn.md) goes through them chapter by chapter, and the [amie reference](amie-reference.md) has the commands and the API in brief.

The sandbox is a Kotlin script editor (`main.kts`) with a console (how to use the editor, the completion dropdown and
the console filters is in [The sandbox](sandbox.md)). The script runs inside the app with the sandbox API bound to
it; everything it logs shows in the console on the right and is appended to `AMP/logs.txt`.

Every function below can be called **directly** (`source("x.csv")`) or through the `amie` object
(`amie.source("x.csv")`). The only difference is `amie.import`: without the prefix write
`implementation("group:module:version")` or `dependency(...)` for a package and `environment(SettingsEnv(...))` for
an environment, since a bare `import(...)` reads as a Kotlin import.

## Running a script

1. Type in the editor. The script is saved to `AMP/pluginDir/main.kts` as you type.
2. Press **Run** over the editor or **⌘↵** (Ctrl+Enter elsewhere) inside it.
3. While the script runs the console strip reads `running 12s` (with the model build's phase and progress when
   `modelBuilder` is working) and the Run button reads *Running…*; pressing Run again only logs "Script is
   already running".
4. When it ends the strip shows `finished in 41s` or `failed after 3s`, and the console gets the same line.

Started with `AMIE_AUTORUN=1`, the app opens the sandbox and runs the saved `main.kts`
once, as if Run was pressed (the model site's `ample.sh` does this for the gateway). Without a saved `main.kts`
nothing runs.

The level toggles on the console strip (internal, error, data, info) filter what is shown; only **info** is on at
start, the counts on the others say what is hidden. **Clear** empties the view, the log file keeps it all.

### Run phases

- **Configuration.** `implementation("group:module:version")` / `amie.import("...")` and
  `environment(SettingsEnv("env", "profile"))` / `amie.import(SettingsEnv(...))` calls are read from the script text *before* it compiles, the packages are resolved from Maven Central (cached under
  `AMP/env/caches`) and put on the script's classpath. Each env (`AMP/env/<name>/env.lock.json`) keeps what was ever
  imported into it, like a virtualenv.
- **Execution.** The script's own `import` lines are moved to the top (a trailing `//` comment on one is fine),
  `LogLevel` is imported for you, then `val amie = bindings["logger"] as ScriptLogger`
  and a prelude of top-level functions delegating to it (`fun source(path: String) = amie.source(path)`, …) are
  inserted before the rest of the code, and the whole is evaluated with the Kotlin JSR-223 engine on a background
  thread. The prelude is generated from one list (`SandboxApi.kt`), which also feeds the editor's completion dropdown.

## The API

Defined in `ScriptLogger.kt`. The most used calls (prefix `amie.` optional):

| Call | What it does |
|---|---|
| `log("...")`, `info`, `error`, `data` | Any value, so `log(pkg.run("x"))` prints every answer: a list, a set or an array one element a line, a map `key: value`. Console lines at the given level: **info** for the script's own output (the only level on at start), **data** for what it produced (every model answer is one), **error** (turns itself on when a run fails), **internal** for the app's own lines |
| `source("train.csv")` | Reads a labeled CSV (text and label columns are inferred from headers like `text`, `label`, `intent`). Looked up as given, then in `AMP/`, then `AMP/pluginDir/` |
| `relate(train, "test.csv")` | Attaches a test file to a training source; the model validates on it instead of a split. Remembered in `AMP/relations.json` |
| `sourceFile("file.csv", column = "")` | Raw rows keyed by index, optionally one column |
| `hasModel("name")` | Whether AMP has that model: built, a text model, a speech model or one imported into `AMP/models/imported` |
| `modelBuilder("name", data)` | Trains and exports an intent classifier (below) |
| `initMod("llama3.2-3b")` | A text generation model of `AMP/models/text`, run on the app's own ONNX Runtime and managed as any other session; `.answer(text)` answers ([voice.md](voice.md)) |
| `modelBuilder("name") { ... }`, `modelBuilder("name", buildOptions(...)) { ... }` | Same, the lambda returns the data; the second with your own options |
| `modelBuilder("name", data, buildOptions(...))` | The same build with your own options: `architecture(hidden, epochs, batchSize, learningRate, activation)` candidates, `pickBy` (`macroF1` or `accuracy`), `preprocess`, `validationFraction`, `seed`, `balanceClasses`, `keepTexts` (false for a model trained on other people's data: no row text reaches the console or `dropped.csv`, and its sessions log each text's length instead of the text), `folds` (cross-validate every head over 2 to 10 folds and find rows that look mislabeled), `calibrate` (fold a temperature into the head so the scores mean what they say), `cacheEmbeddings` (keep the vectors on disk, on by default), `keepMistakes`. See [Models and metrics](models.md#your-own-head) |
| `architecture(..., weightDecay, dropout, labelSmoothing, schedule, warmupEpochs, patience, name)` | A head with what keeps it from memorising, a `cosine` or `linear` rate with a warm-up, and early stopping after `patience` epochs without progress; all off unless given. See [Against memorising](models.md#against-memorising-regularisation-schedules-early-stopping) |
| `searchSpace(hidden, learningRates, epochs, batchSizes, activations, dropouts, weightDecays, patience, trials, seed)` | The candidates of a search: every combination of the values, or `trials` of them at random. `buildOptions(candidates = searchSpace(...), folds = 5)` |
| `fineTune("name", data, fineTuneOptions(...))` | Teaches a trained model more: rows of labels it knows correct it, a label it doesn't know gets an output of its own, and its earlier data is replayed so it keeps what it knew. The data is what `modelBuilder` takes; the model before stays a version. Returns what `modelBuilder` returns. See [Fine-tuning](models.md#fine-tuning-teaching-a-model-more) |
| `fineTuneOptions(epochs, learningRate, batchSize, stayNear, replay, replayPerLabel, freeze, …)` | How a fine-tune goes; the table is in [Fine-tuning](models.md#fine-tuning-teaching-a-model-more) |
| `calibrate("name", data)` | Fits a temperature on labeled rows the model hasn't seen (at least 10) so its scores mean what they say; the answers stay the same; a new version |
| `versions("name")`, `rollback("name", 2)` | The heads the model had (the current one first, the ten before it kept in `versions/`), and an earlier one made the model again as a new version; `rollback` returns true when it did |
| `textsToLabel("name", path = "asked.txt", limit = 50, diverse = false)` | The texts the model is least sure about (a csv's text column or a text file's lines; without a path, what it was asked over HTTP) written with its guess to `AMP/<name>-to-label.csv`, under the rows labeled in that file already; returns the file's path. `diverse = true` picks texts unlike each other among its closest calls ([What to label next](models.md#what-to-label-next)) |
| `teach("name", "text", "label")` | A text the model answers wrongly with the label it should give, written labeled at the top of its queue (`AMP/<name>-to-label.csv`), as the chips under the Try line do; the next fine-tune on the queue learns it. The label in the model's spelling; a text the queue has gets the label instead. Returns the queue's path, null when it isn't written (a model that keeps no texts has no queue; the console says why, never with the text) |
| `queue("name")` | The rows of its queue in order, labeled or not: `.text`, `.label` (empty while unlabeled), `.labeled`, `.guess`, `.score`, `.runnerUp`; printed as a table (an internal line). None for a model that keeps no texts |
| `planFineTune("name", data = null, fineTuneOptions(...))` | What a fine-tune on the data would do, worked out without training, in the words of the plan strip: the rows, the labels they are and add, the earlier rows it replays, what it drops, what is worth a second look and what keeps it from starting (an internal line, and an error line when it would not start). Without data, on the labeled rows of its queue. `.ok` says whether `fineTune` would start; `.rows`, `.known`, `.newLabels`, `.tooFew`, `.nearMisses`, `.replayRows`, `.problems` |
| `regradeQueue("name")` | What the model answers now to every text of its queue, as the Models page grades it after a fine-tune, rollback or calibration: each row's guess, score and runner-up written again, the labels kept; returns the rows. The console says how many labeled texts it answers right |
| `embeddingCache.print()`, `embeddingCache.clear()`, `embeddingCache.sizeOnDisk()` | The sentence vectors kept in `AMP/cache/embeddings`, so a text is embedded once, and the bytes they take |
| `checkData(data)` | The preprocessor alone, without training: what it would clean, drop and refuse, and whether a build could start (returns true then) |
| `initMod("name")` | The session of an exported model: the one already open, else a new one (listed on the Models page, terminable there); `.predict("text")` returns the predicted label, `.scores("text")` every label with its score (best first), `.inference("text")` the raw `label:probability` lines sorted best first; `.close()`. `initMod("dummy")` works on a fresh checkout. An encoder and decoder pair (a T5 or BART export, `encoder_model.onnx` with `decoder_model.onnx`) opens as two sessions a script wires itself: `.encoder.run(mapOf("input_ids" to t.ids, "attention_mask" to t.mask))` gives the hidden states, `.decoder.run(...)` the next token's logits for the ids so far (`.decoder.inputs` names its tensors), `.decoderWithPast` the decoder that takes the past state back when the export has one, `.tokens(text)` the ids and mask, `.text(ids)` the words, `.startToken` and `.endToken` the ids the decoding starts from and stops at (from the config files). See [Encoder and decoder pairs](#encoder-and-decoder-pairs). A model that lacks something it needs is refused before anything opens, with all of it [in the message](sessions.md#when-something-is-off). How sessions live and die: [Running sessions](sessions.md) |
| `sessionMod("name")` | A model to ask and nothing more: a snapshot of the head it has now, in memory. `.predict`, `.scores`, `.predictions`, `.run`, `.answer`, `.inference`, and `.version`, `.labels`, `.name`. It can't train or change the model, writes nothing to its `sessions.log`, needs nothing else open and stays on that head through a fine-tune or a rollback. Everything that asks for the same head shares one snapshot. It goes when the run ends, or with `.close()`; `val m by sessionMod("name")` works too. A jar is refused. See [Running sessions](sessions.md#ask-without-touching-the-model-sessionmod) |
| `sessionMod("name") { ... }` | The same for one block, the model's functions at hand: `sessionMod("name") { predict("text") }`. It goes when the block ends and the call returns the block's value |
| `model.inBackground("text")` | Asks any model a script holds on a thread of its own and returns at once with a job: `job.await()` is every raw answer, `job.await(millis)` waits that long at most and gives null if it isn't done, `job.done`, `job.state` (queued, running, finished, cancelled or failed), `job.cancel()`. At most `-Damie.model.jobs=N` run at once, half the cores by default, the rest wait first in, first out; calls made directly never wait. A job ends with the script that started it, however it ends, with the `ortClosable` block it was started in, which waits for it, and with its model; `await` throws when it was cancelled or failed. It is on the Sessions tab, queued or running, with Terminate; the tab lists the first 100 |
| `initMod()` | The bundled BERT NER model, kept open: `.inference("text")` is one tag per token |
| `group("name")` | The swarm of a model group of the env: the models asked together as one session (the one already open on the group, else a new one, listed on the Models page as a *model group* session). `.predict("text")` is the label with the best score across the models, `.scores("text")` each label's best score, `.vote("text")` the label most models give, `.each("text")` every model's own label, `.ask("text")` every model's raw lines, `.combined("text")` all of it from one asking (`.each` every model's labels and scores, `.merged` the labels with their best scores, `.vote`, as the page's Try line shows them); `.close()` closes the sessions it opened. When one of its models was terminated meanwhile (on the page, or a script's `.close()`), `group("name")` opens a new swarm in its place rather than handing out one that lacks that model. Groups are made on the Models page (a model's ⋯ menu) or with `groups` |
| `huggingFace("owner/name")` | A model of the Hugging Face Hub as `AMP/models/imported/<name>`, by its repo id or its huggingface.co address. It returns the local name, so `initMod(huggingFace("onnx-community/all-MiniLM-L6-v2"))` opens it. The first call downloads what the Models page would pick, a plain `model.onnx` when there is one, with the config and tokenizer files beside it (`amie hf import` at a terminal offers every file that runs here as a list picked with the arrow keys, that one under the cursor; `--take 3` picks for a pipe); an encoder and decoder pair takes its marked encoder and decoders, the menu `amie hf import` offers at a terminal said first. Once the folder is there, a call asks nothing of the network. `huggingFace("owner/name", "my-name")` picks the folder's name, which is the repo's last part by default. A private or gated repo needs `HF_TOKEN` in the app's environment. A repo that can't be read, has nothing that runs here or doesn't fit on the disk is refused with the reason. See [From Hugging Face](models-page.md#from-hugging-face) |
| `huggingFace.search("bert", format = "onnx", task = "any")` | Searches the Hub for models in a format `initMod` opens and returns their repo ids, the most downloaded first, printing each with its task, downloads and formats. `format` is `onnx`, `torchscript`, `tensorflow`, `tflite` or `all` (the default), `task` a Hugging Face task (`text-to-speech`, `automatic-speech-recognition`, `text-classification`, `token-classification`, `feature-extraction`, `text-generation`, `audio-classification`, `image-classification`, or a word for one: `tts`, `asr`, `ner`, `embeddings`, `labels`) or `any` (the default), and `limit` sets how many (20). Blank text with a task lists the most downloaded of that task. Formats are read off each repo's files, so weights-only repos are left out. Pass an id to `huggingFace("…")` |
| `groups` | The model groups of the envs: `groups.print()`, `groups.names()`, `groups.create("name", listOf("a", "b"))` (in the env the script is sourced in), `groups.add("name", "model")`, `groups.remove("name", "model")`, `groups.delete("name")`, `groups.closeAll()`. A group belongs to an env: it is kept in `AMP/env/<env>/groups.json`, `group("name")` looks in the script's env first, and `internalEnv()` lists them with the packages |
| `initAmie("text")` | Named entities in one text with the same NER model, opened and closed for the call: the tags are printed and returned. For many texts use `initMod()` |
| `initMod("whisper")`, `initMod("voice")` | The speech models `speech.properties` names, or `initMod("whisper-base.en")` for one by its folder. Whisper's `.answer(wav)` is what was said, a voice's `.answer(text)` is the file it spoke into. One session each, held by a block around it, on the Sessions tab, ended by Terminate |
| `model.can("chat")` | Whether a model can do something before you call it: `chat` and `generate` for a text model, `classify` and `fineTune` for a built one, `tag` for `initMod()`, `hear` and `speak` for speech, `run` for all |
| `initMod("name.jar")` | A new instance of a package jar: `.run("text")` calls its `run(text)` and returns every answer, `.answer("text")` the first (null when none), `.close()` its `close()`. `predict`, `scores` and `predictions` throw on a package: it has no labels. Looked up in the env, `AMP/pluginDir`, as a path, then fetched from the repositories in `AMP/github.json`; `initMod("github:owner/repo/x.jar@ref")` takes it from that repository (below) |
| `validateMetrics("name", labels = listOf(...))` | Prints the stored validation table of a model, with the range of its accuracy and macro F1 when the run kept them, and returns it: `.accuracy`, `.macroF1`, `.accuracyRange` and `.macroF1Range` (`low`, `high`; null for a run from before) |
| `metricsChart("name", labels = listOf(...))` | ASCII bars: accuracy, macro F1 and the F1 of each label with precision, recall and support |
| `metricsHistory("name")` | ASCII line chart of accuracy and macro F1 across every build |
| `confusionMatrix("name")` | The last validation's confusion matrix as a shaded ASCII grid |
| `mistakes("name")`, `suspects("name")` | The validation rows the last run got wrong, the surest first; the training rows a cross-validated build doubts. Texts only when the model keeps texts |
| `calibrationChart("name")`, `trainingCurves("name")` | How often each score bin is right and what a score gate keeps; loss and accuracy by epoch |
| `forwardContext("text") { ids -> }` | Runs the encoder and returns the hidden state `[batch][token][hidden]` |
| `importModelOnnx("path.onnx")` | Any ONNX model behind the `inference / getStatus / close` interface |
| `importModel("path")` | Any model by its path: an `.onnx` file, a TorchScript file (`.pt`, `.pth`, `.torchscript`), a TensorFlow SavedModel folder, or a folder holding one such as a Hugging Face repo. Text in with its `tokenizer.json`, labels from its `config.json`, tensors through `.inputs`, `.outputs` and `.run(...)`. Never throws: what can't be opened is one error line with what would work, and null. See [Models from outside](#models-from-outside) |
| `initMod("bert-onnx")`, `initMod("/path/model.pt")` | A model of `AMP/models/imported/<name>` (a Hugging Face repo or a folder copied in), or a model by its path, after every kind above: opened as `importModel` opens it, held by the block around it, on the Sessions tab with Terminate. A refusal throws, with all it lacks in the message |
| `sessions.print()`, `sessions.cancel("name")`, `sessions.cancelAll()`, `ortSessions()` | The open ORT sessions; `ortSessions()` returns them as the lines `sessions.print()` prints |
| `ortClosable(name) { }` | What the block opens is for that block alone: closed when it ends, however it ends, the last opened first, so a handle kept past it fails as closed. A session open before the block is used and left open. A template started in the block works inside it and ends with it. Returns the block's value. See [Scope functions](sessions.md#scope-functions) |
| `ortAsync(name) { }` | `ortClosable` run in the background: the block runs on a thread of its own and the call returns a job at once. What the block opens is closed when it ends, however it ends, and a template started in it ends with it. Its log lines go to the script's console. The job ends with the script, however it ends (done, stopped or terminated), and with an `ortClosable` block around the call, which waits for it: it is cancelled and what it opened is closed. `job.await()` is the block's value and rethrows its failure, `job.await(millis)` waits that long at most and gives null if it hasn't ended, `job.done`, `job.state` (running, finished, cancelled or failed), `job.cancel()`, which interrupts the block as Stop does a script. The name is `async` unless given. `val job = ortAsync("warm") { initMod("dummy").predict("turn the lights on") }`, then `log(job.await())` |
| `ortStatic(name) { }` | What the block opens stays open across runs until it is terminated or freed, and a session open before the block is kept too. Returns the block's value. See [Scope functions](sessions.md#scope-functions). `ortForever` is its old name: a saved script that calls it still runs, and the console says the new name |
| `ortRequired(name) { }` | Kept like `ortStatic` while anything else is in session: a model, package, group, template or background job that no required scope holds. The encoders models share, and an intent model's BERT encoder, don't count. Once nothing else is open it is freed within a couple of seconds, unless its block is still going or what it holds is running, and the block opens it again when next run. For what other models need beside them, an encoder they share, say. Returns the block's value |
| `ortLimited(name, max) { }` | Kept like `ortStatic`, at most `max` sessions under the name: when one more is opened the oldest is closed first. Returns the block's value |
| Memory running short | Below 32 MB of free heap or 512 MB of free RAM, the app closes the idle sessions of `ortLimited` scopes and the model `handleBy` opened, oldest first, one at a time, with a console line for each. It never closes what is running, what a block still going holds, what has a `model.inBackground` job on it, or anything in `ortStatic`. A script that keeps such a session gets an error on its next call, as after `ortScopes.free` |
| `ortScopes.print()`, `ortScopes.free("name")`, `ortScopes.freeAll()` | What each static, required and limited scope holds; closing what one holds, or what all of them hold, which return how many were closed |
| `handleBy(handler) { }` | Runs the block and returns its value; when it throws, the handler's value for what it threw instead, and the run goes on: `val text = handleBy({ e -> log("failed: ${e.message}"); "" }) { initMod("dummy").predict("hi") }`. A stop, a cancel (Stop, Terminate, a template's or a job's cancel) and an Error such as `OutOfMemoryError` are never handled: they go on as they would |
| `handleBy("message")` / `handleBy(e)` | What the local text model (`AMP/models/text`, the one `assistantModel` in settings.json names, else the first) makes of an error: what is wrong and how to fix it, from the message, main.kts and the env, printed and returned. It only answers; the script stays as it is. `handleBy({ e -> handleBy(e) }) { source(Path("intents.csv")) }` asks about what the block threw. Without a text model, or short of RAM to open one, an error line says why and the answer is `""`. The model stays open five idle minutes, as **handleBy** under Still open |
| `errorLog(last = 20)`, `handleBy()`, `handleBy(lines)` | The error terminal's log: the last error lines the console showed, oldest first, as it has them: a run's failures, what a command printed on stderr and why it failed, the app's own error lines. Emptied with the console, which a run does when it starts, so they are the run's. `handleBy()` hands the last ten of them to the local text model with main.kts and the env, so one call after a failed command or run asks about what the console showed in red; `handleBy(lines)` takes any list of lines. With nothing there it says so and asks nothing |
| `importModelTensorflow(Path.of("…"))` | `importModel` under its old name: a saved script still runs, and the console says the new name |
| `packages.available()`, `packages.fetch("x.jar")`, `packages.print()`, `packages.closeAll()`, `packages.github()`, `packages.check("x.jar")`, `packages.meta("x.jar")`, `packages.config("x.jar")`, `packages.setConfig("x.jar", "key" to "value")`, `packages.test("x.jar")` | The package jars the GitHub repositories offer, a download into `AMP/pluginDir`, the open package sessions; the jars of the GitHub repositories in `AMP/github.json`; the check `initMod` and a fetch make, a jar's metadata, the keys and values of its config files and setting them, and its tests run (below) |
| `saveState("name", json)`, `loadState("name")`, `listStates()` | A JSON text kept per user on the Amie backend (signed in): store it under a name, read it back (null when absent), list the names. The sandbox has no state that outlives a run; this is how a script remembers a choice across runs and machines |
| `database(name = "main")` | A Postgres database from `AMP/databases.json`: `.query(sql, params…)` rows as maps, `.scalar`, `.execute` for changes, `.print(sql)` a table in the console, `.tables()`, `.close()`. Failures are one Error line and a `DatabaseException` |
| `databases.print()`, `databases.names()`, `databases.closeAll()` | The configured databases |
| `shell("du -sh ~/Documents")` | Runs a command in your login shell and returns what it printed: `.ok`, `.exit`, `.out`, `.err`, `.lines`. `dir`, `timeoutSeconds` and `env` are optional ([Shell commands](#shell-commands)) |
| `shellScript("~/bin/lookup.sh", listOf("arg one", "arg two"))` | Runs a script file with its arguments passed one by one; returns what `shell` returns |
| `exportJvm("main.kts", to = "~/bundles/talk")` | Exports a script as a bundle that runs without the app, as **Export…** does ([Exporting a script](sandbox.md#exporting-a-script)): main.kts, a template's name or id, or a file, into a new or empty folder, the first free one under `~/amie-bundles` when `to` is empty. What can't be included stops it unless `anyway = true`; `planOnly = true` only says what would go in; `compiled = false` carries the text and the compiler. The console says what goes in and where it went; returns the bundle's folder, null when nothing was written |
| `serve("name", port = 8765)` | Serves the model over HTTP like the Models page's Listen; returns the predict URL or null. `minScore = 0.75` makes it abstain under that score, answering `fallbackLabel` or no label ([Serving a model](serving.md#answering-only-when-it-is-sure)); `minScore = 0.0` and `fallbackLabel = ""` take saved ones away, and a min score outside 0 to 1 doesn't start it. `stopServing("name")`, `servingUrl("name")`, `accessLog("name", last = 20)` (the last requests of its [access log](serving.md), printed and returned) |
| `importPlugin("plugin.jar")` | Runs a static `run()` or `main` of a plugin jar once (not a package session; open packages with `initMod("x.jar")`), found in the env, `AMP/pluginDir`, as a path or in the GitHub repositories; never throws: a missing jar, a jar without an entry point or a plugin that fails is one Error line and the script goes on |
| `implementation("group:module:version")`, `dependency("…")`, `amie.import("...")` | Add a package to the script's environment (`dependency` is another name for `implementation`) (the Models page's **Add → From Maven Central…** does the same with a search) |
| `implementation("my.jar")`, `amie.import("my.jar")` | Add a jar file to the environment |
| `environment(SettingsEnv("name", "profile"))`, `amie.import(SettingsEnv(...))` | Select the script environment (must be written literally) |
| `internalEnv()` | Print what the environment contains |
| `implementation(BuildSettings(...))` | Evaluates Gradle build settings in the background |
| `coroutineTemplate("name", input = listOf(...))` | Starts a saved template in the background and the script goes on; the run it returns answers to `.send(text)`, `.receive()`, `.closeInput()`, `.await()`, `.cancel()` and `.state`. See [Templates in the background](#templates-in-the-background) |
| `input()`, `output("text")` | The next line of input, waiting for one, null once input is closed; and an answer for `receive()`. In a template the line comes from `.send`; in main.kts it is typed in the console, or read from standard input in an exported bundle, and `output` logs |
| `templateRuns.print()`, `templateRuns.cancelAll()` | What runs in the background, and cancelling all of it |

## Models from outside

`initMod("<name>")` opens the folder `AMP/models/imported/<name>`, and `initMod("/path/to/model")` or
`importModel("/path/to/model")` a model file or folder anywhere. The kinds `initMod` knew before come first: an
intent model, a text model or a speech model of that name is opened as ever, then an imported name, then a path.

| Format | Files | Runs on |
|---|---|---|
| ONNX | `*.onnx`, also a repo's `onnx/model.onnx` | The ONNX Runtime the app ships |
| TorchScript | `*.pt`, `*.pth`, `*.torchscript` saved with `torch.jit.trace` or `torch.jit.script` | PyTorch 2.5.1 through DJL 0.31 |
| TensorFlow | A SavedModel folder: `saved_model.pb` and `variables/` | TensorFlow 2.16.1 through DJL 0.31, in the lightweight build |
| TFLite | `*.tflite` | Refused: DJL dropped TFLite after 0.27. Convert it with `python -m tf2onnx.convert --tflite model.tflite --output model.onnx` |

Refused with what would work: a frozen TensorFlow graph (any `.pb` but `saved_model.pb`), PyTorch weights saved with
`torch.save` (a `.pt` or `.pth` with no code in it), and weights alone: `*.safetensors`, `pytorch_model.bin`,
`*.gguf`, `*.h5`, `*.keras`. No engine runs those without the model's Python code, so export the model to ONNX
(`optimum-cli export onnx --model <repo> <folder>`) or TorchScript, or take the repo's `onnx/` folder when it has one.

A folder with several formats opens its ONNX first, since it runs on the bundled runtime; otherwise the format its
`source.json` names (`"format": "onnx|torchscript|tensorflow|tflite"`, which a Hugging Face import writes); otherwise
the first that runs on this machine. Whenever there was a choice, the console says which was taken. Of several files
of one format, `model.onnx` comes before `onnx/model.onnx`, and a full model before a quantized or fp16 one.

```kotlin
val bert = initMod("distilbert-sst2")                 // AMP/models/imported/distilbert-sst2, its onnx/model.onnx
log(bert.predict("what a lovely day"))                // POSITIVE, from its config.json id2label
log(bert.inputs)                                      // [input_ids int64 [-1, -1], attention_mask int64 [-1, -1]]

val resnet = importModel("~/models/resnet18.pt")      // TorchScript on PyTorch, which declares no shapes: give one
val image = Tensor.of(FloatArray(3 * 224 * 224), 1, 3, 224, 224)
log(resnet?.run(mapOf("image" to image))?.values?.first())   // output0 float32 [1, 1000]: …

val tf = initMod("/models/classifier/saved_model")    // a SavedModel folder, lightweight build
log(tf.run(mapOf("input_1" to floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))))
```

- **Text** goes in when a `tokenizer.json` is next to the model or in a folder above it, up to the imported folder: the
  token ids, the attention mask and the token type ids fill the inputs of those names (a TorchScript model, which
  names none, gets the ids and the mask in that order). Without one, `predict("0.1, 0.2, 0.3")` reads the text as
  numbers. The labels come from `config.json`'s `id2label`.
- **A language model** (GPT-2, Llama, BERT for masks: logits `[1, tokens, vocab]`, such as `[1, 1, 50257]`, which is a score for every token of the vocabulary at every place, not a hidden state) answers in text. One that continues text (`…LMHeadModel`, `…ForCausalLM` in its `config.json`) goes on greedily, a run a token, up to 24 tokens or its end token: `answer(text)` and `predict(text)` are that text, the rest of `run(text)` its 5 likeliest next tokens as `token:probability` (`scores(text)`). One that fills a mask (`…ForMaskedLM`) gives the likeliest tokens at `[MASK]`. An export that wants its key and value cache (`past_key_values` inputs) gives its first token only
- **Tensors**: `.inputs` and `.outputs` are each tensor's name, shape (`-1` for a size left open) and type. TensorFlow
  and ONNX say them as soon as the model is open, TorchScript after its first run. `.run(floatArrayOf(…))` and
  `.run(longArrayOf(…))` feed the one input, in its shape with a batch of one (one row when the model says no shape);
  `.run(mapOf("name" to values))` feeds each by name, a value being an array or `Tensor.of(values, 2, 3)` for a shape of
  your own. TorchScript takes its inputs in the map's order, whatever their names. Every output comes back by
  name as a `Tensor`: `.shape`, `.type`, `.floats()`, `.longs()`.
- **Natives**: PyTorch's and TensorFlow's native libraries are downloaded from publish.djl.ai into `~/.djl.ai`
  (`DJL_CACHE_DIR` moves it) the first time a model of theirs is opened on a machine, which the console says once with
  the size: PyTorch about 40 MB on an Apple Silicon Mac, 65 MB on a Raspberry Pi 5, 110 MB on Linux x86-64; TensorFlow
  130 to 150 MB. The CPU build is taken unless `PYTORCH_FLAVOR` says otherwise. Offline, the first open fails saying
  what it wanted; after that they are read from the cache. DJL's telemetry is turned off as it is for the tokenizers.
- **Where each runs**: PyTorch on macOS arm64, Linux arm64 and x86-64 and Windows x86-64, not on an Intel Mac.
  TensorFlow everywhere, but only in the lightweight build (`-Pamie.lightweight=true`, the default on an Apple
  Silicon Mac and arm64 Linux): the full build carries
  TensorFlow 1.15 for KotlinDL, whose classes clash with TensorFlow 2's. A model that can't run on the machine or in
  the build is refused naming what is missing.
- **Lifetime**: every call opens a new model. It is held like `importModelOnnx`'s: closed with the `ortClosable` block
  around the call, kept by a static, timed or limited one, listed on the Sessions tab (an *Imported model* row for
  PyTorch and TensorFlow, an *ONNX session* row for ONNX) with Terminate, and `.close()` gives its natives back once the
  runs in flight are done. `importModelTensorflow(path)` is `importModel` under its old name.

### Encoder and decoder pairs

A sequence-to-sequence export (T5, BART, Marian translation, Whisper) is two ONNX files in one folder,
`encoder_model.onnx` with `decoder_model.onnx` and often `decoder_with_past_model.onnx`. Neither answers a text alone:
the encoder turns the input into hidden states, the decoder takes those with the ids written so far and gives the
next token's scores, one step at a time. `amie hf import` offers every encoder and decoder of the folder at the terminal, picked with the arrow keys, the
default marked (the full variant, the merged decoder left out when the plain and the with-past ones are there): space
takes or leaves the file under the cursor (the quantized ones, say), Enter imports, `n` leaves; `--take "2 6 8"` picks
for a pipe. `huggingFace("…")` in a script takes the marked files.
`initMod("<name>")` then opens both sessions, and a script wires them. `.inference(text)`, `.predict` and `.answer`
refuse on a pair, saying how.

| Member | What it is | Comes from |
|---|---|---|
| `m.encoder` | The encoder session, run with tensors: `.inputs`, `.outputs`, `.run(mapOf(…))` | `encoder_model.onnx` |
| `m.decoder` | The decoder session, the same way | `decoder_model.onnx` |
| `m.decoderWithPast` | The decoder that takes the past attention state back, or null when the export has none | `decoder_with_past_model.onnx` |
| `m.tokens(text)` | The token ids and the attention mask of a text, as `.ids` and `.mask` | `tokenizer.json` beside the models |
| `m.text(ids)` | The words of a list of ids | The same tokenizer |
| `m.startToken` | The id the decoder starts from, or null when no config names one | `decoder_start_token_id`, else `bos_token_id`, of `generation_config.json`, then `config.json` |
| `m.endToken` | The id that ends the decoding, or null | `eos_token_id` of the same files |
| `m.encode(text)`, `m.encode(file)` | The encoder run: a text through the tokenizer, or an audio or image file through the preprocessor; `.hidden` the states, `.mask` the text's mask | `tokenizer.json`, or `preprocessor_config.json` |
| `m.decoderFeeds(encoded, ids)` | The decoder's inputs for one step: the ids so far, the hidden states, and the encoder's mask when the decoder names one | `m.decoder.inputs` |
| `m.startTokens(encoded?, vararg tokens)` | The ids the decoder starts from: the start id and the forced ones after it, each replaceable by a name the config knows: a language (`"de"`), a task (`"translate"`, `"transcribe"`), `"timestamps"` or `"notimestamps"`. A language left open and not named is detected from the encoding, else English | `forced_decoder_ids`, `lang_to_id`, `task_to_id`, `no_timestamps_token_id` of the config files |
| `m.preprocessor` | The `preprocessor_config.json` as JSON: sampling rate, mel bins, image size, mean and std | That file |
| `m.abilities` | `encoder`, `decoder`, and `decoderWithPast`, `tokens`, `startToken` when the folder has them; `text`, `audio` or `image` for what the encoder takes | The folder |
| `m.getStatus()` | Each session's status, by name | Both sessions |
| `m.close()` | Closes every session of the pair | |

What the encoder takes is read off the folder: a text pair has no `preprocessor_config.json` and takes text through its
tokenizer; one whose file names a feature extractor (Whisper) takes an audio file, wav or aiff at any rate, turned into
the log-mel spectrogram that file describes (80 bins, 16 kHz, 30 seconds, padded with silence or cut); one naming an
image processor (TrOCR, Donut, Nougat) takes an image file, resized to `size`, rescaled and normalized by `image_mean`
and `image_std`. `encode` does whichever applies, and `decoderFeeds` fills the decoder's inputs by their names, Marian's
`encoder_attention_mask` among them. The same greedy loop then translates or transcribes:

```kotlin
val m = initMod("t5-small")                                   // or "whisper-tiny", with a wav in place of the text
val e = m.encode("translate English to German: The house is wonderful.")
val starts = m.startTokens(e)                                 // Whisper: start, language (detected, or startTokens(e, "de")), transcribe, no timestamps
val ids = starts.toMutableList()
for (i in 1..40) {
    val logits = m.decoder.run(m.decoderFeeds(e, ids.toLongArray())).getValue("logits")   // [1, ids so far, vocabulary]
    val vocab = logits.shape.last().toInt()
    val last = logits.floats().takeLast(vocab)                 // the scores at the last place
    val best = last.indices.maxBy { last[it] }
    if (best.toLong() == m.endToken) break
    ids.add(best.toLong())
}
log(m.text(ids.drop(starts.size).toLongArray()))              // Das Haus ist wunderbar.
m.close()
```

Under `encode`, the sessions are still there for a script that wants them raw: `m.tokens(text)` and
`m.encoder.run(mapOf("input_ids" to t.ids, "attention_mask" to t.mask))` are what `encode(text)` does, and
`m.encoder.inputs` names what a file becomes (`input_ids`, `input_features`, `pixel_values`).

Running the whole id list each step is simple, not fast. A faster loop runs `m.decoder` once, then `m.decoderWithPast`
with each step's `present.*` outputs fed back as its `past_key_values.*` inputs, which its `.inputs` name.

## Reading a prediction

Every model session, whatever opened it (`initMod("name")`, `importModelOnnx(...)`, `group("name")`), answers
through the same three calls on top of `inference`. A package (`initMod("x.jar")`) has no labels and refuses them:
call it with `run(text)` / `answer(text)` instead. A `sessionMod("name")` answers through the same calls.

| Call | Returns | Example |
|---|---|---|
| `predict("text")` | The predicted label: the best answer of `inference` without its score (the argmax of a classifier). Throws when the model answers nothing | `"lights"` |
| `scores("text")` | Every label with its score, best first, as a `Map<String, Float>`. Answers without a score are left out | `{lights=0.67, weather=0.50, greeting=0.33}` |
| `predictions("text")` | The raw answers parsed into `Prediction(label, score)`, in the model's order; `score` is null when the answer had none | `[lights:0.67, weather:0.50, …]` |
| `inference("text")` | The raw lines a model answers: `label:score` for a classifier, one tag per token for the NER model, whatever `run(text)` returned for a package | `["lights:0.67", "weather:0.50", …]` |
| `run("text")` | The same raw answers, the name to use for a package | `["lights:0.67", "weather:0.50", …]` |
| `answer("text")` | The first raw answer as it came, scores and all; null when there was none | `"lights:0.67"` |

```kotlin
val m = initMod("intents")
log(m.predict("turn the lights on"))                        // lights
val scores = m.scores("turn the lights on")                 // {lights=0.97, weather=0.02, greeting=0.01}
log("confidence ${scores.values.first()}")                  // 0.97
if (scores.values.first() < 0.5f) log("not sure", LogLevel.ERROR)
m.predictions("turn the lights on").forEach { p -> log("${p.label} ${p.score}") }
```

Each call is logged as a Data line in the console in the shape it was asked: `predict` logs the text and the one
label with its score (`intents "turn the lights on" -> lights 0.970`), `scores` and `inference` the text and every
label with its score. Each of the three calls runs the model once, so ask once and keep the result instead of
calling `predict` and `scores` on the same text. A package (`initMod("x.jar")`) has no labels: `predict`, `scores` and
`predictions` throw on it, so ask it with `run` or `answer`.

## Building a model

```kotlin
val train = source("intents.csv")          // columns: text,label
relate(train, "intents-test.csv")          // optional test file
val model = modelBuilder("intents", train)
model.modelStatus()                        // the stored validation table
model.modelChart()                         // bars, build history and confusion matrix

val m = initMod("intents")
log(m.predict("turn the lights on"))                 // lights
log(m.scores("turn the lights on").toString())       // {lights=0.97, weather=0.02, greeting=0.01}
m.close()
```

`intents.csv` stands for your own file. `source()` finds it as given, relative to the folder amie was started in, then in `AMP/`, then in `AMP/pluginDir/`; an absolute path is taken as it is. A new AMP folder has `sample-intents.csv` (45 rows) to try, and a name that is not found is reported with the folders looked in and the csv files of AMP whose names come closest.

The charts are described with examples in [Models and metrics](models.md#reading-the-metrics).

## Packages

A package is a jar with one public class that fulfils the backend's `AmiePackage` interface: a public no-arg
constructor, `run(input: String): List<String>` and `close()`, both required. That is the contract the website
checks on upload and the samging/jar-jar workflow checks on every jar; implementing the interface is the easiest way
to get there, and any class with the same methods qualifies. amie's own loader is more lenient (any non-void `run`,
`close()` optional), so such a jar opens locally but cannot be uploaded. [The amie
guide](../amie/README.md#writing-a-package-run-and-close) has the whole contract with an example.
Packages are bottles: every `initMod("name.jar")` makes a fresh instance in its own class loader, so two sessions
on the same jar never share state, and `close()` releases that instance and its loader. The jar is looked up in the
active env, then `AMP/pluginDir`, then as a path; when it is nowhere local it is fetched from the GitHub
repositories of `AMP/github.json` (below) and stored in `AMP/pluginDir`, so the next open is local. The Amie server
(the website's `/api/jar-download`, else the S3 service's `/api/aws/jars/<name>`) is off, like accounts and Keycloak,
until `AMIE_ACCOUNTS=1` turns them on. Next to it, `AMP/pluginDir/<name>.source.json` records where
it came from: the exact download URL, the website page that lists and describes it (`<site>/downloads`), version,
size, publish date and fetch time. `getStatus()` of a package session carries the `url` and `page`, and the
Models page shows them behind the **i** toggle of the package.

```kotlin
val echo = initMod("echo-1.0.jar")        // fetched from GitHub when it is not local
echo.run("hello there").forEach { log(it) }   // every answer of its run(text); echo.answer(...) the first
log(echo.getStatus().toString())          // jar, class, source, runs
echo.close()

packages.available().forEach { log("${it.name} ${it.size}") }   // what the repositories list
packages.print()                                                   // open package sessions
```

### Jars from GitHub

A jar can come from a GitHub repository. `initMod("github:owner/repo/x.jar")` takes it from that repository's
default branch; `github:owner/repo/libs/x.jar@v1.2` names a folder and a branch, tag or commit, and a github.com
file address (`https://github.com/owner/repo/blob/main/x.jar`) works too. A name that is not a file of the
repository is looked for among the assets of its latest release (or of the release tagged with the ref). A plain
`initMod("x.jar")` that is nowhere local is looked for in the repositories listed in
`AMP/github.json` (`{"repos": ["samging/jar-jar"], "token": null}`, written on first use).

```kotlin
packages.github()                                       // the jars of the repositories in AMP/github.json
packages.github("samging/jar-jar")                      // one repository's
val tr = initMod("github:samging/jar-jar/google-translate.jar")
log(tr.answer("to de: good morning"))                   // Guten Morgen
packages.check("google-translate.jar")                  // package class com.amie.translate.GoogleTranslate (run(String) and close() by shape)
```

**Every downloaded jar is checked before it is kept** (from GitHub, and from the Amie server when accounts are on): it must read as
a jar, its classes must load on this app's Java, its metadata's `java` must hold, and a class must satisfy the
package contract (public, a no-arg constructor, `run(String)` returning the answer, `close()`; implementing
`AmiePackage` is the easiest way), and when the metadata names the `entry` class, that class must be it. The check
loads classes without initializing them, so no code of the jar runs. A jar that fails is not kept (a copy already
in `AMP/pluginDir` stays) and the reason is the error. The check is about the contract, not about trust: only open
jars from repositories you trust.

A jar from GitHub lands in `AMP/pluginDir` with its origin beside it (`repo`, `sha`, page). A `github:` open asks
GitHub for the file's current version and downloads it only when it changed; when GitHub cannot be reached, the
copy already there is used. A jar of the same name that came from elsewhere is replaced only when its bytes are the
repository's (same git blob sha); otherwise the open fails and says so. Without a token GitHub allows 60 requests an
hour; `token` in `AMP/github.json` or `GITHUB_TOKEN` raises that and opens private repositories. Without a token a
private repository answers as if it were empty, so its jars are reported as not found. On the Models page the
repositories' jars are listed under **On GitHub**, with **Fetch from GitHub** in their menu.

A jar reaches samging/jar-jar through a pull request that adds it at the top of the repository. The repository's
**Check jars** workflow checks every jar the way amie checks a download, with the same rules as above and without
running any code of the jar. A second step then opens each jar as `packages.test` does: it calls `run()` with every
declared test and then `close()`, each jar in a JVM of its own. A class without `List<String> run(String)` and `close()` fails; missing metadata is a note. Once merged, a plain
`initMod("x.jar")` finds it; a machine that already has an older copy in `AMP/pluginDir` takes the new one through a
`github:` open. [The amie guide](../amie/README.md#packages-from-github) has the steps.

The package class is the one the jar's metadata names (below) when it satisfies the contract, else the manifest's
`Main-Class` when it does, else a class implementing an interface named `AmiePackage`, else the first class in the
jar that does. `run` executes on amie's `amie-package-call` thread with the jar's class loader as context loader while the caller
waits; stopping the script interrupts that thread. What it returns is shown one line per element. Open package sessions
also appear under **Sessions** on the [Models page](models-page.md).

### What a jar needs: metadata, configuration and test runs

A jar can say what it requires in `META-INF/amie/package.json` (or `amie-package.json` at its root). For a jar
that ships none, `AMP/pluginDir/<jar>.package.json` beside it says the same (**⋯ > Write metadata template** on
the Models page writes one to fill in); when both exist, the sidecar's fields override the jar's. The shape:

```json
{
  "name": "weather", "version": "1.2", "description": "answers with the forecast for a place",
  "java": 21,
  "entry": "com.example.Weather",
  "config": [
    {"file": "weather.properties", "required": true, "description": "the API key", "template": "apiKey=\n"}
  ],
  "env": [{"name": "WEATHER_PROXY", "required": false, "description": "an HTTP proxy, if one is needed"}],
  "tests": [
    {"name": "a known place", "input": "Paris", "contains": "Paris"},
    {"name": "answers in one line", "input": "Berlin", "lines": 1},
    {"name": "exact", "input": "ping", "expect": "pong"}
  ],
  "notes": "free text for whoever runs it",
  "flags": [{"text": "stable", "color": "green"}, {"text": "v2 api", "color": "blue"}]
}
```

`flags` are coloured tags the Models page shows on the jar's row and panel beside its version, Java, language and
test flags; the colour is one of slate, green, amber, red, violet, blue. Flags added on the page go to the
sidecar and add to the jar's own.

The runtime reads it before the jar is opened (`pluginEngine/PackageMeta.kt`, `JarPackages.open`):

- **Configuration lives in `AMP/pluginDir/config/<jar without .jar>/`.** The folder is made on the first open,
  and every declared `config` file that is missing is written from its `template`, so the person finds a file to
  fill in instead of a stack trace. The package finds the folder three ways: it is the **first root of the jar's
  class loader**, so `getClass().getClassLoader().getResource("weather.properties")` (or `getResourceAsStream`)
  resolves there, ahead of a default copy inside the jar; the system properties **`amie.package.config`** (the
  folder) and `amie.package.name` (the jar) are set while it is opened, with `amie.package.config.<jar>` kept
  for the jar; and a public **`configure(File)`**, `configure(Path)`, `configure(String)` or
  `configure(Map<String, String>)` method of the package class, when it has one, is called with the folder right
  after the constructor (the map holds `dir` and each file in the folder by name). `importPlugin("x.jar")` loads a
  jar the same way, but it isn't refused as below. `amie packages setup <x.jar>` asks for each key at the terminal,
  and `amie packages config <x.jar> key=value` sets one, so the files need no editor. In a script, `packages.config("x.jar")` reads the keys
  and `packages.setConfig("x.jar", "key" to "value")` sets them; at the prompt, `:config` and `:setup` do the same.
- **`initMod` refuses a jar that lacks what it says it needs**, before any instance is made: a required config file
  that is missing, a required one that still equals its template (the template is for the person to fill in, so
  mark a config whose template works as it stands `"required": false`), or a required `env` variable that is not
  set. The message names every problem and the config folder, and the template is written first, so there is
  something to fill in. A jar that declares nothing runs as before.
- **`java`** is checked against the Java this app runs: a jar that needs a newer one is refused with the reason.
- **`entry`** picks the package class when the jar has more than one candidate.
- **`tests`** are the jar's own test runs: `packages.test("x.jar")` in a script, or **Test run** on the Models
  page, opens a fresh session as `initMod` would (configuration included), sends each `input` through `run`,
  checks the answer against `expect` (the whole answer), `contains` (some line) and `lines` (how many), closes
  the session, prints the report and keeps it as `AMP/pluginDir/<jar>.tests.json`. A test with none of the three
  passes when `run` does not throw. With no tests declared the run is one call with `"hello"` that must answer.

```kotlin
packages.meta("weather.jar")?.let { log("needs Java ${it.java}, files ${it.config.map { c -> c.file }}") }
val report = packages.test("weather.jar")          // the console gets one line per test
log(if (report.ok) "all ${report.passed} passed" else "${report.failed} failed")
```

The Models page shows all of it on the jar's panel: **Requirements** (Java, package class, metadata, config
folder, every config file and variable, the last test run, each marked met or not, with the verdict `initMod`
gives), **Config** (the folder with
**Reveal folder**, the files and their state) and **Tests** (what is declared, and the last run's answers).

### Session logs

(How to work with sessions day to day, keep them warm between runs and close them is in [Running sessions](sessions.md).)

Every session of a model or a package keeps a log of what it was asked and answered: an `open` line, one `run`
line per `inference` call with the text, the first answers and the time it took, an `error` line when the call
threw, and a `close` line with the number of runs. The Models page shows it behind **Log** on the session, and the
same lines are appended to `AMP/models/intents/<name>/sessions.log` for a model and `AMP/pluginDir/<jar>.sessions.log`
for a package (`runtime/SessionLog.kt`), so a terminated session's history stays readable:

```
2026-10-14T17:40:02.118Z dummy #3 open  opened as session #3
2026-10-14T17:40:05.402Z dummy #3 run   "turn the lights on" -> lights:0.61, weather:0.22, greeting:0.17 (14 ms)
2026-10-14T17:41:10.009Z dummy #3 close closed after 1 runs
```

### Building a package

The **Packages** tab of the Models page reads a jar's classes and resources, the Java it needs (from the class
file version) and what built it (the manifest), so a jar can be checked before a script opens it. The shape of a
build that produces one:

```kotlin
plugins { kotlin("jvm") version "2.1.0" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
tasks.jar {
    archiveFileName = "echo-1.0.jar"
    manifest { attributes("Main-Class" to "org.example.Echo") }
}
```

```kotlin
package org.example

class Echo {
    fun run(text: String): List<String> = listOf("echo: $text")
    fun close() {}
}
```

The app runs on Java 21, so a jar compiled for Java 21 or older loads; the page says so next to the class file version.

Accepted data shapes: a `DataSource` from `source`, `List<Pair<text, label>>`, `Map<label, List<text>>`, or
rows of `[text, ..., label]`.

### What modelBuilder does

`buildClassifier` in `jetbrainDLBuilder/JetBuilder.kt`:

1. **checking data**: the preprocessor (`jetbrainDLBuilder/Preprocessor.kt`) cleans the rows, unifies label
   spellings, drops empty, junk, repeated and conflicting rows with a reason each, measures the set and refuses one
   a classifier cannot come out of; every line goes to the console and `metrics.json`. The reasons are listed in
   [Models and metrics](models.md#before-training-the-preprocessor).
2. **embedding**: every text goes through the BERT encoder once; the hidden states are mean-pooled into one vector
   each. A text embedded before comes from `AMP/cache/embeddings` (`EmbeddingCache.kt`).
3. **training**: every candidate head (`Architecture.kt`; by default linear, `dense[16]`, `dense[32]`, `dense[64, 16]`,
   or what `buildOptions` names) is trained with Adam on plain Kotlin arrays (`DenseNet.kt`, any number of hidden
   layers, relu or tanh; weight decay, dropout, label smoothing, rate schedules and early stopping when a head asks
   for them) on the same rows, scored on the same validation rows, and the best by macro F1 (or accuracy) is kept;
   with `folds` the heads are compared over the folds instead (`TrainingKit.kt`, `Search.kt`). The comparison and
   every head's learning curve are stored with the build.
4. **validating**: the chosen head on the related test file, or on a stratified 20% split; precision, recall, F1 per
   label, then the calibration error and its gates, the rows it got wrong, and with `calibrate` a temperature folded
   into the last layer (`Calibration.kt`).
5. **exporting**: the layers are written by hand as ONNX (opset 13) to `AMP/models/intents/<name>/model.onnx` with
   `module.json` (encoder, dims, labels, version) and `metrics.json` (one entry per build); the head it replaces is
   kept under `versions/` (`modules/ModelVersions.kt`). `fineTune` (`FineTune.kt`) and `calibrate`
   (`ModelCalibration.kt`) end the same way.

### Build progress

`ModelBuildProgress` (`jetbrainDLBuilder/BuildProgress.kt`) is a `StateFlow` the builder updates at every phase and
step: text *n* of *N* while embedding, epoch *n* of 150 while training, each validation sample, then export. Phases are
weighted (embedding 60%, training 30%, ...) into one fraction that drives:

- the **console strip** (`running 12s · modelBuilder 'intents' training 72/150 · epoch 72 loss 0.3102 accuracy 0.912 · 48%`, with a thin bar under it),
- the build line at the top of the Models page's list, with its thin bar.

Every 25 epochs the loss and accuracy are also logged to the console. Finished builds (with their duration and
outcome) stay in `ModelBuildProgress.history`; the console strip's tooltip names the last one while idle.

## Saved states

Not available yet: accounts, Keycloak and the Amie backend are off, so these calls print one Error line and do
nothing until `AMIE_ACCOUNTS=1` turns them on. Signed in then, a script can keep small JSON documents on the Amie backend, which stores them as
`uploads/<user>/state/<name>.json` next to your packages (your devices live there as `devices`). The calls block
like a package fetch and print one line each; a failure (a guest, an unreachable backend, a bad name) is one Error
line and `loadState` then returns null. A name matches `[A-Za-z0-9._-]{1,64}`, a document is at most 1 MiB.

```kotlin
saveState("labels", """{"intents": ["greet", "bye"]}""")   // PUT /state/labels
listStates().forEach { log(it) }                                 // devices, labels, ...
val labels = loadState("labels")                                 // the JSON text, null when there is none
```

## Shell commands

A script can run commands and script files on the computer and use what they print.

```kotlin
val r = shell("ls -la ~/Documents | head")      // runs it, the console shows it as it runs, then returns
log("exit ${r.exit}, ${r.lines.size} lines")
if (r.ok) log(r.out) else error(r.err)

shell("du -sh .", dir = "~/Documents", timeoutSeconds = 60, env = mapOf("A" to "1"))
shellScript("~/bin/lookup.sh", listOf("arg one", "arg two"))
```

`shell` runs the command through your login shell (`$SHELL -lc`, so your PATH and tools work; `/bin/sh -c` when
`SHELL` is not set and `cmd /c` on Windows). A login shell reads `.zprofile` or `.profile`, not `.zshrc`, so a tool set
up only in `.zshrc` is not found. `shellScript` runs a file: directly when it is executable, through the shell
otherwise. Its arguments go to it one by one and are never joined into a command line, so spaces and quotes arrive as
they are. A relative path starts from `dir`.

| On the result | |
|---|---|
| `.ok` | The exit was 0 and it was neither timed out nor cancelled |
| `.exit` | Its exit code, -1 when it could not start |
| `.out`, `.err` | What it printed, without the line breaks at the end. As text the result is `.out`: `"${shell("whoami")}"` |
| `.lines` | `.out` as a list of lines |
| `.timedOut`, `.cancelled` | How it was stopped |
| `.command` | What ran, as the console showed it |

- A command that fails is not an exception. A non-zero exit gives `ok = false` and an error line with the exit code,
  so the script decides. A command that can't start, a missing script file and a missing `dir` are the same, with exit
  -1 and the reason in `.err`.
- `.patchBy { error -> ... }` runs when the command failed, with what it said, and gives the result back. The function can send it anywhere: `.patchBy(::log)`, a voice, `llama.answer(error)`, a template, or `.patchBy(::handleBy)` for the local text model. A command stopped with its script doesn't count.
- What a command printed on stderr, and why it failed, are the error terminal's lines too: `errorLog()` pulls the last ones and `handleBy()` hands them to the local text model, with main.kts and the env, as `.patchBy(::handleBy)` hands what the one command said the moment it fails.
- A relative path for `shellScript` that isn't there is looked for under `AMP/` and `AMP/pluginDir`, so `shellScript("voice/weather.sh")` finds a script of AMP and an export carries it.
- Each command is printed to the console before it runs, and what it prints follows as it arrives, both at the
  internal level: turn **Internal** on in the console strip to see them, so a script that runs commands keeps the
  console for what it logs itself. stderr comes as error lines. A command that
  fails turns the error level on, as a failed run does, so its reason is in view. All of it also goes to
  `AMP/logs.txt`. None of this goes to the error log on the backend, unlike other error lines.
- `dir` is where it runs; without it, your home folder, where a new terminal starts. `~/` works in `dir` and in a
  script's path.
- `timeoutSeconds` is 120 unless given, and 0 means no limit. After it the command and everything it started are
  stopped and `.timedOut` is true. Interrupting the script's thread does the same, with `.cancelled`, and the thread
  stays interrupted. Commands still running when the app exits are stopped too. A command that puts something in the
  background and ends (`server &`) has ended, so what it left running stays, as from a terminal.
- Standard input is closed, so a command waiting for input fails instead of hanging.
- `.out` and `.err` keep the last 1 MB of each stream; past that the first line says how much was cut. The console
  shows the first 1000 lines of a command, each cut at 4 KB, and says how many it left out.

A script could already run any JVM code, so this adds convenience, not power. There is no confirmation and no list of
allowed commands, and a template loaded from someone else runs its commands with your rights: read it before you press
Run. Only script code reaches `shell`; no route of a served model does. Text that came from outside, such as what was
sent to `/teach`, goes to `shellScript` as an argument, never into the command line of `shell`, where the shell would
read it as a command.

## Templates in the background

A saved template (see [Templates](sandbox.md#templates)) can run beside the script that starts it, and the two can talk:
`coroutineTemplate` starts it and the script goes on, the template reads with `input()` and answers with `output(text)`.
The two halves are starters in the Templates pane: **Background templates: coroutineTemplate**, the caller, which you
load and Run, and **Background template: a reader**, the template it starts.

```kotlin
// Starts the saved template "Background template: a reader" beside this script and talks to it: send gives it a
// line for its input(), receive waits for what its output(...) answers. Meanwhile Models > Sessions lists the run
// under Scripts with what it holds, and the Templates pane's fold says it is running.
val job = coroutineTemplate("Background template: a reader")
job.send("turn the lights on")
log("the reader answered ${job.receive()}")
job.send("what is the weather")
log("the reader answered ${job.receive()}")
templateRuns.print()                                 // every run in the background, this one among them

// No more input is coming: the reader's input() turns null, its loop ends and the run ends with it.
job.closeInput()
log("the reader ended: ${job.await()}")              // finished, or failed: and the reason

// When the run ends it prints its own resources line, as a run of main.kts does: memory, cpu, sessions. The dummy
// session it opened stays open, as with any script; Models > Sessions shows it until it is closed or terminated.
```

```kotlin
// The template half of a pair. The starter "Background templates: coroutineTemplate" starts this one in the
// background, feeds it lines with send and reads its answers with receive. Run by itself it reads the console:
// each line typed there is one input(), and eof ends it.
val model = initMod("dummy")
while (true) {
    val text = input() ?: break          // waits for the caller's next line; null once its input is closed
    output(model.predict(text))          // the label goes to the caller's receive(), and to the console
}
log("input is closed, the reader ends")
```

`coroutineTemplate("name", input = listOf("a", "b"))` starts one with those lines already waiting for its `input()`.

### A script that waits to be called

In main.kts `input()` waits for a line typed in the console, so a script can load nothing until it is asked. Give it a
main entry point:

```kotlin
fun work() = ortClosable("work") {
    val model = initMod("dummy")
    log(model.predict("hello"))
}

fun main() {
    log("Type go in the console to start")
    while (true) {
        when (input()?.trim() ?: break) {
            "go" -> work()
            else -> log("go starts it")
        }
    }
}

main()
```

It works as a Run window does: while the script waits, every line typed is its input, and the console's commands wait
until it reads no more. `eof` gives it the null of a closed input, and Stop ends it where it waits. A line typed while it
isn't waiting isn't kept: it counts as a console command or, like `log("hi")`, as a line of script that runs in the
background, and the console says when it is neither.

An exported bundle has no console, so there `input()` reads standard input: type at `./run.sh`, or pipe lines in with
`echo go | ./run.sh`. When standard input ends, `input()` is null. It works the same with `--template`.

| Call | What it does |
|---|---|
| `coroutineTemplate("name")` | Starts the template and returns at once. The name is matched in any case, or give its id; a name no template has is an error that lists the templates |
| `coroutineTemplate("name", input = listOf("a", "b"))` | The same, with those lines waiting for its `input()` |
| `job.send("text")` | Gives the template a line of input. It never waits: what is sent stays until the template reads it. False when the line can't reach it, input being closed or the run ended |
| `job.receive()` | The template's next output, waiting for it; null once it has ended and everything it said was read |
| `job.receive(5000)` | The same, waiting five seconds at most; null then too, and `job.state` tells the two apart |
| `job.closeInput()` | No more input is coming: the template's `input()` is null once it has read what was sent |
| `job.await()` | Waits for the end and returns how it ended |
| `job.cancel()` | Ends the run (below) |
| `job.state` | How it stands: `running`, `finished`, `cancelled`, or `failed: ` and the reason. `.running` and `.failed` say so as true or false, `.reason` is the reason |
| `job.name`, `job.id`, `job.startedAt`, `job.endedAt` | Which run it is, and when it began and ended (`endedAt` is null while it runs) |
| `template("name")` | A template to call like a function: see [Delegating to a template](#delegating-to-a-template) |
| `templateRuns.print()` | An internal line for each run and for the last three that ended (`#3 Reader running 4s`); the lines are returned too |
| `templateRuns.cancelAll()` | Cancels every run and returns how many there were |

A template is an ordinary script with the whole API, and what it opens stays open as with any script. Its console
lines start with its name, and `output(text)` logs the text as well as handing it over. Run in the foreground (loaded
into the editor, say) `input()` returns null at once, with one line saying nothing feeds it, and `output` only logs,
so a template can be tried with **Run**.

**How long a run lives.** A run of `coroutineTemplate` outlives a script that ends by itself, until it ends, is
cancelled, or the app closes. **Stop** on the script ends it too, with whatever it started in turn. At most eight run at
once, fewer on a small heap; a ninth is an error that says so. A start also needs the memory for it, or it is refused
with what is short and what to close ([The sandbox](sandbox.md#templates-in-the-background)). The fold in the Templates pane and the **Sessions** tab of the Models page list them
([The sandbox](sandbox.md#templates-in-the-background)).

**Output nobody reads.** A run never waits at `output`. The newest 1024 answers are kept for `receive()`, and the
console says it once when older ones are dropped, so a run started with **Run in background** neither stops nor fills
the memory.

**Cancelling.** `job.cancel()`, **Stop** in the Templates pane and **Terminate** on the Sessions tab are the same, and
each reaches what the run started, however deep, also through a run that has ended. A
blocked `input()` or a sleep ends at once. A busy script ends at its next `log`, `info`, `error`, `data`, `input` or
`output`, each of which throws `CancellationException` once the run is cancelled; until then the pane reads *Stopping*
and the run still counts as one of the eight. A cancel during its compile takes effect when the compile ends: every
compile in the app reads the classpath through one shared file system, and interrupting one breaks the compiles beside
it, `main.kts` included.

**When it fails.** The run ends `failed`, and the script that started it goes on. The reason names the template's own
file and line (`failed: reader.kts:3: java.lang.IllegalStateException: boom`), and the console has a line for each
problem. `job.await()` returns that state rather than throwing.

**The env.** A run stays in the env that is current when it starts, and changes nothing of it. A template with
`environment(SettingsEnv("other"))`, or with an `implementation("...")` this env doesn't have, is refused:
`coroutineTemplate` throws an error that says what to do instead. Another env's class loader could be had, but the API
a script calls looks up the current env (`group()`, jars), so the template would run half in one env and half in the
other. A template that names no env runs in the current one, and an import that only happens as the template runs is
an error line, the env left as it is.

**Each run has an engine of its own**, made for the run and dropped when it ends, so a run begins with a compile of a
few hundred milliseconds. This is what lets `main.kts` wait in `receive()` while the template runs: an eval holds the
lock of the engine state in its bindings to its end, so a template evaluated on the bindings of `main.kts` would wait
for `main.kts`, and `main.kts` for it.

### Delegating to a template

`template("name")` gives you a saved template to call like a function, for when all you want from it is an answer:

```kotlin
// Delegation: a saved template called like a function. The first call starts "Background template: a reader", gives
// it the line and returns what its output(...) answers; the next calls go to the same run.
val reader by template("Background template: a reader")
log("the reader answered ${reader("turn the lights on")}")
log("the reader answered ${reader("what is the weather")}")
log("run #${reader.run.id} is ${reader.run.state}")      // reader.run is the run behind it

// A template that fails makes the call throw, with its name and its error, so a failure never passes for an answer.
// close ends its input and waits. A script that doesn't is closed for when it ends, and Stop ends the reader with it.
reader.close()
log("the reader ended: ${reader.run.state}")
```

| Call | What it does |
|---|---|
| `val reader by template("name")` | Nothing starts yet. `val reader = template("name")` is the same |
| `reader("text")` | The first call starts the template. Each call gives it the line and returns its next `output(...)`, on the same run |
| `reader.close()` | No more input is coming, and it waits for the end |
| `reader.run` | The run behind it, a `TemplateRun` as `coroutineTemplate` returns; asking for it starts the template |

**When it fails.** A template that failed makes the call throw, with its name and its error
(`Template 'reader' failed: reader.kts:3: java.lang.IllegalStateException: boom`). One that ended without answering, or
was cancelled, throws as well, so the call never returns null. A start that was refused, for memory say, throws
the reason, and the next call tries again.

**When the script ends.** A script that ends, however it ends, has its `template` values closed for it: their input
closes, and a run still going ten seconds later is cancelled. A template's own `template` values are closed when it
ends in the same way. `coroutineTemplate` is the one that carries on.

**Nesting.** A template can start templates. One that would start itself, directly or through another, is refused with
a line naming the way round (`Not started: template 'a' would be calling itself: a > b > a`), and they nest four deep
at most. **Stop** on a script cancels everything below it.

## Serving a model

Open the Models page, expand a model, set host, port, path and methods, save, and switch **Listen for incoming data**
on. Then:

```bash
curl -X POST http://127.0.0.1:8765/models/intents/predict -d '{"text":"turn the lights on"}'
curl 'http://127.0.0.1:8765/models/intents/predict?text=turn+the+lights+on'
```

Every request is appended to `AMP/models/intents/<name>/incoming.jsonl` and counted on the Models page. Models that were
listening when the app closed start again on the next launch.
