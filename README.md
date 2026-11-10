<img width="1756" height="662" alt="gitImg" src="https://github.com/user-attachments/assets/eba3d2c0-9d15-42f0-9058-6af7adf9d311" />

An interpreter for Kotlin scripts that train, ask, group and serve intent models, at a terminal. It is the
script engine of the amie pilot desktop app without its window: the same `main.kts` functions (`initMod`,
`group`, `modelBuilder`, `database`, `log`, ...), the same AMP folder, no Compose on its class path.

Free software under the GNU General Public License, version 3 or later: see [LICENSE](LICENSE).

## Build and install

A JDK 21 is needed to build; `amie` finds one on its own when it runs (`AMIE_JAVA_HOME`, `JAVA_HOME`, the
Mac's `java_home`, Homebrew, sdkman, `/usr/lib/jvm`).

```bash
./gradlew :amie:installDist      # the distribution under amie/build/install/amie
amie/install.sh                  # builds and installs it: ~/.local/lib/amie, ~/.local/bin/amie
bin/amie --version               # from the checkout: builds when the sources changed, then runs
```

`amie/install.sh --prefix /usr/local`, `--no-build` and `--uninstall` are the other forms.

The intent models need the encoder models: `scripts/setup.sh` downloads them, and `AMIE_MODELS_DIR` points
at another copy.

## Use

```bash
amie                             # the prompt: lines of script, kept from one to the next; :help lists its commands
amie script.kts                  # runs the file as main.kts
amie -c 'log(1 + 1)'             # runs the code given
amie new weather                 # a project: AMP/projects/weather with a main.kts, in an env of its name
amie run weather                 # runs the project's main.kts
amie example                    # the starter scripts by group; amie run "<title>" runs one
amie --help                      # the options; amie <command> --help for a command's actions
```

The commands: `run`, `new`, `example`, `model`, `group`, `package`, `env`, `template`, `project`, `session`, `hf`
(Hugging Face) and `docs`, the learn book. Everything amie reads or writes is in the AMP folder: `--amp`, else
`AMIE_AMP_DIR`, else the user's data folder (`~/Library/Application Support/amie pilot/AMP` on a Mac).

[amie/README.md](amie/README.md) is the full guide: every command line, the prompt, the scripting functions, the
exit codes. `amie docs` opens the learn book, chapter by chapter from the first line to a model served.

## Layout

```
amie/        the interpreter: the command line, the prompt, the commands (module :amie)
runtime/     the engine: the script runtime, models, groups, packages, envs, templates, serving (module :runtime)
scripts/     setup.sh, which downloads the encoder and voice models into the AMP folder
bin/amie     builds and runs amie from the checkout
docs/        the documentation written for the desktop app; docs/sandbox-scripting.md is the scripting guide
voice/       the voice scripts: talk to amie through its intent models and a text model
```

The desktop app itself, with its Compose window, lives in its own repository and depends on the same `runtime`
module. amie was split out of it in November 2026.
