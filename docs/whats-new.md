# What's new

## Next: projects of templates, and one name per command

- **Projects group templates.** `amie template new <name> [--project p]` makes a template and opens it in `$VISUAL` or
  `$EDITOR`. A project (`amie new p [templates]`) keeps an ordered list of templates in `AMP/projects/p/templates.txt`,
  and `amie run p` runs them one after another in the env `p`, then the project's `main.kts`, stopping at the first that
  fails. `amie project list`, `show`, `add` (`--at n` to place or move one), `remove`, `run` and `edit`. See
  [Templates grouped into a project](learn.md#projects).
- **Every command is singular.** `amie model`, `group`, `package`, `env`, `template`, `project`, `session` and
  `example`; at the prompt `:session`. The plural names are gone.
- **`amie model metric <name>`** prints the model's `metrics.json`, one entry per build; `--last` the latest build only, `--file` where it is.
- **`:docs` opens the book**, `amie docs` from the shell; `:learn` and `amie learn` are gone.
- **`log(source("x.csv"))` lays the file out as pandas' `df.info()` does**: each column's non-null count, dtype and role, the labels with their rows. `logInfo(source(...))` and `source(...).info()` do the same, and neither needs `!!`.
- **New: `quietly { }`.** Every model answer writes a console line, which floods the output when a script asks a model thousands of times to measure it. `quietly { }` runs its block without those lines, the script's own `log` lines still show, and the session logs still record every answer. See [Scope functions](learn.md#scope-functions).
- **Metrics a script asks for now print.** `validateMetrics`, `metricsChart`, `confusionMatrix`, `calibrationChart`, `mistakes`, `suspects`, `metricsHistory`, `trainingCurves`, `modelStatus()` and `modelChart()` print at the normal level, so they show without `-v`. The report a build prints on its own stays hidden.
- **New example: [examples/google-assistant](../examples/google-assistant/README.md).** One template: a model built with `modelBuilder` routes requests to google-calendar, google-tasks, google-drive, gmail-reader, google-translate and the browser, its metrics print, then each request is answered by its package.
- **`sourceFile()` finds and reads a file as `source()` does**: as given, in `AMP/`, then in `AMP/pluginDir/`, saying where it looked when it can't; a quoted field keeps its commas, where before it was split at them.

## 1.1.0: encoders and decoders, projects, and the templates' three verbs

- **Encoder and decoder pairs.** A sequence-to-sequence export (T5, BART, Marian translation, Whisper) is two ONNX
  files, `encoder_model.onnx` with `decoder_model.onnx`, which no single run answers. `amie hf import` now takes the pair
  whole: it lists every encoder and decoder of the folder, the full variant marked, to pick with the arrow keys (space
  takes or leaves the file under the cursor, Enter imports, `n` leaves; `--take` names them for a pipe or a script), with
  the config, tokenizer and preprocessor files alongside. Any repo with several files that run here is picked the same
  way, the file `hf info` says an import takes already marked. `hf info` marks the pair's files. `initMod("<name>")` opens both sessions as `.encoder` and
  `.decoder` (`.decoderWithPast` when exported), with `.tokens(text)`, `.text(ids)`, `.startToken` and `.endToken` from
  the files beside them. See [Encoder and decoder pairs](sandbox-scripting.md#encoder-and-decoder-pairs).
- **The encoder's input made for you.** `m.encode(text)` runs the tokenizer and the encoder; `m.encode(file)` reads the
  model's `preprocessor_config.json` and turns a wav or aiff into Whisper's log-mel spectrogram, or an image into
  normalized pixels, then runs the encoder. `m.decoderFeeds(encoded, ids)` fills the decoder's inputs by name, and
  `m.startTokens(encoded, "de", "translate")` gives the decoder's first ids from the config's forced tokens, each
  replaceable by a name the config knows, Whisper's language detected when none is given. A script's own part is the
  twelve-line greedy loop. Whisper tiny transcribes a spoken wav exactly, and a quantized T5 translates.
- **`amie model remove <name>`.** Removes a model's folder whatever its kind, an intent, text, speech or imported
  model, closing a session of it first. Paths are printed as a shell takes them, `Application\ Support`.
- **`amie model info` on an imported model** lists its files by role (the encoder, the decoders, or the model and the
  files beside it), the config and tokenizer files with their sizes, the start and end tokens, its labels, and how
  `initMod` opens it.
- **`ortRequired` in place of `ortTimed`.** `ortRequired("name") { }` keeps what its block opens while anything else is
  in session, a model, package, group, template or background job, and frees it within a couple of seconds once
  nothing else is open; its block opens it again when next run, as lazily as `ortStatic`. For what other models need
  beside them. `ortTimed` is gone from scripts: a saved script that calls it no longer compiles. The starter **Scope
  functions** is brought up to date in every AMP folder where it was never saved over.
- **Help that reads.** `amie <command> --help` puts every action on its own line with its description wrapped beneath
  and a blank line between them, as wide as the terminal; `:help` wraps the same way.
- **Projects.** `amie new <name>` makes `AMP/projects/<name>/main.kts` to start from, in an env of the same name;
  `amie run <name>` runs it.
- **Export without the jars.** `amie template export <name> --out <folder> --no-jars` leaves the env's Maven jars and
  the package jars out of the bundle and writes `require.txt`, one line a file with the path to put it at under the
  bundle; the README has the same table. `run.jar --check` says which are still missing. A run with a Maven jar missing
  fetches it from Maven Central when it can reach it, and with a package jar missing stops and names it. Compiled
  bundles from `amie` now carry the app's own classes: before this the runner was missing from `run.jar`.
- **Templates: save, edit, delete.** `amie template save <name> <file>` keeps a script file, a project or a starter as a
  template (a name taken is saved again, its id kept); `amie template edit <name>` opens its file in `$VISUAL` or
  `$EDITOR` and takes the edit, or prints the path when neither is set; `amie template delete <name>` removes it.
- **Piper voices import ready to speak.** A Piper voice from the Hub (`rhasspy/piper-voices`, a path to one voice) lands
  in `AMP/models/speech`, laid out as sherpa-onnx wants it, its ONNX metadata added when the export lacks it. A path
  that names no voice is refused instead of another voice being taken in its place.
- **Installing on a machine without Java.** `install.sh` finds a JDK 21 or installs one: Homebrew's on a Mac, apt's on
  Debian and Ubuntu, else a Temurin build from Adoptium into `~/.local/lib/jdk-21`; `--no-jdk` leaves that out.
- **Fixes at the prompt.** `:help` lists every command line of amie, each as `amie <command> --help` when it has more
  than one action, with no example line repeated. The line editor no longer expands `!!` as shell history, which broke
  Kotlin's non-null assertion in a pasted block. A name `initMod` can't find says every folder it looked in and names
  `amie model list` and `amie hf import`. A tokenizer with an empty "Precompiled" normalizer (Marian's) no longer
  aborts the process. `amie model list` wraps a model's labels instead of running off the row, and a pair shows none.

## Less said twice

- **System usage on the Sessions tab.** The line over the sessions says what the process holds of the machine's RAM
  and how much of that is the encoders, the heap, the native memory, the cpu, the threads and what the sessions weigh,
  and "near the limit" when it is. The memory strip at the foot of the window is gone, and so is Memory in the rail;
  the strip shows only while something runs.
- **TensorFlow on the Hub.** The Hugging Face search for TensorFlow asks for the `tf-keras` tag, where the SavedModels
  are, as well as `tf`, whose repos are nearly all Keras `.h5` weights that don't run here; an empty answer says so.
- **A package's manual.** The package panel opens with **Manual**: the description of the jar's metadata, which says
  what the package does and the texts it answers, with its notes. **Copy script** beside Opens with puts a three-line
  script on the clipboard: open the package, call it with its first test's text, close it.
- **Paths only in Settings.** The package panel's Where, Page, Download and File lines are gone, and so is the config
  folder's path; Settings has a **Package folders** group with every jar's path and config folder, Copy path, Reveal
  jar and Reveal config. The Model folders rows say the encoder's path; About names the encoder. A group's panel no
  longer says where groups.json is, and the New model form shows the chosen file's name.
- **Created moved to Settings.** When a model was made is on its row under Settings, Model folders, with its kind and
  its folder; the About tab's Details no longer say it.
- **Three labels, and how many more.** About's Labels line names the three labels with the most training rows, "chat,
  weather, list_files and 7 more"; the rows per label stay on the Data tab, which is also the only place that says
  what the preprocessor did to the rows.
- **Templates and Console in the header.** The Sandbox's two toggles sit at the left of the header strip, before the +
  for a new template; the strip no longer names main.kts, and the Models page's New model is a + with the words in its
  tooltip.

## Hugging Face tasks, one New model, and two hues

- **Every kind of model the Hub has.** The Hugging Face search has a task menu beside its format chips: **Text to speech**,
  **Speech recognition**, **Text classification**, **Token classification**, **Feature extraction**, **Text generation**, **Audio
  classification** and **Image classification**, or **Any task**. A task alone lists the Hub's most downloaded models of it.
  A looked-up repo says its task, model type and the labels of its `config.json` before anything is downloaded, and the
  labels and the task are written into the imported model's `source.json`, shown on its row and panel, and found by the
  filter. A speech model's voices, tokens, lexicon and phoneme data, and a Piper voice's `.onnx.json`, come along with it.
  In a script, `huggingFace.search("", task = "text-to-speech")` (or `tts`, `ner`, `embeddings`…). See [From Hugging
  Face](models-page.md#from-hugging-face).
- **One New model.** The Models page's **New model** opens one form whose first line says where the model comes from, **A
  csv** or **Hugging Face**; the cloud chip is gone, and the list, the rule and the panel sit closer. See [New
  model](models-page.md#new-model).
- **Blue and green.** The app is no longer gray alone: a dark blue fills the one primary button of a place, a lighter blue
  draws links, the focus ring, a focused field, the open tab's bar, a selected row's edge and the line of a build or a
  download, and a faint blue washes what is selected; the muted green marks what runs, succeeded or is done, the bar of a
  notice included. Accents, never floods; every word keeps 4.5:1 and every mark 3:1 (`ContrastTest`). See
  [Colour](components.md#colour).
## The error terminal's lines go to handleBy

- **Pull the console's error lines, and hand them to `handleBy`.** `errorLog()` is the error terminal's log: the last error lines the console showed, oldest first, a run's failures, what a command printed on stderr and why it failed, the app's own. `handleBy()` hands the last ten of them to the local text model with main.kts and the env, so after a failed command or run one call asks about what the console showed in red; `handleBy(lines)` takes any list, and `shell("x").patchBy(::handleBy)` asks about what the one command said the moment it fails. Clear, ⌘K and a new run empty it. See [Scripting API](sandbox-scripting.md#the-api).

## Models from Hugging Face

- **Add from Hugging Face.** The cloud chip after **New model** on the Models page searches the Hub, in every format `initMod` opens or one of them (ONNX, TorchScript, TensorFlow, TFLite), and also takes a repo id or a huggingface.co address. It lists the repo's runnable files by format with their sizes and says why the weights-only files won't run. It picks a plain `model.onnx` with its config and tokenizer files and downloads the pick into `AMP/models/imported/<name>`, with progress and Cancel. Leaving the page doesn't stop the download. The files only take their names once their sizes check, so a closed app never leaves a half file, and the next download resumes. See [From Hugging Face](models-page.md#from-hugging-face).
- **Imported models are listed.** They have their own fold, **Imported**, showing each model's format, source repo and size, with a Delete that asks first.
- **`huggingFace("owner/name")` in a script** downloads the model the first time and returns its local name, so `initMod(huggingFace("owner/name"))` works; `huggingFace.search("bert", format = "onnx")` returns repo ids. A gated or private repo reads `HF_TOKEN` from the environment, and the token is never logged or stored. See [Scripting API](sandbox-scripting.md#the-api).
- **Model folders in one place.** The full path of a model's folder is gone from its page (the About tab's block is **Details** now) and from an imported model's panel; Settings lists every model's folder in **Model folders**, by kind, with Copy path and Reveal
- **A language model answers in text.** Logits like GPT-2's `[1, 1, 50257]` were shown as "hidden state [1, 1, 50257]"; they are a score for every token at every place, and an imported language model now decodes them: one that continues text gives the text it goes on with (greedy, up to 24 tokens) and its 5 likeliest next tokens, one that fills a mask the likeliest tokens at `[MASK]`

## PyTorch and TensorFlow models

- **`initMod` opens models made elsewhere.** `initMod("<name>")` opens `AMP/models/imported/<name>`, a Hugging Face repo or a folder copied in, and `initMod("/path/model.pt")` or the new `importModel(path)` a model by its path: ONNX on the bundled runtime, TorchScript (`.pt`, `.pth`, `.torchscript`) on PyTorch, a TensorFlow SavedModel folder on TensorFlow. A folder of several formats opens its ONNX, else the one its `source.json` names, else the first that runs here, and says which. See [Models from outside](sandbox-scripting.md#models-from-outside).
- **Tensors in and out.** A model from a file says what it takes and gives (`.inputs`, `.outputs`: name, shape, type) and runs on numbers: `.run(floatArrayOf(…))`, `.run(longArrayOf(…))`, `.run(mapOf("input_ids" to ids))`. Text still goes in when a `tokenizer.json` is next to it, and the labels come from its `config.json`. `importModelOnnx` models answer the same calls.
- **Natives on first use.** PyTorch and TensorFlow come as DJL engines whose jars are small; their native libraries (about 40 MB for PyTorch on an Apple Silicon Mac, 130 to 150 MB for TensorFlow) are downloaded into `~/.djl.ai` the first time one of their models is opened, which the console says once. Offline, the first open says what it wanted.
- **Refused, with what would work.** TFLite (DJL has no TFLite engine since 0.28), frozen TensorFlow graphs, PyTorch weights saved with `torch.save`, and weights alone (safetensors, `pytorch_model.bin`, gguf, h5) are refused, naming every file and the way to ONNX or TorchScript. PyTorch has no natives for Intel Macs, and TensorFlow 2 is in the lightweight build only, since the full build's TensorFlow 1.15 clashes with it.
- **Listed and closed like the others.** Each one is a row of the Sessions tab with Terminate, closed with the `ortClosable` block around it, and `hasModel("name")` is true for an imported model. `importModelTensorflow` is `importModel` now, and the old TensorFlow 1.15 and TFLite loaders, which did nothing, are gone.
- **An Apple Silicon Mac builds the lightweight app.** TensorFlow 1.15 has no natives for it, and its classes kept TensorFlow 2 out, so a Mac like that now builds without it, as a Raspberry Pi does: about 700 MB less, and `initMod` opens TensorFlow SavedModels there. `-Pamie.lightweight=false` builds the full one

## Quicker builds, where a turn's time goes, and setup in AMP/setup

- **The editor marks wrong calls as you type.** A moment after typing stops, a call to the script API with too few or too many arguments, a named argument the function hasn't, a literal of the wrong type (`initMod(12)`) or an unknown member (`amie.lgo("x")`) gets a red wavy line and a dot by its line number; the pointer on it says what is wrong. See [The editor](sandbox.md#the-editor).
- **`handleBy("error")` asks the local text model, and the AI pane is gone.** `handleBy("Unresolved reference: dumy")` (or `handleBy(e)` for what was thrown, as in `handleBy({ e -> handleBy(e) }) { … }`) asks a text model of `AMP/models/text` what is wrong and how to fix it, with main.kts and the env in its prompt, prints the answer and returns it. It only answers: the script stays as it is. The sparkle on the rail, ⌘2 and the pane that wrote whole scripts went, as a small model's whole scripts were often wrong
- **The console remembers.** The lines of script typed at `>>>` share one interpreter: `val x = 12`, then `log(x)` prints 12, and a `fun`, `class` or `import` stays for the next line. A line that ends in a value prints it. A block can go over several lines: `for (x in 0..10)` waits at a `...` prompt until `{`, `log(x)` and `}` make it whole, then runs once. A blank line sends a block whose brackets are closed, Ctrl+C drops it (or stops a line that runs), and `reset` forgets the definitions. Lines run in the order they were typed.
- **`shell` prints at the internal level.** `shell("ls -l")` shows the command and what it prints as internal lines, its stderr as errors, so a script's own info lines aren't buried under a listing. Turn *Internal* on to see them; `.out`, `.lines` and `.err` keep everything.
- **The editor's list offers each name once.** `info`, `log`, `error`, `data`, `output`, `import`, `initMod`, `sessionMod` and the rest with overloads were listed once per overload (`info(message: String)` and `info(message: Any?)`); each is one row now, the form a script writes most: `initMod("")`, `info("")`, `import("")`.
- **The sandbox's bar reads as IntelliJ's.** The env is a flat run-configuration widget (a small box, its name, a chevron), and Run, Stop and export are thin outlined icons, a green triangle, a red square and an arrow, all of one size.
- **Gray, not blue, as IntelliJ's dark chrome.** Every background, border and text gray is neutral now (the editor #0E0E0E, the panels and bars #151515, the borders #242424, the text #BFBFBF), at the same steps of lightness, so every word still reaches 4.5:1. The primary button (New model, Open session, Validate, Build) is filled light gray with dark words in place of blue, and while disabled is a plain gray chip. A selected model is a gray step with a brighter edge, the chosen completion a gray step, the open tab's underline and the keyboard's focus ring are light gray, the editor's selection is a gray band as the IDE's inactive selection, and the Metrics charts and the confusion matrix are grays told apart by lightness and by each series' mark. Text selected in any field is the same gray. The code's colours are as they were, and the flags named slate and blue keep their hues.
- **A line between the line numbers and the code.** The editor's gutter ends in a thin gray line down its full height, as in IntelliJ, so the numbers no longer run into the text.
- **Darker still.** Every surface is a step darker (the editor from #131417 to #0D0E10, the panels and bars from #1B1C20 to #141518, the borders from #2B2D32 to #222428), the text a little dimmer and the editor's colours less bright; every word still reaches 4.5:1.
- **Full screen fills the screen on a Mac.** The window drew its own frame, and macOS moves such a window into a full screen Space without making it bigger, so it sat at its old size in black. On a Mac the window is now the system's, its title bar see-through under the header: the red, yellow and green buttons are at the header's left, the green one or **View → Enter Full Screen** (⌃⌘F) gives full screen at the screen's size, and the window resizes from any edge as any Mac window does. Windows and Linux keep the header's − □ × (F11 for full screen). The app also starts with `--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED`, without which Java 21 refused the request.
- **One bar at the top, the page's own.** As in an IDE, the window's bar is the page's toolbar: the sandbox's main.kts, **+**, env, Run and export; the Models page's tabs; Settings' back arrow and its sections as tabs, which scroll to them. The row the window had over each page is gone, 31dp back on every page, and there is no title.
- **Fewer words.** A template is one line in the Templates pane, its about line and tags in the name's tooltip, Load an icon; Running in the background shows only with a run in it. Settings rows are a name and a control, the rest in their tooltips. The Metrics charts have short titles (Per label, Confusion matrix, Training by epoch, Calibration…) and no legend in sentences, which the Guide tab has; the Try line says *Ask it something*; the sidebar's tips are the pages' names. The About tab's Reveal folder stands at the end of the Where line again, however long the path.
- **The console reads from the top down, as a terminal does.** The `>>>` prompt is on the line after the output, not in a bar at the foot of the pane: a fresh console is the greeting and the prompt at the top, each line pushes the prompt down, and once the pane is full it scrolls with the prompt at its foot. A click under the prompt puts the cursor in it. The prompt has no hint in it, only a blinking block cursor (an outline while the console hasn't the keyboard); the hint is the tooltip of `>>>`, and the send arrow is gone: Enter sends.
- **The console greets you as Python does.** `Amie pilot 1.0.0 (default, Nov 06 2026, 02:23:55) [Kotlin 2.1.0, JDK 21.0.11] on darwin`, then `Type "help", "sessions", "terminate all" or log("hi") for more information.`; the input line's prompt is `>>>`. Clear and a run leave it there.
- **Run details, off by default.** The console strip shows only its level toggles, Find, Details and Clear: no `Finished in 0s`, no memory line under it. Settings → Editor → Console → **Run details** brings them back, over the editor too while the console is hidden.
- **+ beside main.kts.** A new template in one click: the editor is emptied (the script in it kept under Previous scripts), the Templates window opens on the save form with the cursor in Name.
- **A double click on Load no longer loads twice.** The second click could land on another row, often a Previous script, which put back the script just replaced, so a loaded template seemed to jump back. A Load within a second of another is ignored.
- **The console shows only what the script logs.** `Running pluginDir/main.kts`, `Finished in 0s` and the `Resources:` line are at the internal level now, off at start: the strip over the console already says how the run went and what it used. A script that logs nothing leaves the console closed. A session the script left open is still named, at info.
- **`log` takes any value.** `log(pkg.run("scan"))` was a type mismatch, since a package answers a list; `log`, `info`, `error`, `data` and `output` now take any value, a list or an array one element a line, a map `key: value`.
- **New template.** The Templates window has a **New template** button at the top: it starts a new, empty script (the one in the editor is kept under Previous scripts) and opens the save form with the cursor in Name. It used to keep the script in the editor, which made copies of whatever was open.
- **Talk to Amie exports.** Its Whisper and voice were taken for intent models and stopped the export; they now go along with `speech.jar`, and a script that opens only speech or text models carries no encoder.
- **Export from a script.** `exportJvm("main.kts")` writes the same bundle as **Export…**; `planOnly = true` only says what would go in. See [Scripting API](sandbox-scripting.md#the-api).
- **`ortAsync` and `handleBy`.** `ortAsync("warm") { }` is `ortClosable` on a thread of its own, returning a job that ends with the script; `handleBy({ e -> "" }) { }` gives the handler's value when the block throws, never for a stop or a cancel. See [Scripting API](sandbox-scripting.md#the-api). A running block is a row of the Sessions tab, *Background block*, with Terminate.
- **The export panel after writing.** Show it stands alone: the export button, which had nothing left to write into that folder, no longer squeezes in beside it.
- **Words level with their icons, badges that fit their words.** Text that named no line height took Material's 24sp, and Compose Desktop put that line's room nearly all above an 11-13sp word, so words sat low: under the icon of a button or a list row, at the bottom of a badge. Such text now has its font's own line. A list row's icon sits on its name's line, the flags and the meta under the name. The UI goldens are now taken in the app's theme, as the window shows it; before, they never had the 24sp line, so they never saw this.

- **Builds embed about five times quicker.** The encoder takes the rows 32 at a time, each batch rows of the same number of tokens, so 1,000 rows take about 2.1 s instead of 11 on a Mac and the benchmark ends in 17 s instead of 26. The vectors are the same to the bit as one row at a time, so a model trains exactly as before; rows of different lengths are never mixed, because padding them moved the vectors by a few millionths.
- **The encoder stops copying what nobody reads.** Every answer used to copy its whole hidden state into a list that nothing read.
- **`setup.sh` is in `AMP/setup/`.** It sets up the whole AMP folder, not only the voice, so it moved out of `voice/`: run `./AMP/setup/setup.sh` from the repository.
- **Talk to Amie says where a turn's time went.** `Took … ms: hearing …, intent …, Llama …, voice …, playing …` in the console after each turn, so a slow answer shows which part was slow. See [What happens to what you say](voice.md#what-happens-to-what-you-say).

## The app says what it waits for

- **Nothing loads blank any more.** A list that is still reading says "Reading the models…" with three soft dots instead of "No models yet"; a button whose work runs keeps its word, "Opening…", with the dots alive after it; the console's last line shows them while a text model loads or the builder embeds and fits; the thin line under the console and the build line of the Models list are the same line. Nothing shows for the first 150 ms, so a quick read never flickers. One component draws all of it, `Loading.kt`, listed in [the components](components.md#loading-loadingkt).
- **Every model is under `AMP/models/`.** `bert/` as before, `intents/` for the trained intent models (was `modules/`), `text/` for Llama and Qwen (was `textModels/`), `speech/` for Whisper and the voices (was `pluginDir/config/speech/models/`). The app moves an older install there when it starts, one rename a folder, nothing copied, nothing deleted; with both an old and a new folder there it reads the new one and says in the console which to tidy. `speech.properties` is pointed at the new folder. See [The AMP folder](amp-folder.md#the-models-folder).

## Darker, and a console like an IDE's

- **Easier on the eyes.** Every surface is darker (the editor went from #1E1F22 to #131417, the panels from #2B2D30 to #1B1C20), no words are pure white any more, and the accents are calmer. Sign-in's white button is the app's blue, and right-click menus are dark on every page.
- **The console works like an IDE's terminal.** ⌘F finds in the output, ⌘K clears, the arrow keys bring back what you typed, a copy over several lines keeps them apart, a `main.kts:12` or a web address anywhere on a line is a link, and ANSI colours are drawn. See [the output console](sandbox.md#the-output-console).

## Talk to Amie, on models the app manages

- **Models answer in the background.** `val job = model.inBackground("text")` returns at once, and `job.await()`, `job.done` and `job.cancel()` follow it. Only so many run at once, the rest wait their turn on the Sessions tab, and when memory runs short the app closes idle `ortTimed` and `ortLimited` sessions first.
- **Text models run inside the app.** ONNX Runtime GenAI exports in `AMP/models/text/<name>` open with `initMod("name")`, on the app's own ONNX Runtime (1.31.1 now). They are in Still open, count in a run's memory, end with Terminate and with the `ortClosable` around them.
- **Stop stops.** A call of a package runs on a thread of its own, so Stop ends a script at once even when the package reads a device for seconds, and a loop can't go round again after it.
- **Scripts can wait to be called.** `input()` in main.kts now reads what is typed in the console, and standard input in an exported bundle, so a script with a `main()` loads nothing until you ask. `eof` ends its input and Stop ends the wait. Exports now carry text models.
- **Amie is less dumb.** It listens until you stop talking, understands many more ways of asking, and when unsure lets Llama read your words, so it picks out sites, files and days, and says when it didn't catch you instead of guessing. Web questions are answered from Wikipedia and the news, and `files.jar` opens, creates, reads and finds files.
- **The window keeps up.** It is no longer see-through, which it never needed, so the Mac has less to do for each frame. The face draws on its own thread, and only copies its finished picture to the window. If the window's thread still stalls, the log says where.
- **Stopping a model can't take the app down.** Closing a session or pressing Stop while a model is running used to free memory the run was still using, which crashed the whole app. A stopped run now ends first, and its session closes after it.
- **Amie answers sooner.** Supertonic 3 is the voice now: it speaks about five times faster than Kokoro did, so a one-sentence reply starts in about 0.6 s instead of 2 s. Its model is under OpenRAIL-M. `speaker=` picks one of ten voices and `steps=` trades speed for quality; Kokoro and Piper still work with `use`.
- **Amie plays music.** `spotify.jar` plays, pauses, skips and says what is on in the Spotify app, so "play Daft Punk" and "what song is this" work by voice. Without keys a name opens as a search in Spotify; with a `clientId` and `clientSecret` of your own in its settings, it plays straight away.
- **Amie does more by voice.** Say "turn it up", "dark mode", "take a screenshot", "how much space is left", "close this tab" or "what are my newest files". It tells you how many emails are unread, and the face falls asleep when you stay quiet.
- **The jars do more.** Mail counts, shows and marks read. The browser reads news and lists or closes tabs. Mac sets dark mode, takes screenshots and reads disk space. Files appends, renames and moves. Memory lists what's recent. The face is happy, sad, surprised or asleep.
- **Speech models are models.** `initMod("whisper")` and `initMod("voice")` open them by name. They show on the Sessions tab under that name, and blocks and Terminate handle them like any other model. This needs the new `speech.jar`.
- **Ask a model what it can do.** `model.can("chat")`, `can("classify")`, `can("fineTune")`, `can("hear")` or `can("speak")` tell you before you call it.
- **The app lands in half the time.** The sandbox draws in about a second and freezes for much less on the way, on a start after the first. The editor's line numbers exist only near what's on screen, the app goes straight to the sandbox, and `./gradlew run` keeps a class archive in `build/`. Packaged apps don't get the archive yet.
- **Amie has a face.** `face.jar` shows an animated face over your windows that smiles, listens, thinks, loads and talks. A script sets the mood with `face.run("think")`.
- **Closing keeps it running.** Close the window while a script runs and it goes on in the background. The face in the menu bar opens the window again or quits.
- **Amie is a companion.** It greets you, remembers what you tell it, reads your email, drafts replies and sends them only after you say yes, opens the browser and apps, and handles volume, the calendar and reminders, through four new jars with no keys.
- **The console runs script.** Type `log("hi")` with nothing running and it runs in the background. It keeps going while you work or close the sandbox, and `terminate console` ends it.
- **The console reads like a Run window.** While a script waits at `input()`, what you type is its input, shown in green, and the hint says so. Every red line now starts with the exception's name, or with Error.
- **The editor follows the caret.** The arrow keys and typing move the page, so the caret never leaves the view.
- **The console has colours.** Who wrote a line, strings, paths, numbers, session ids, and words like done, failed and warning each stand out. The words themselves stay as they were.
- **`patchBy` handles a failed command.** `shell("x").patchBy { error -> ... }` sends what a failed command said to anything: a model, a voice, the console.
- **Talk to Amie runs without Python.** `speech.jar` hears with Whisper and speaks with Kokoro, a natural voice with 11 speakers, set up in the Packages tab. It can now also open a terminal, close all of them, edit a file, open one in vim and run `brew install`, and it says out loud why a command failed.
- **Talk to Amie** starts when you type `talk` in the console. It hears you, picks what to do with a BERT intent model, then gets the weather, lists or finds files, looks something up, or answers. See [voice.md](voice.md).

## Update of 1 November 2026

### The sandbox looks and works like an IDE

The app now uses the fonts and dark colours of the JetBrains IDEs: JetBrains Mono for code, Inter for everything else. The
editor paints Kotlin as the IDE does, with functions, properties, named arguments, and what `${}` holds in a string each in
their own colour. The script API is in italics.

The templates and the console are tool windows that stand side by side, opened from an icon rail or with ⌘1 and ⌘4. Nothing
closes one to show the other. Drag a window's header to the left, right or bottom edge to move it, and the line at its edge
to size it; both are kept. The console's Recent runs and Still open are tabs, and it ends in a one-line prompt. See
[The sandbox](sandbox.md).

Long titles are cut with an ellipsis and read whole in their tooltip, so they no longer push other labels away. A narrow
model header puts its buttons on their own line, and tabs that don't fit go to a second line.

### Templates end with the block that called them

A template called in an `ortClosable` block now ends when that block ends, and what it opened closes with it. Before, it
went on running after the block, and its session stayed open. This holds at any depth, so a template called by a template
ends too, and a delegate used again in a later block starts a new run.

```kotlin
ortClosable("one question") {
    val label by template("Function: label a text")
    log(label("turn the lights on"))
}
// The template has ended here, and its model session is closed
```

### Function templates

A new Functions group of starters are templates you call like functions. Each takes a line of input and answers with a
line: **Function: clean a text**, **Function: count words**, **Function: label a text** and **Function: label and
score**. Four more show how to combine them: **Function: clean, then label**, **Functions: a pipeline in ortClosable**,
**Functions: keep the model between calls** and **Functions: a command as a function**. An existing `AMP` folder gets
them once. See [Scope functions](sessions.md#scope-functions).

What landed in the update of 31 October 2026, and where to read more about each part.

## Scripts

### Scope functions

`ortClosable`, `ortStatic`, `ortTimed` and `ortLimited` take a block and decide how long what it opens stays open.

```kotlin
// Closed when the block ends, however it ends: a one-time use
val label = ortClosable("one time") {
    initMod("dummy").predict("turn the lights on")
}

// Stays open across runs, under a name you can free
val search = ortStatic("search") { initMod("dummy") }
log(search.predict("what's the weather"))

// Stays while it is used: freed by itself once nothing has asked it for 40 seconds
val recent = ortTimed("recent", 40) { initMod("dummy") }

// At most three package instances under the name, the oldest closed when a fourth is opened
val pool = ortLimited("pool", 3) { initMod("echo-1.0.jar") }

ortScopes.print().forEach { log(it) }     // what each scope holds
ortScopes.free("search")                  // closes what search holds
```

- `ortTimed(name, idleSeconds) { }` keeps what its block opens like `ortStatic`, and frees it once nothing has used it
  for that long: the block not run again, no session it holds asked. One daemon timer serves every timed scope, and the
  **Sessions** tab counts down on its rows, as in *timed recent, 40s left*.
- `ortLimited(name, max) { }` keeps at most `max` sessions under the name, freeing the oldest first when one more is
  opened; the tab says *limited pool, 2 of 3*.
- `ortForever` is `ortStatic` now. A saved script that calls it still runs, the console says the new name, and the
  editor no longer offers it.

The Models page, **Sessions** tab, says which scope holds a session, as in *static search*. The whole starter is
**Scope functions: ortClosable and ortStatic** in the sandbox's Templates pane; an existing `AMP` folder gets it once,
and one that has it under its old title gets it renamed and brought up to date, as long as it is still the starter.
See [Scope functions](sessions.md#scope-functions).

### sessionMod

`sessionMod("name")` is a model to ask and nothing more: a snapshot of the head it has now. It can't train or change the
model, leaves no trace in its log, and stays on its head when the model is trained again. It was called `sharedModel`
until now; a saved script that says so still runs, and the console says the new name.

```kotlin
val m = sessionMod("dummy")                  // an object
log(m.predict("turn the lights on"))

val same by sessionMod("dummy")              // the same thing, as a delegate

val label = sessionMod("dummy") {            // for one block, gone when the block ends
    predict("turn the lights on")
}
```

See [Ask without touching the model](sessions.md#ask-without-touching-the-model-sessionmod).

### Background templates

`coroutineTemplate` starts a saved template in the background while the script goes on. `main.kts` feeds it lines, the
template reads them with `input()` and answers with `output(text)`.

```kotlin
val job = coroutineTemplate("Background template: a reader")   // starts the saved template and goes on
job.send("turn the lights on")           // a line for its input()
log(job.receive())                       // its next output()
job.closeInput()                         // no more input is coming
log(job.await())                         // waits for the end
```

Each background run shows on the **Sessions** tab, keeps running after the run that started it, and says what it used
when it ends. The two halves are starters in the Templates pane: **Background templates: coroutineTemplate**, which
you load and Run, and **Background template: a reader**, the template it starts; an existing `AMP` folder gets them
once. See [Scripting API](sandbox-scripting.md#templates-in-the-background).

### Run several saved templates at once

**Run in background**, the play icon on each row of the Templates pane, starts a saved template beside the script in
the editor. The pane counts what runs, and each run has its live figures and **Stop**. Before a start, memory is
checked: too little and it is refused, saying what is short and what to close. See
[Templates in the background](sandbox.md#templates-in-the-background).

### Delegate to a template

`template("name")` calls a saved template like a function, on one run:

```kotlin
val reader by template("reader")
log(reader("turn the lights on"))         // starts it, sends the line, returns its next output()
log(reader("what is the weather"))        // the same run
reader.close()                            // closes its input and waits
```

A failed template makes the call throw. When the script ends its `template` values are closed for it. **Stop** on a
script, and `cancel()` or **Terminate** on a run, now end what it started, nested runs included; a template can't start
itself. See [Scripting API](sandbox-scripting.md#delegating-to-a-template).

### Shell commands

`shell` and `shellScript` run commands and script files on this computer, in your login shell.

```kotlin
val r = shell("du -sh ~/Documents")
log(r.out)
shellScript("~/bin/backup.sh", listOf("--dry-run"))
```

The output follows in the console as it arrives. A command that fails turns the console's error level on, so its reason
is in view. A command stops at its timeout, 120 seconds unless you give another, and when the app exits. Only a script
in the sandbox can run one: the HTTP endpoints have no way to. See [Scripting API](sandbox-scripting.md).

### Exporting a script, compiled

The export square on the strip over the editor (**⌘E**) makes a folder that runs the script with plain `java`, and by
default the script and its templates go in compiled to classes: no compiler in the bundle, no compile at start, Java 21
or newer. The pane lists every saved template to take along; `java -jar run.jar --template "name"` runs one, `--list`
says what there is, and templates called with `template("name")` or `coroutineTemplate` go along and call each other in
the bundle as they do here. A script that calls two templates comes to 20 MB, starts and ends in 0.3 s and peaks at
87 MB, where the old way took 189 MB, 4.3 s and 430 MB. The pane ends on the bundle's path, its size and the exact
`java -jar` line, with **Copy**. **Script** keeps the old way, the text with the compiler beside it. A script that
doesn't compile is refused with its errors and nothing is written. See [Exporting a script](sandbox.md#exporting-a-script).

### Resources for every run

Each run ends with what it used: memory and its peak, heap, CPU time and ONNX Runtime calls, then the sessions left open.
**Recent runs** shows a short form of the same line, and background templates print it too. A background template's line
doesn't count its own run, or the runs that hold it, among the sessions left open.

```text
Resources: memory 1.20 GB, peak 1.40 GB, 180.0 MB more than before · heap 340.0 MB of 4.00 GB · cpu 2.10 s · ort 12 runs in 640 ms · sessions 3 opened, 2 closed, 1 still open
Still open: web-ample #3 (static search)
```

See [The sandbox](sandbox.md).

The Models page, **Sessions** tab, has a **Scripts** section too: each script and template that ran, what it holds, its
answers, its CPU and what it allocated; while main.kts runs, what its meter reads so far is under it.

Every session's row has a resources line under it, in file sizes, since ONNX Runtime doesn't say what it holds: a
model session its head and its encoder (`head 9 KB · encoder 415.6 MB, shared by 3 · 12 ms a run · last used 3s ago`;
the encoder is named once with how many share it), a package its jar, a bare ONNX session its file, a group the sum of
its heads, and a background template what its meter reads right now (`memory 1.20 GB, peak 1.40 GB · heap 340.0 MB of
4.00 GB · cpu 2.10 s · ort 12 runs in 640 ms`). Over the rows, a totals line: what the process holds, how much of that
is the encoders, the heap against its limit, and how many sessions are open and what they weigh together
(`Process 1.85 GB, of which encoders 415.6 MB · heap 340.0 MB of 4.00 GB · 20 sessions weigh 436.0 MB`). See
[Running sessions](sessions.md#see-what-a-script-uses).

### Autocomplete for block functions

The editor offers every block function with its braces, as a script writes it, with the caret inside: `ortClosable {  }`,
`sessionMod("") {  }`. After `m.` on a model, and inside a `sessionMod` block, it offers that model's own functions.

### Modules that miss something are refused

`initMod` no longer opens a model or package that lacks something. It stops before anything opens and names what is
missing: an encoder file, a head, labels that don't match, or a package config still holding its template. The same
check backs the requirements list on the Models page. See [Running sessions](sessions.md).

## The app

- **Memory is watched while templates run, not only when they start**: a light watch (a thread that exists only while a template goes in the background, one look a second) stops the newest template when the free heap stays under 32 MB for three looks and a collection doesn't bring it back, one at a time and newest first, with a plain console line saying which and why. `main.kts` is never stopped for it; it gets a warning line. See [Background templates](sandbox.md#templates-in-the-background).
- **`main.kts`'s Resources line no longer lists its own delegates as still open**: the templates it called with `template(...)` are being closed at the moment the line is written, so it waits a moment for them and leaves them out; one started with `coroutineTemplate` goes on by design and is still listed (a background template's own line got the same fix earlier). See [Resources](sandbox.md#running).
- **A running script survives leaving the sandbox page**: going to Models or Settings used to end `main.kts` with `Stopped after`. The run now belongs to the app, held apart from the page as the console's width and panes are, so it goes on while you look at Packages and Sessions, its lines keep reaching the console, and coming back shows it still running with its live status and a working **Stop**. See [Running](sandbox.md#running).
- **What runs, in the strip at the foot of the window**: while `main.kts` or a template in the background goes, the strip's left end says how many (`2 running`). A click opens a small panel over it with a line for each: the name, its own figures (`holds 416.5 MB · allocated 878.3 MB · cpu 1.39 s`) and how long it has gone, and its own **Stop**. The cpu and the allocation are those of the thread its script runs on, and what it holds is the weight of the sessions it opened, an encoder shared with another run counted once, so a busy template and an idle one read differently. The Templates pane's rows show the same. With nothing running the strip is the one calm line it was, and its ✕ works as before. The figures are read off the UI thread. See [Running in the background](sandbox.md#templates-in-the-background).
- **Cloud save**: in the sandbox's Templates pane, under the save form, a logged-out person sees *Saved here only* and a quiet **Log in to save to the cloud**, which opens the log in page and comes back to the pane. Logged in, what you save (your templates, `settings.json` and `main.kts`, nothing else) is also kept in your account, a moment later from the background, and the pane says *Saved here and to the cloud*, or *Saved here. Not saved to the cloud:* with why. A failed send never touches the local save and is tried again on the next save and at the next login. At login, what the account has and this Mac doesn't comes down; where both changed, the newer stays in place and the other is kept under `AMP/pluginDir/backup`. Local saving works as before, logged in or not, and with the server down. See [Cloud save](sandbox.md#cloud-save).
- **Performance tests**: `org.example.project.perf` measures start-up, the Models page over 60 models, what sessions, shared models and background runs hold and give back, every page and the command palette opened and closed over and over, the console under 40000 printed lines (it keeps between 18000 and 20000 of the newest, the file keeps all and is rotated at 20 MB), the console's commands including `terminate all`, and the figures on a run's Resources line, each against a budget. See [Troubleshooting](troubleshooting.md#performance-tests).
- **Console** at its 20000-line cap drops its oldest 2000 lines at once, not one per new line: a script printing at the cap no longer makes the window compose every line again for each batch (1.2 s a batch of 100 lines became 0.2 s on average).
- **Appearance** follows VS Code's Dark Modern: near-black panels with a slightly lighter sidebar and header, thin gray
  borders in place of filled blocks, one blue for the primary action, and red only on what ends or removes something.
  Buttons have 4px corners and two heights; the sidebar's items carry their names.
- **Layout polish**: Settings is built like VS Code's, a title over a card of rows with the controls at the right, from
  the same buttons and toggles as the other pages. Every confirmation (destroy a model, delete a template or a file,
  restore a backup) and the error dialog share one box: 480 dp wide, a title, one muted line, and two 28 dp buttons at
  the right, the one that changes nothing first and the red one last. A template's name is cut with an ellipsis and its
  tags show only when they fit whole, so **Load** is never over them; the export of a template keeps its title in place
  while the page scrolls, and a package's flags are faint outlines with the same corners as the rest.
- **Status strip**: the memory figure moved from the Models page to one strip across the foot of the window, on every
  page. Its ✕ hides it until the app closes, and **Memory** in the sidebar brings it back.
- **UI scale** stops at 150%. A saved scale above that is brought down to it. See [Settings](settings.md).
- **Text** starts with a capital and goes on in normal case everywhere. No label is in all capitals, apart from real
  acronyms such as ONNX and HTTP.
- **Stop**: while a script runs the play square is a red stop square. Pressing it, or ⌘., interrupts the script, and
  the strip and **Recent runs** say *Stopped after*, neither finished nor failed. What the script opened stays open.
- **Left arrow** closes the Templates panel as well as the console, and a second click brings them back as they were.
- **The status strip** says nothing before a first run. The hint under the empty console is gone too.
- **Package repository**: the search moved from Settings to the Models page, as the **Repository** section under the
  installed packages on the Packages tab. Settings keeps the account, the settings and the files. See
  [Models page](models-page.md#repository).
- **Live resources**: while a script runs a line under the console strip reads `memory 1.20 GB, peak 1.40 GB · heap
  340.0 MB of 4.00 GB · cpu 2.10 s · ort 12 runs`, read every second, so a script that grows is seen before it ends;
  the last run's figures stay there until the next run.
- **Still open**: a fold under Recent runs lists every open session with Terminate, Free on one a kept scope holds,
  and Free all; the console's `Still open:` line opens it. The Sessions tab has Free on those rows too.
- **Console commands**: `free search`, `free all`, `terminate #3`, `terminate all`, `sessions`, `scopes`, `help` and
  `clear` typed into the console's input run without a script. See [The sandbox](sandbox.md#the-command-line).
- **Console tooltips**: every control on the console strip says what it is for, which way it is now and what a click
  does; the level toggles say how many lines they hide.
- **Editor crash**: a long script at a high UI scale no longer crashes the editor pane.
- **Editor highlighting**: a `//` or a `#` inside a string, an apostrophe, an escaped quote or a raw string no longer
  shifts the colours of everything after it onto the wrong characters; the editor reads strings and comments itself.
  A console place in a script with CRLF line endings lands before the `\r`.
- **Editor margins**: a script longer or wider than its view no longer loses its margins when the editor takes the
  keyboard; the first letter of every line was cut off under the gutter and line 1 was half under the top edge.
- **Line numbers** stand on the baseline of their lines. They sat a little above the text, and below it on line 1.
- **ONNX threads** sleep when their session is idle instead of spinning, so an encoder, heads and a group open together
  don't take the cores of a Raspberry Pi between requests. `-Damie.ort.threads=N` sets how many threads a session gets.
  See [Troubleshooting](troubleshooting.md#slow-hardware).
- **Builds on a small heap**: the rows are embedded one at a time and only each row's sentence vector is kept, so ten
  thousand rows take 30 MB of heap instead of 2 GB.
- **The Models page opens on its models, not on their plots**: the plots are drawn on a thread of their own and shown
  as they are ready, where the first read drew every model's before showing anything; a serving model's plots are drawn
  again at most every ten seconds instead of after every answer.
- **The Models page on a slow disk** waits three times as long as its last read took before reading again, decodes a
  model's metrics history once per version of its file, and reads the process's memory from `/proc` on Linux and from one
  `ps` a second at most elsewhere.
- **Background templates** run eight at a time on a heap of 512 MB or more and fewer on a smaller one, one per 64 MB.
- **Memory**: the heap may grow to 35% of the machine's RAM; `AMIE_JVM_ARGS` adds JVM flags for one machine. An encoder
  that would not load, and an out of memory error nobody caught, each leave a console line saying what to do.
- **Startup timing**: `-Damie.timing=true` prints when the window was composed and when the Models page had its first read.
- **Raspberry Pi 5 benchmark**: `--bench` runs the app with no window and prints what each part costs on this machine
  (`./scripts/pi-bench.sh` on a Pi). Three things it found are fixed: the encoder's load no longer waits 4 seconds on
  DJL's call home (4.9 s to 0.8 s on a Mac, and nothing is sent to DJL's telemetry bucket any more), ONNX Runtime's
  library is unpacked once into `AMP/cache/ort/` instead of at every start, and the script engine is warmed in the
  background so the first Run answers in a moment. See [Raspberry Pi 5](pi5.md).

## Packages

`google-sheets.jar` and `google-docs.jar` join the other Google packages in `jar-jar`, on the shared Google code from
`amie-packages`. `janitor-ai.jar` asks any OpenAI-compatible chat API over plain REST.

| Package | Commands |
|---|---|
| `google-sheets.jar` | `tabs`, `read`, `write`, `append`, `create` |
| `google-docs.jar` | `read`, `create`, `append`, `replace` |
| `janitor-ai.jar` | `ask` (the default: a prompt, or a chat completions request as JSON), `raw`, `models` |

Both Google packages need `google.properties` filled in, so `initMod` refuses them until it is. To set them up, enable
the Sheets and Docs APIs in your Google project, copy the jars to `AMP/pluginDir`, and authorize unless an
`authorize --all` token already covers them:

```bash
java -jar google-sheets.jar authorize "<pluginDir>/config/google-sheets"
```

`janitor-ai.jar` speaks the OpenAI chat completions format (`POST /chat/completions`, `GET /models`) with the JDK's
HTTP client and nothing else: no SDK, no dependency. Copy it to `AMP/pluginDir`; the first `initMod("janitor-ai.jar")`
writes `AMP/pluginDir/config/janitor-ai/janitor-ai.properties` and refuses the jar until the file is filled in. Paste
the URL and the key there: `url=` takes the base URL ending in `/v1`, or the full `.../chat/completions` address, the
same thing JanitorAI's API settings take; `key=` the API key (sent as `Authorization: Bearer`, kept on this machine,
never logged); `model=` the model name (`models` lists what the server offers); `system=` an optional system prompt.
It works with any OpenAI-compatible server, a local one included: `http://localhost:11434/v1` for Ollama or
`http://localhost:1234/v1` for LM Studio, with `key=` left empty. `run(prompt)` answers the assistant's text only, so
`answer(prompt)` is the reply; an error names the HTTP status and what to do (401: the key, 404: the URL, 429: wait).

```kotlin
val ai = initMod("janitor-ai.jar")
log(ai.answer("say hi in three words"))
ai.close()
```

## Sandbox layout

The rule beside the editor now moves in a small window or at a large UI scale, and stays under the pointer after it
overshoots. The console and templates stay as you left them when you come back from the Models or Settings page. See
[the sandbox](sandbox.md).
