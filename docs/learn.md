# Learn amie

An interpreter for scripts that train, ask and serve intent models, from the first line at the prompt to a model served over HTTP, one chapter at a time. Every example runs as it is written.

## What amie is {#welcome}

```
 ______     __    __     __     ______
/\  __ \   /\ "-./  \   /\ \   /\  ___\
\ \  __ \  \ \ \-./\ \  \ \ \  \ \  __\
 \ \_\ \_\  \ \_\ \ \_\  \ \_\  \ \_____\
  \/_/\/_/   \/_/  \/_/   \/_/   \/_____/
```

amie trains small intent models on your own labeled text, runs them, groups them, serves them over HTTP and scripts all of it in Kotlin. A script is `main.kts`, and the functions it calls (`initMod`, `modelBuilder`, `group`, `database`, `log` and the rest) are in scope with nothing to import.

It works the way the Python interpreter does: a prompt where each line runs as you type it, script files, `-c` for a line of code, and a command for every feature. Everything a script makes lives in one folder, the AMP, so a model built by one script is there for the next, and a package added at the command line opens in a script.

amie is free software under the GNU General Public License, version 3 or later. The full text is the LICENSE file at the root of the repository; `amie --version` says so too.

## Install and the first run {#install}

The install script builds the interpreter and puts it on the PATH: the distribution under `~/.local/lib/amie`, and `~/.local/bin/amie` linked to it.

```
$ amie/install.sh
Building amie...
Installed amie 1.0.0 (Kotlin 2.1.0) on OpenJDK 64-Bit Server VM 21.0.5, Mac OS X aarch64 at /Users/me/.local/bin/amie
```

`--prefix /usr/local` installs it for every user (build as yourself first, then `sudo` it with `--no-build`); `--uninstall` takes it out again. A JDK 21 is needed, to run as well as to build, and `amie` finds one on its own: `AMIE_JAVA_HOME`, `JAVA_HOME` when it is one, the Mac's `java_home`, Homebrew's `openjdk@21`, sdkman's, `/usr/lib/jvm`, or one Gradle downloaded.

From the checkout, the launcher at the root of the repository runs it without installing, building it the first time and again whenever the sources changed:

```
./amie --version
amie 1.0.0 (Kotlin 2.1.0) on OpenJDK 64-Bit Server VM 21.0.5, Mac OS X aarch64
amie is free software under the GNU GPL, version 3 or later: see LICENSE.
```

Built by hand, `./gradlew :amie:installDist` at the repository root leaves it in `amie/build/install/amie`, the folder the install script copies. The rest of this book writes `amie` for any of these.

### The AMP folder

Everything amie knows is in one folder, the AMP: models, envs, packages, templates, the log. By default:

| Where | Folder |
|---|---|
| macOS | `~/Library/Application Support/amie pilot/AMP` |
| Windows | `%APPDATA%\amie pilot\AMP` |
| Elsewhere | `~/.local/share/amie-pilot/AMP` |

Another folder is one option away: `amie --amp ~/scratch/amp`, or the environment variable `AMIE_AMP_DIR`. A folder that doesn't exist yet is made, empty, which is a fine way to try things without touching the default one. The greeting at the prompt says which AMP is in use.

Intent models train on a BERT encoder, read from `AMP/models/bert`, or from the folder `AMIE_MODELS_DIR` names. A scratch AMP needs one or the other before its first build.

### The first run

```
$ amie
 ______     __    __     __     ______
/\  __ \   /\ "-./  \   /\ \   /\  ___\
\ \  __ \  \ \ \-./\ \  \ \ \  \ \  __\
 \ \_\ \_\  \ \_\ \ \_\  \ \_\  \ \_____\
  \/_/\/_/   \/_/  \/_/   \/_/   \/_____/
amie 1.0.0 (Kotlin 2.1.0) on OpenJDK 64-Bit Server VM 21.0.5, Mac OS X aarch64
Type :help for the commands, :api for the script's functions, :docs for the book, :quit to leave. AMP: /Users/me/Library/Application Support/amie pilot/AMP
>>> log("hello")
hello
>>> :quit
```

`amie -h` prints the whole command line, and every command prints its own help with `--help`.

## The prompt {#prompt}

At `>>>` each line is a piece of script, run in one interpreter kept from line to line. What a line defines (a `val`, a `fun`, a `class`, an `import`) is there for the lines after it. The value a line ends with is printed.

```
>>> val texts = listOf("turn the lights on", "is it raining")
>>> texts.size
2
>>> fun shout(s: String) = s.uppercase() + "!"
>>> shout(texts.first())
TURN THE LIGHTS ON!
```

A line that isn't whole waits at `...` for the rest. A block whose brackets are closed is sent by a blank line, so a `for` or a function over several lines goes in as one piece:

```
>>> for (t in texts) {
...     log(t.length)
... }
...
18
13
```

Lines are edited and recalled with the arrow keys, and the history lives in `~/.amie_history` between runs. Ctrl-C stops the line that is running, or drops the block being typed. Ctrl-D leaves, as `:quit` does.

### The prompt's own commands

A line that starts with a colon is for the prompt itself, not the script:

| Command | What it does |
|---|---|
| `:help` | These commands |
| `:api [word]` | The script's functions, with the word in their name or description: `:api group` |
| `:docs` | This book, in the browser |
| `:env [name]` | The env the lines run in; with a name, that env from now on |
| `:load file.kts` | Runs the file's lines here, so what it defines stays |
| `:sessions` | Every open session: models, groups, packages, templates in the background |
| `:config [x.jar]` | Package config files: every jar's and whether each is filled in, or one jar's keys; `key=value` sets keys; `:config --help` lists all of it |
| `:setup x.jar` | Asks for each config key of the jar with its hint; Enter keeps a value |
| `:terminate x` | Closes the session named x, or `#id`; `:terminate all` closes every one |
| `:runs` | The recorded runs of `main.kts`, `AMP/pluginDir/runs.jsonl` |
| `:reset` | Forgets what the lines typed here defined |
| `:clear` | Clears the screen |
| `:quit` | Leaves; `:exit`, `:q` and Ctrl-D do too |

`help`, `help(group)`, `quit` and `exit` work without the colon too, with or without the parentheses, as they do at Python's prompt. A line that starts with `amie` and a space is amie's own command line, run right here: `amie envs new prod`, `amie models list`, `amie hf search bert`. On a terminal the banner is in the pride flag's colors, the commands and the prompt have a color each and errors are red; a piped output or `NO_COLOR` in the environment leaves everything plain.

`:api` is the fastest way to find a function. The description of each comes from the list the script API is generated from, so what it says is what the function does:

```
>>> :api vote
fun group(name: String): ModelSwarm
    The models of a group opened together: predict is the best score across them, vote the label most give, ...
```

## The first lines of script {#lines}

The console has levels. `log` and `info` are the script's own output, `data` is what it produced, `error` is a problem. Internal lines, the engine's own, are hidden unless amie runs with `-v`.

```
>>> log("hello from amie")
hello from amie
>>> data(mapOf("lights" to 0.9f, "weather" to 0.1f))
lights: 0.9
weather: 0.1
>>> error("how a problem looks")
how a problem looks
```

Errors go to standard error, everything else to standard output, so a script's results can be piped and its complaints still reach the terminal. Every line also goes to `AMP/logs.txt`.

### Ask a model

A model is opened by name with `initMod`, which gives a session: one per model, kept open until it is closed. Every session answers through the same three calls.

```
>>> val m = initMod("intents")
>>> m.predict("switch off the kitchen light")
lights
>>> m.scores("is it going to rain")
{weather=0.93, lights=0.04, greeting=0.03}
>>> m.answer("good morning")
greeting:0.97
```

| Call | Returns |
|---|---|
| `predict(text)` | The best label, nothing else; throws when the model answers nothing |
| `scores(text)` | Every label with its score, best first, a `Map<String, Float>` |
| `predictions(text)` | The answers as `Prediction(label, score)`, in the model's order |
| `inference(text)`, `run(text)` | The raw lines the model answers, `label:score` |
| `answer(text)` | The first raw answer as it came, or null |

Each call runs the model once, so ask once and keep the result. The chapter on models trains the `intents` model these lines use.

### A confidence gate

The scores let a script say "not sure" instead of guessing:

```
>>> val threshold = 0.5f
>>> for (text in listOf("turn the lights on", "asdf qwerty")) {
...     val (label, score) = m.scores(text).entries.first()
...     if (score < threshold) log("$text: not sure ($label ${"%.2f".format(score)})")
...     else log("$text: $label ${"%.2f".format(score)}")
... }
...
turn the lights on: lights 0.97
asdf qwerty: not sure (greeting 0.41)
```

### A model for one block

`sessionMod` gives a snapshot of a model's head, kept in memory and gone when the block ends. Inside the block its functions need no name in front, and what the block ends on is what the call returns:

```
>>> val answers = sessionMod("intents") {
...     listOf("hello there", "lights off").map { predict(it) }
... }
...
>>> answers
[greeting, lights]
```

## Scripts in files {#scripts}

A file of script runs as `main.kts`, with the same API in scope and nothing to import:

```
// label.kts
val m = initMod("intents")
val lines = generateSequence { input() }        // standard input, a line at a time, until it ends
lines.forEach { log("${m.predict(it)}\t$it") }
```

```
$ printf 'lights off\nis it raining\n' | amie label.kts
lights	lights off
weather	is it raining
```

The forms of a run:

| Command line | What happens |
|---|---|
| `amie label.kts` | Runs the file |
| `amie label.kts a b` | With arguments, read as `System.getProperty("amie.args")`, one a line |
| `amie -c 'log(initMod("intents").predict("hi"))'` | Runs the code given |
| `amie -i label.kts` | Runs it, then opens the prompt with its models still open and its values defined |
| `amie -e prod label.kts` | In the env `prod`, over the one the script names |
| `amie -v label.kts` | With the internal lines: the env sourcing, a build's phases, what the run used |
| `amie run label.kts` | The same as the bare file |
| `amie new reader` | A project: `AMP/projects/reader/main.kts` to start from, in an env named `reader` |
| `amie run reader` | Runs the project by its name, as a starter or a template by its title |

Standard output carries the console, standard error the errors, with the script's line in front:

```
$ amie bad.kts
main.kts:2:5: Unresolved reference: initMode
$ echo $?
1
```

The exit code is 0 when the script finished, 1 when it failed, and 2 for a command line amie can't read. A script that leaves a model serving keeps the process up until Ctrl-C.

`-i` is the way to work on a script: run it, then poke at what it built from the prompt, and `:load` runs a file into the prompt the same way without leaving.

## Train a model {#models}

An intent model is trained from a csv with a text column and a label column, whatever they are called. A handful of rows for each label is enough to start; more rows make a better model.

```
text,label
turn the lights on,lights
switch off the kitchen light,lights
lights out please,lights
is it going to rain,weather
what's the weather like today,weather
will it be sunny tomorrow,weather
hello there,greeting
good morning,greeting
hi how are you,greeting
```

The command does the whole thing and prints the validation:

```
$ amie models build intents.csv --name intents
Building intents: embedding 9 rows, training, exporting
accuracy 1.00  macro F1 1.00
label     precision  recall  f1  support
greeting  1.00       1.00    1.00  3
lights    1.00       1.00    1.00  3
weather   1.00       1.00    1.00  3
```

The model is in `AMP/models/intents/intents`, ready for `initMod("intents")`. Validated on a split of its own rows by default, it validates on a test file instead with `--test test.csv`.

The same build as script, which is what the command runs for you. `intents.csv` stands for your own file: `source()` finds it as given, relative to the folder amie was started in, then in `AMP/`, then in `AMP/pluginDir/`. A new AMP folder has `sample-intents.csv` to try.

```
val train = source("intents.csv")          // the text and label columns are inferred
relate(train, "intents-test.csv")          // optional: a test file to validate on
val built = modelBuilder("intents", train)  // embeds with BERT, trains a small head, exports ONNX
built.modelStatus()                         // precision, recall, F1 for each label
```

A file named in a script is looked up as given, then in the AMP folder, then in `AMP/pluginDir`.

### Look at what was built

```
$ amie models list
intent    intents    3 labels    v1
$ amie models validate intents
$ amie models info intents
$ amie models remove intents
```

`validate` prints the stored validation again; `info` the labels, the encoder, the version, how it was trained, the groups it is in and where it is served, if anywhere. In script, `validateMetrics("intents")`, `confusionMatrix("intents")` and `mistakes("intents")` show the same as tables.

### Teach it more

A trained model learns from new rows without starting over. Rows of labels it knows correct it, a label it doesn't know gets an output of its own, and what it knew is replayed so it keeps it. The model before stays as a version.

```
>>> fineTune("intents", source("more.csv"))
>>> versions("intents")
>>> rollback("intents", 1)
```

`buildOptions` opens the rest: your own head architectures, cross-validation over folds, calibration so the scores mean what they say, and a search over candidates. `:api buildOptions` lists what it takes.

## Groups {#groups}

A group is a named set of models, kept in the env, that is asked as one. `vote` is the label most of them give, `predict` the best score across them, `each` every model's own answer.

```
$ amie groups add house intents dummy
$ amie groups list
house    intents, dummy    env default
$ amie groups open house "turn the lights on"
intents: lights
dummy: lights
vote: lights
```

Without texts, `groups open` reads them from standard input, a line each. `groups remove house dummy` takes a model out, and without a model removes the group.

In script:

```
>>> val swarm = group("house")
>>> swarm.vote("is it raining")
weather
>>> swarm.each("is it raining")
{intents=weather, dummy=weather}
>>> swarm.close()
```

Opening a group opens a session of each model; closing the swarm closes the ones it opened.

## Packages {#packages}

A package is a jar with a class that answers text, kept in `AMP/pluginDir`. It is opened like a model, by its file name, and asked with `run` or `answer`, since it has no labels of its own:

```
$ amie packages search google
6 jars in samging/jar-jar matching 'google'
$ amie packages add spotify.jar
$ amie packages add ~/build/greeter.jar
greeter.jar: Greeter 1.2, a package class found
$ amie packages test greeter.jar
$ amie packages info greeter.jar
$ amie packages setup spotify.jar
    clientId: 
    clientSecret: 
```

A jar's settings are files in `AMP/pluginDir/config/<jar>/`. `amie packages setup` asks for each key with the hint the file gives, Enter keeping a value, and offers the values another jar already has, so one Google client fills every Google package. `amie packages config spotify.jar clientId=abc` sets a key from a script, `--all-jars` sets it for every jar with that file, and `amie packages config` alone shows every jar's files and whether each is filled in.

```
>>> val greeter = initMod("greeter.jar")
>>> greeter.answer("hello")
Hello to you
```

`add` checks that the jar has a package class before copying it in. `test` runs the tests the jar declares in `META-INF/amie/package.json`, or one call with "hello" when it declares none. `info` says what the jar is, where it came from, whether it opens here and how its last test went.

### Writing a package: run and close {#writing-packages}

A package class fulfils the `AmiePackage` interface: `List<String> run(String)` and `close()`, both required, and a public no-arg constructor. Implementing the interface is the easiest way, but any class with the same methods qualifies, so a jar needs no amie library to compile against:

| Member | What amie does with it |
|---|---|
| The no-arg constructor | Every `initMod("x.jar")` makes a new instance, in a class loader of its own |
| `configure(File dir)`, if there | Called after the constructor with `AMP/pluginDir/config/<jar>/`; `Path`, `String` and `Map` work too |
| `List<String> run(String text)` | What `.run` and `.answer` call; each element is one line |
| `void close()` | Called once, by `.close()`, `:terminate` or the end of a scope, to release what `run` opened; then the class loader is dropped |

```
public class Greeter {
    public java.util.List<String> run(String text) {
        return java.util.List.of("Hello to you");
    }

    public void close() {}
}
```

Two sessions of one jar never share an instance or a static field. `run` runs on amie's `amie-package-call` thread while the script waits; Ctrl-C interrupts it, and an exception it throws becomes the script's error. The classes must be compiled for Java 21 or older, and a Kotlin package can rely on amie's own standard library. When a jar has several classes that fit, `"entry"` in its `META-INF/amie/package.json` names the one.

### Packages from GitHub {#github-packages}

A script asks for a jar in a GitHub repository with `github:`. amie downloads it, checks it as `packages add` does, and keeps it in `AMP/pluginDir` with where it came from:

```
>>> val notes = initMod("github:samging/jar-jar/memory.jar")
>>> notes.answer("help")
remember <text>  keeps it
>>> val files = initMod("files.jar")
```

A plain name like `files.jar` is looked for in the env, `AMP/pluginDir`, as a path, and last in the repositories of `AMP/github.json`, which starts with `samging/jar-jar`. `@main`, a tag or a commit after the name pins a version, and a release's assets are found too. A `github:` open downloads again only when the file changed; a plain name uses the copy already in `AMP/pluginDir`. A private repository needs `GITHUB_TOKEN` or a `"token"` in `AMP/github.json`; without one the jar is reported as not found. `amie packages search` lists what those repositories hold, and `amie packages add <x.jar>` downloads one by its name.

To upload a jar, check it with `amie packages add` and `amie packages test`, then open a pull request that adds it at the top of samging/jar-jar. Its Check jars workflow first checks every jar the way amie checks a download, running none of its code, then opens each one as `amie packages test` does: it calls `run()` with every test the jar declares and then `close()`. The jar is there for everyone once the pull request is merged.

### Maven packages

A library from Maven Central goes into the env, so a script can import it:

```
$ amie packages maven org.jsoup:jsoup:1.17.2
$ amie packages list
```

```
>>> import org.jsoup.Jsoup
>>> Jsoup.parse("<p>hi</p>").text()
hi
```

In a script file the same is one line, read before the script compiles: `implementation("org.jsoup:jsoup:1.17.2")`. What was ever imported into an env is kept in its lock file, like a virtualenv.

## Envs {#envs}

An env is a folder under `AMP/env` holding its Maven packages and its groups. Scripts run in `default` unless told otherwise. A second env keeps one project's packages away from another's.

```
$ amie envs new prod
$ amie envs list
default    2 packages    1 group    AMP/env/default
prod       0 packages    0 groups   AMP/env/prod
$ amie envs show prod
```

Three ways to run in an env:

- `amie -e prod script.kts`, or `-e prod` before any command, for this run.

- `:env prod` at the prompt, for the lines after it.

- `amie envs use prod`, which rewrites the `environment(...)` line of the saved `main.kts` in `AMP/pluginDir`, or makes one with it.


A script names its own env with `environment(SettingsEnv("prod"))`, read before it compiles; `-e` wins over it.

## Templates and the background {#templates}

A template is a script saved under a name in `AMP/templates`: the starters that come with amie, and the scripts you save.

```
$ amie templates list
Hello: log at every level          first steps, starter
Train a model from a csv           models, starter
Serve a model and call it          serving, starter
$ amie templates show "Train a model from a csv"
$ amie templates run "Train a model from a csv"
```

`export` turns a template into a bundle that runs on its own, a folder with a `run.jar`:

```
$ amie templates save reader ~/reader.kts     keeps the file as the template "reader"; a name taken is saved again
$ amie templates edit reader                  opens it in $VISUAL or $EDITOR, or prints the path when neither is set
$ amie templates delete reader                removes it
```

```
$ amie templates export talk --out ~/bundles/talk
$ java -jar ~/bundles/talk/run.jar
```

The bundle carries the env's Maven jars and the package jars the script uses. `--no-jars` leaves them out, for a bundle to send where they are already, or to keep it small: `require.txt` then lists each file with the path to put it at under the bundle (a Maven jar from Maven Central or the exporting machine's `AMP/env/caches`, a package jar from its `AMP/pluginDir`), `run.jar --check` says which are still missing, and a run with a Maven jar missing fetches it from Maven Central when it can reach it; with a package jar missing it stops and names it.

```
$ amie templates export talk --out ~/bundles/talk --no-jars
$ cat ~/bundles/talk/require.txt
$ java -jar ~/bundles/talk/run.jar --check
```

### A template in the background

A script can start a template beside itself and talk to it. The template reads its caller's lines with `input()` and answers with `output(...)`; the caller sends and receives.

```
// the template, saved as "reader"
val model = initMod("intents")
while (true) {
    val text = input() ?: break        // null once the caller closes its input
    output(model.predict(text))
}
```

```
>>> val job = coroutineTemplate("reader")
>>> job.send("turn the lights on")
>>> job.receive()
lights
>>> job.closeInput()
>>> job.await()
finished
```

By delegation the same template is called like a function, started on the first call and reused after:

```
>>> val reader by template("reader")
>>> reader("is it raining")
weather
>>> reader.close()
```

A template that fails makes the call throw, with its name and its error, so a failure never passes for an answer. `:sessions` lists the runs in the background, and a script that ends waits for the ones it started.

## Serve a model {#serving}

A model served answers over HTTP. The command keeps the process up until Ctrl-C:

```
$ amie models serve intents --port 8765
Serving intents at http://127.0.0.1:8765/models/intents/predict
Serving. Stop with Ctrl-C or SIGTERM.
```

From another terminal:

```
$ curl -X POST http://127.0.0.1:8765/models/intents/predict -d '{"text": "turn the lights on"}'
lights:0.97
$ curl 'http://127.0.0.1:8765/models/intents/predict?text=is+it+raining'
```

Every request is appended to the model's `incoming.jsonl`. In script, `serve("intents", port = 8765)` returns the address or null when it couldn't start, and `stopServing("intents")` ends it; a script that leaves a model serving keeps amie running after its last line.

## Models from the Hub {#hub}

Models of the Hugging Face Hub that run here, ONNX among them, are searched, looked at and imported into `AMP/models/imported`:

```
$ amie hf search sentiment --format onnx
$ amie hf search whisper --task asr --limit 5
$ amie hf info onnx-community/whisper-tiny.en
$ amie hf import onnx-community/whisper-tiny.en --model-name whisper-tiny
```

```
>>> val whisper = initMod("whisper-tiny")
>>> huggingFace.search("bert", format = "onnx")
>>> val m = huggingFace("owner/repo")
```

The tasks narrow a search: text-classification, ner, tts, asr, embeddings and more; `info` says which files run here and which one an import takes. A repo with several files that run here (a full `model.onnx` beside quantized and fp16 ones, say) is picked at the terminal with the arrow keys: the same list, the one import takes under the cursor, up and down move it, Enter imports, `n` leaves. `--take 3` picks for a pipe or a script. A gated repo needs `HF_TOKEN` in the environment. An ONNX, TorchScript or TensorFlow model already on disk is opened by its path with `importModel("/path/model.onnx")`.

### Encoder and decoder pairs {#pairs}

A sequence-to-sequence export (T5, BART, Marian translation, Whisper) is two ONNX files in one folder, `encoder_model.onnx` with `decoder_model.onnx` and often `decoder_with_past_model.onnx`. Neither answers a text alone: the encoder turns the input into hidden states, the decoder takes those with the ids written so far and gives the next token's scores, one step at a time. The import offers every encoder and decoder of the folder as a numbered menu, the companion files beneath, and at a terminal waits for the choice:

```
$ amie hf import google-t5/t5-small
google-t5/t5-small is an encoder and decoder pair; the import takes the files marked:
  encoders
  [x]  1  onnx/encoder_model.onnx (134.9 MB)
> [ ]  2  onnx/encoder_model_quantized.onnx (33.9 MB)
  decoders
  [x]  3  onnx/decoder_model.onnx (221.7 MB)
  [ ]  4  onnx/decoder_model_merged.onnx (221.9 MB)
  [ ]  5  onnx/decoder_model_merged_quantized.onnx (56.0 MB)
  [ ]  6  onnx/decoder_model_quantized.onnx (55.7 MB)
  [x]  7  onnx/decoder_with_past_model.onnx (209.7 MB)
  [ ]  8  onnx/decoder_with_past_model_quantized.onnx (52.7 MB)
  with: config.json, generation_config.json, spiece.model, tokenizer.json, tokenizer_config.json
Take 1 3 7, 568.3 MB
up and down move, space takes or leaves a file, Enter imports, n leaves
```

The marked files are the default: the full variant, with the merged decoder left out when the plain and the with-past ones are there. Space takes or leaves the file under the cursor, the quantized ones here, at least one encoder and one decoder; the size line follows. `--take "2 6 8"` picks for a pipe or a script's `huggingFace("…")`, which otherwise take the marked files without asking.

`initMod("opus-mt-en-de")` then opens both sessions, and the script wires them. `.inference`, `.predict` and `.answer` refuse on a pair, saying how.

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
| `m.getStatus()`, `m.close()` | Each session's status by name; every session closed | Both sessions |

What the encoder takes is read off the folder: a text pair has no `preprocessor_config.json` and takes text through its tokenizer; one whose file names a feature extractor (Whisper) takes an audio file, wav or aiff at any rate, turned into the log-mel spectrogram that file describes; one naming an image processor (TrOCR, Donut, Nougat) takes an image file, resized and normalized as it says. `encode` does whichever applies, and `decoderFeeds` fills the decoder's inputs by their names, Marian's `encoder_attention_mask` among them. The same greedy loop then translates or transcribes:

```
>>> val m = initMod("t5-small")                 // or "whisper-tiny", with a wav in place of the text
>>> val e = m.encode("translate English to German: The house is wonderful.")
>>> val starts = m.startTokens(e)               // Whisper: start, language (detected, or startTokens(e, "de")), transcribe, no timestamps
>>> val ids = starts.toMutableList()
>>> for (i in 1..40) {
...     val logits = m.decoder.run(m.decoderFeeds(e, ids.toLongArray())).getValue("logits")   // [1, ids so far, vocabulary]
...     val vocab = logits.shape.last().toInt()
...     val last = logits.floats().takeLast(vocab)                 // the scores at the last place
...     val best = last.indices.maxBy { last[it] }
...     if (best.toLong() == m.endToken) break
...     ids.add(best.toLong())
... }
>>> log(m.text(ids.drop(starts.size).toLongArray()))
Das Haus ist wunderbar.
```

The sessions are still there raw: `m.tokens(text)` and `m.encoder.run(mapOf("input_ids" to t.ids, "attention_mask" to t.mask))` are what `encode(text)` does, and `m.encoder.inputs` names what a file becomes (`input_ids`, `input_features`, `pixel_values`). Running the whole id list each step is simple, not fast. A faster loop runs `m.decoder` once, then `m.decoderWithPast` with each step's `present.*` outputs fed back as its `past_key_values.*` inputs, which its `.inputs` name.

## Sessions and housekeeping {#housekeeping}

Whatever a script opens is a session: a model, a group, a package, a template in the background. Sessions stay open after the line that opened them, which is what makes the second `predict` fast.

```
>>> :sessions
#1  intents  model    running  opened 14:02:11
#2  house    group    running  opened 14:05:40
>>> :terminate house
>>> :terminate all
```

From the command line, `amie sessions list` shows the sessions of that process, and `amie sessions runs` the recorded runs of `main.kts`.

### Scope functions

A block decides how long what it opens lives:

| Block | What it opens is |
|---|---|
| `ortClosable { }` | Closed with the block |
| `ortStatic("name") { }` | Kept across runs under that name |
| `ortRequired("name") { }` | Kept while anything else is in session, freed once nothing else is open; its block opens it again |
| `ortLimited("name", 3) { }` | Capped at that many |
| `ortAsync { }` | Run in the background |

```
>>> ortClosable {
...     val m = initMod("intents")
...     listOf("hi", "lights off").map { m.predict(it) }
... }
...
[greeting, lights]
```

### Data and the shell

A Postgres database named in `AMP/databases.json` is one call away, and so is the login shell:

```
>>> val db = database("main")
>>> db.print("select label, count(*) from answers group by label")
>>> shell("ls ~/data").out
```

## When something goes wrong {#errors}

`initMod` and `database` throw when what they name isn't there, so a script doesn't go on without data. Wrap what may be missing:

```
val m = runCatching { initMod("intents") }.getOrElse { e ->
    error("no intents model yet (${e.message}); building one")
    modelBuilder("intents", source("intents.csv"))
    initMod("intents")
}
```

`importModel`, `importPlugin` and the save and load of states never throw: they log one error line and return nothing, so test their value.

At the prompt an error prints and the prompt comes back, with everything defined before it still there. In a file the run stops, the line is on standard error and the exit code is 1. `handleBy(handler) { }` runs a block on after an error with the handler's value, and `errorLog()` is what the local text model makes of the last one.

When a build or a run does something you didn't expect, `-v` shows the internal lines: which env was sourced, the build's phases, what the run used.


The [amie reference](amie-reference.md) has the command line, every command, the script API in brief and the exit codes; the [Scripting API](sandbox-scripting.md) has every function in full.
