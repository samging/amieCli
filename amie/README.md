```
 ______     __    __     __     ______
/\  __ \   /\ "-./  \   /\ \   /\  ___\
\ \  __ \  \ \ \-./\ \  \ \ \  \ \  __\
 \ \_\ \_\  \ \_\ \ \_\  \ \_\  \ \_____\
  \/_/\/_/   \/_/  \/_/   \/_/   \/_____/
```

# amie

An interpreter for Kotlin scripts that train, ask, group and serve intent models. It works like the Python
interpreter: a prompt, script files, `-c`, and a command for every feature. The script's functions (`initMod`,
`modelBuilder`, `group`, `database`, `log`, ...) are in scope with nothing to import, and everything a script makes,
a model, a group, a package, an env, lives in one folder, the AMP, for the next script to use.

amie is free software under the GNU General Public License, version 3 or later: [COPYING](COPYING), and the full
text in [LICENSE](../../LICENSE) at the repository root.

## Install

`install.sh` builds the interpreter and puts it on the PATH: the distribution under `~/.local/lib/amie`, and
`~/.local/bin/amie` linked to it.

```
amie/install.sh
```

`--prefix /usr/local` installs it for every user (build as yourself first, then `sudo` it with `--no-build`);
`--uninstall` takes it out again. A JDK 21 is needed, to run as well as to build, and `amie` finds one on its own:
`AMIE_JAVA_HOME`, `JAVA_HOME` when it is one, the Mac's `java_home`, Homebrew's `openjdk@21`, sdkman's, `/usr/lib/jvm`,
or one Gradle downloaded.

From the checkout, the launcher at the repository root runs it without installing, building it the first time and
again when the sources changed:

```
./amie --version
```

`./gradlew :amie:installDist` builds it by hand into `amie/build/install/amie`, the folder `install.sh`
copies; `./gradlew :amie:distZip` zips the same into `build/distributions`, to unpack anywhere.

The AMP folder is `~/Library/Application Support/amie pilot/AMP` on macOS, `%APPDATA%\amie pilot\AMP` on Windows and
`~/.local/share/amie-pilot/AMP` elsewhere; `AMIE_AMP_DIR` or `--amp <folder>` names another, made when it doesn't
exist. The BERT encoder every intent model trains on is read from `AMP/models/bert`, or from the folder
`AMIE_MODELS_DIR` names.

## Try it

The starters, the scripts the app's Examples menu holds, come with amie: `amie examples` lists them by group, a title
prints one, `amie run "<title>"` runs it. They run on a fresh AMP folder: the first one seeds the templates, a model
called `dummy` and `sample-intents.csv`. What follows is a run against an empty folder (`AMIE_AMP_DIR`), with the
BERT encoder under `AMIE_MODELS_DIR`.

```
$ amie examples
First steps
  Hello: log at every level
  Ask the dummy model
  A confidence gate
Models
  Train a model from a csv
  Train with your own heads
  ...
Functions
  Function: clean a text
  ...
amie examples "<title>" prints one, amie run "<title>" runs it; they are in AMP/templates too (amie templates list)

$ amie run "Hello: log at every level"
hello from main.kts
the same level as log
what the script produced, kept apart from chatter
how a problem looks (the error toggle turns on when a run fails)

$ amie run "Ask the dummy model"
dummy "turn the lights on" -> lights 0.572
lights
dummy "turn the lights on" -> lights 0.572, weather 0.476, greeting 0.451
{lights=0.5722593, weather=0.4760026, greeting=0.4507023}
dummy "hello there" -> weather 0.608, lights 0.520, greeting 0.373
weather:0.6081904
lights:0.5195025
greeting:0.3731988
ModuleManifest(name=dummy, format=onnx, model=model.onnx, encoder=.../bert/bge, pooling=mean, ... labels=[greeting, lights, weather], ...)

$ amie run "Train a model from a csv"
modelBuilder: 45 rows in, 45 kept; 3 labels: greeting 15, lights 15, weather 15
modelBuilder: training on 36 rows, validating on 9 (a 20% split); 4 heads to compare
modelBuilder: linear: accuracy 1.000 · macro F1 1.000 on validation (train accuracy 1.000, loss 0.0000, 2307 parameters)
...
modelBuilder: compared 4 heads by macro F1: linear 1.000 · dense[16] 1.000 · dense[32] 1.000 · dense[64, 16] 1.000
modelBuilder: chosen: linear, the best macro F1 on the validation rows (next: dense[16] 1.000)
Model 'intents' saved, load it with amie.initMod("intents")
intents "switch off the kitchen light" -> lights 1.000
lights

$ amie run "A group of models, asked together"
dummy "turn the lights on" -> lights 0.572
house "turn the lights on" -> lights 0.572 (dummy)
lights
...
dummy says lights
house "turn the lights on" -> lights 0.572 (dummy) · weather 0.476 · greeting 0.451
lights 0.5722593
weather 0.4760026
greeting 0.4507023

$ amie run "Talk to amie"
Hello! How can I assist you?
The three most commonly recognized colors of the rainbow are **red**, **orange**, and **yellow**. ...
```

"Talk to amie" downloads a 500 MB chat model (Qwen3 0.6B) into `AMP/models/text/qwen3-0.6b` on its first run.

The dummy's scores are made up (it is there so a script has a model before any is trained); the one trained from the
csv answers for real. `amie examples "Ask the dummy model"` prints a starter's code to read or to copy into a file of
your own.

## The prompt

```
$ ./amie
 ______     __    __     __     ______
...
amie 1.0.0 (Kotlin 2.1.0) on OpenJDK 64-Bit Server VM 21.0.5, Mac OS X aarch64
Type :help for the commands, :api for the script's functions, :learn or :docs for the book, :quit to leave. AMP: /Users/me/Library/Application Support/amie pilot/AMP
>>> val m = initMod("greetings")
>>> m.predict("good morning")
greeting
>>> for (text in listOf("hi", "lights off")) {
...     log(m.scores(text))
... }
...
```

Each line is a piece of script, run in one interpreter kept from line to line: what a line defines (`val`, `fun`,
`class`, `import`) is there for the next, until `:reset`. A line that isn't whole waits at `...` for the rest; a blank
line sends a block whose brackets are closed. The value a line ends with is printed. Lines are edited and recalled with
the arrow keys; the history is `~/.amie_history`. Ctrl-C stops the line running or drops the block being typed, Ctrl-D
leaves. On a terminal the banner is in the pride flag's colors, the commands and the prompt have a color each, errors
are red; a piped output or `NO_COLOR` in the environment leaves everything plain.

| Command | What it does |
|---|---|
| `:help` | The commands |
| `:api [word]` | The script's functions, with the word in their name or description: `:api group` |
| `:learn`, `:docs` | The learn book, [learn/index.html](learn/index.html), in the browser (`./amie learn`, `./amie learn path` from the shell) |
| `:env [name]` | The env the lines run in; with a name, that env from now on |
| `:load file.kts` | Runs the file's lines here, so what it defines stays |
| `:sessions` | Every open session: models, groups, packages, templates in the background |
| `:terminate x` | Closes the session named x, or `#id`; `:terminate all` closes every one |
| `:runs` | The recorded runs of `main.kts`, `AMP/pluginDir/runs.jsonl` |
| `:reset` | Forgets what the lines typed here defined |
| `:clear` | Clears the screen |
| `:quit` | Leaves (`:exit`, `:q`, Ctrl-D) |
| `help`, `help(x)`, `quit` | The same without the colon, as python has them: `help(group)` is `:api group` |
| `amie models list` | A command line of amie, run here: `amie envs new prod`, `amie hf search bert`, `amie --help` |

## Scripts

```
./amie train.kts                 runs the file as main.kts
./amie train.kts a b             with arguments: System.getProperty("amie.args"), one a line
./amie -c 'log(initMod("x").predict("hi"))'
./amie -i train.kts              runs it, then the prompt, with its models still open
./amie -e prod train.kts         in the env prod (AMP/env/prod), over the one the script names
./amie -v train.kts              with the internal lines: the env sourcing, a build's phases, what the run used
./amie run train.kts             the same as the bare file
```

The script's console goes to standard output, errors to standard error with the script's line
(`main.kts:12:5: ...`), and what `input()` reads is standard input. The exit code is 0 when the script finished, 1
when it failed and 2 for a command line amie can't read. A script that leaves a model serving keeps the process up
until Ctrl-C.

Every console line also goes to `AMP/logs.txt`.

## The script API in brief

Every call works bare (`log("hi")`) or through `amie` (`amie.log("hi")`); `:api` at the prompt lists them with their
signatures, and [docs/sandbox-scripting.md](../../docs/sandbox-scripting.md) in the repository is the full guide.

| Call | What it does |
|---|---|
| `log(x)`, `info`, `error`, `data` | A console line at that level; a list, a map or a model's answer is printed as such |
| `source("train.csv")`, `relate(train, "test.csv")` | A labeled csv with its text and label columns inferred; a test file for it to validate on |
| `modelBuilder("name", data)`, `modelBuilder("name", data, buildOptions(...))` | Trains and exports an intent model to `AMP/models/intents/<name>` |
| `fineTune("name", data)`, `calibrate("name", data)`, `versions("name")`, `rollback("name", 2)` | Teaches a model more, makes its scores honest, the heads it had |
| `validateMetrics("name")`, `metricsChart`, `metricsHistory`, `confusionMatrix`, `mistakes`, `suspects` | The stored validation as tables and ASCII charts |
| `initMod("name")` | A session of a model: `.predict(text)`, `.scores(text)`, `.answer(text)`; a package jar when the name ends in `.jar`; a text, speech or imported model by its name |
| `sessionMod("name") { predict("hi") }` | A model to ask for one block |
| `group("name")` | The models of a group asked together: `.each(text)`, `.vote(text)`, `.combined(text)` |
| `huggingFace("owner/repo")`, `huggingFace.search("bert", format = "onnx")` | A model of the Hub imported into `AMP/models/imported`, and the search |
| `importModel("/path/model.onnx")` | An ONNX, TorchScript or TensorFlow model by its path |
| `serve("name", port = 8765)`, `stopServing("name")` | The model over HTTP |
| `ortClosable { }`, `ortStatic("name") { }`, `ortTimed("name", 60) { }`, `ortLimited("name", 3) { }`, `ortAsync { }` | What a block opens: closed with it, kept across runs, freed when idle, capped, or run in the background |
| `database("main")` | A Postgres database of `AMP/databases.json`: `.query(sql)`, `.execute(sql)`, `.print(sql)` |
| `shell("ls")`, `shellScript("x.sh", args)` | A command in the login shell: `.out`, `.err`, `.exit` |
| `implementation("group:module:version")`, `environment(SettingsEnv("name"))` | A Maven package into the env; the env the script runs in (read before it compiles) |
| `coroutineTemplate("name")`, `template("name")` | A saved template run in the background: `.send`, `.receive`, `.await` |
| `input()`, `output("text")` | The next line of standard input; an answer for whoever started this script |
| `handleBy(handler) { }`, `handleBy(e)`, `errorLog()` | Runs on after an error with the handler's value; what the local text model makes of an error |

## Commands

Every command prints its help with `--help`. `-e`, `--amp` and `-v` go before the command.

### models

```
./amie models list                                 every model: intent, text, speech, imported
./amie models build data.csv --name intents        trains an intent model on the labeled rows (modelBuilder) and prints its validation
./amie models build data.csv --test test.csv       validated on the test file instead of a split
./amie models validate intents                     the last stored validation: accuracy, macro F1, each label
./amie models serve intents --port 8765            serves it over HTTP until Ctrl-C; POST {"text": "hi"} at /models/intents/predict
./amie models info intents                         labels, encoder, version, how it was trained, its groups, its serving address
```

### groups

```
./amie groups list                                 every group of every env
./amie groups add weather intents places           makes the group, or adds the models; --env prod for another env
./amie groups remove weather places                takes a model out; without a model, removes the group
./amie groups open weather "is it raining"         asks the group: each model's answer, then the vote; without texts, every line of standard input
```

### packages

```
./amie packages list                               the jars of AMP/pluginDir, and the env's Maven packages
./amie packages add ~/build/greeter.jar            checks the jar has a package class and copies it in; initMod("greeter.jar") opens it
./amie packages maven org.jsoup:jsoup:1.17.2       adds the Maven package to the env, from Maven Central
./amie packages test greeter.jar                   runs the tests the jar declares (META-INF/amie/package.json), else one call with hello
./amie packages info greeter.jar                   its metadata, where it came from, whether it opens here, its last test run
```

### envs

```
./amie envs list                                   every env with its packages and groups
./amie envs new prod                               an empty env, AMP/env/prod
./amie envs use prod                               main.kts runs in it from now on (its environment line rewritten, or a main.kts made with one)
./amie envs show prod                              its packages, where each came from, its groups
```

### templates

```
./amie templates list                              the starter templates and the scripts saved as templates
./amie templates show "Build a model"              its code
./amie templates run "Build a model"               runs it as main.kts
./amie templates export talk --out ~/bundles/talk  a bundle that runs on its own: java -jar run.jar
```

### sessions

```
./amie sessions list                               the sessions open in this process (:sessions at the prompt lists the prompt's)
./amie sessions runs                               the recorded runs of main.kts
./amie sessions terminate "#3"                     closes a session; terminate all closes every one
```

### hf

```
./amie hf search "sentiment" --format onnx         models of the Hub with an ONNX file, the most downloaded first
./amie hf search whisper --task asr --limit 5      narrowed to a task: text-classification, ner, tts, asr, embeddings, ...
./amie hf info onnx-community/whisper-tiny.en      its files that run here, the one import takes, its labels
./amie hf import onnx-community/whisper-tiny.en --model-name whisper-tiny
                                                   downloads it into AMP/models/imported/whisper-tiny; initMod("whisper-tiny") opens it
```

TensorFlow repos are found under the Hub's `tf-keras` tag (a SavedModel), since `tf` mostly means Keras weights that
don't run here. A gated repo needs `HF_TOKEN` in the environment. A repo with `genai_config.json` beside its ONNX file is an ONNX
Runtime GenAI chat model: it lands in `AMP/models/text/<name>`, where `initMod("<name>")` answers with it.

### run

```
./amie run train.kts                               the same as ./amie train.kts
./amie run "Ask the dummy model"                   a starter or a template by its title
```

### examples

```
./amie examples                                    the starters by group: what amie can do, each ready to run
./amie examples "Ask the dummy model"              prints one
./amie run "Ask the dummy model"                   runs it
```

The starters are the scripts the app's Examples menu holds. The first `examples`, `templates` or `run` by title seeds
them into `AMP/templates` as the app does (tagged `starter` and with their group), with what they run on beside them:
a model called `dummy` and `sample-intents.csv`.

## Tests

`./gradlew :amie:test` runs them against `build/test-amp`, never your AMP folder: the command line, the prompt's
reading of lines and blocks, one script through the interpreter.

## How it is made

amie is a module of this repository, `amie`. The script engine, the models, the groups, the packages,
the envs, the templates, the Hub imports and the model server live in the engine module it depends on
(`runtime`), reached through one facade there, `HeadlessAmie`: the script runtime for a program with no
window. Nothing of the app's window is on its class path: it holds the Kotlin scripting host and compiler, coroutines
and serialization, ktor's client (the Hub, the package store, the backend), ONNX Runtime, DJL's tokenizers and engines,
JLine and slf4j-simple. Sources are under `src/main/kotlin/org/amie/cli`:

| File | What it holds |
|---|---|
| `Main.kt` | The entry point and the exit codes |
| `CommandLine.kt` | What the command line asks for, and a command's flags |
| `Repl.kt` | The prompt: lines and blocks, its colon commands, JLine's editing and history |
| `ScriptRun.kt` | A script as `main.kts`, the wait for a model left serving |
| `Commands.kt`, `ModelCommands.kt` | The commands |
| `Output.kt`, `Style.kt`, `AmpFolder.kt`, `Banner.kt` | The console on standard output, its colors, the AMP folder, the greeting |
| `jdk.sh`, `install.sh` | The JDK 21 lookup, spliced into the built `bin/amie` and sourced by the launcher; the install onto the PATH |
