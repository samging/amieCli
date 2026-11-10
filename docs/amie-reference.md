# amie reference

## The command line

```
amie [options] [script.kts [args] | -c code | command ...]

  -c code          runs the code
  -i               the prompt after the script, with its values defined
  -e, --env name   the env to run in
  --amp folder     the AMP folder, over AMIE_AMP_DIR and the default
  -v, --verbose    the internal lines too
  -q, --quiet      no banner at the prompt
  --version, -V    the version and the license line
  -h, --help       this; amie <command> --help for a command
```

## The commands

| Command | Actions |
|---|---|
| `model` | `list`, `build <csv> --name n [--test csv]`, `validate <n>`, `metric <n> [--last] [--file]`, `serve <n> [--port]`, `info <n>` |
| `group` | `list`, `add <group> <models>`, `remove <group> [model]`, `open <group> [texts]`; `--env` for another env |
| `package` | `list`, `search [query]`, `add <jar>`, `maven <coordinate>`, `test <jar>`, `info <jar>`, `config [jar] [key=value]`, `setup <jar>` |
| `env` | `list`, `new <name>`, `use <name>`, `show <name>` |
| `template` | `list`, `new <name> [--project p]`, `show <name>`, `run <name>`, `edit <name>`, `save <name> <file>`, `delete <name>`, `export <name> --out <folder>` |
| `project` | `list`, `show <p>`, `new <p> [templates]`, `add <p> <templates> [--at n]`, `remove <p> <template>`, `run <p>`, `edit <p>` |
| `session` | `list`, `runs`, `terminate <name \| #id \| all>` |
| `hf` | `search <query> [--format] [--task] [--limit]`, `info <repo>`, `import <repo> [--model-name]` |
| `run` | `run <file> [args]` |
| `docs` | The book, [Learn amie](learn.md), offline in the browser; `docs path` prints where it is |

## The script API in brief

| Call | What it does |
|---|---|
| `log`, `info`, `error`, `data` | A console line at that level; lists, maps and answers printed as such |
| `source`, `relate` | A labeled csv; a test file for it. `log(source("x.csv"))` or `.info()` says what it holds, as pandas' `df.info()`: columns, their types and roles, labels and their rows |
| `modelBuilder`, `buildOptions` | Trains and exports an intent model, with options |
| `fineTune`, `calibrate`, `versions`, `rollback` | Teaches a model more, makes its scores honest, the heads it had |
| `validateMetrics`, `confusionMatrix`, `mistakes`, `suspects` | The stored validation as tables |
| `initMod`, `sessionMod` | A session of a model, a package or an imported model; one for a block |
| `group` | The models of a group asked together |
| `packages` | The package jars: what the repositories offer, a download, a jar's check, metadata and tests; `packages.config` and `packages.setConfig` read and set its config files |
| `huggingFace`, `importModel` | A model of the Hub; one by its path. An encoder and decoder pair opens as `.encoder` and `.decoder` ([pairs](learn.md#pairs)) |
| `serve`, `stopServing` | A model over HTTP |
| `ortClosable`, `ortStatic`, `ortRequired`, `ortLimited`, `ortAsync` | How long what a block opens lives |
| `quietly` | A block without the console line of each model answer, to measure a model on thousands of texts |
| `database`, `shell`, `shellScript` | Postgres; the login shell |
| `implementation`, `environment` | A Maven package into the env; the env to run in |
| `coroutineTemplate`, `template`, `input`, `output` | A template in the background, and the two ends of its conversation |
| `handleBy`, `errorLog` | Running on after an error; what the text model makes of one |

`:api` at the prompt has every function with its signature, and the [Scripting API](sandbox-scripting.md) has the rest.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | The script finished, or the command did what it was asked |
| 1 | The script failed, or the command couldn't |
| 2 | A command line amie can't read |

## Inside amie {#inside}

amie is a Gradle module, `amie`, and reimplements nothing: the script engine, the model groups, the packages, the envs, the templates, the Hub imports and the model server live in the engine module it depends on, `runtime`, reached through one facade there, `HeadlessAmie`, the script runtime for a program with no window. No UI toolkit is on its class path.

The sources are short. `Main.kt` is the entry point and the exit codes, `CommandLine.kt` reads the command line, `Repl.kt` is the prompt, `ScriptRun.kt` runs a file as `main.kts`, and `Commands.kt`, `ModelCommands.kt` and `Learn.kt` are the commands. `jdk.sh` is the JDK 21 lookup, spliced into the built `bin/amie` and sourced by the launcher, and `install.sh` the install onto the PATH. The tests run against a folder of their own, never your AMP.

amie is free software: you can redistribute it and modify it under the GNU General Public License, version 3 or any later version. Its text is the LICENSE file at the root of the repository.

