# The sandbox

The sandbox is the screen the app opens on, laid out as a JetBrains IDE: a rail of icons at the far left, the Kotlin
script editor in the middle, and tool windows round it. It uses the IDE's fonts (JetBrains Mono for code, Inter for
everything else) and its dark colours, and the Kotlin colours in the editor.

**The rail** holds the pages (Sandbox, Models, Settings) and the tool windows (**Templates**, ⌘1, and **Console**, ⌘4;
Ctrl elsewhere). Each opens and closes on its own, and they stand side by side: nothing closes one to show the other, and
a run leaves the templates open. A closed console keeps its output and the templates keep what was typed in them.

**Docking.** Templates start at the left and the console at the bottom. Drag a window's header to the left, right or
bottom edge to move it there, a blue area shows where it lands, or right-click the header and choose a side. Two windows
on one side share it. Drag the line at a dock's edge to size it. Sides and sizes are kept in `AMP/settings.json`.

The console starts hidden, since there is nothing to show, and opens by itself at the first line a run logs (one the
level toggles let through). It holds what the script logs and nothing else: the run's own lines (`Running
pluginDir/main.kts`, `Finished in 0s` and the `Resources:` line) are at the **internal** level, off at start, since the
strip over the console can say the same; a script that logs nothing leaves the console closed. It can be hidden at any time, output or not; it stays hidden for the rest of that run, and the
first line of the next run (which empties it first) brings it back. It has three tabs: **Console** with the output and
a line for commands, **Recent runs**, and **Still open**. The Console tab starts with two muted lines, as Python's
interpreter greets: `Amie pilot 1.0.0 (default, Nov 06 2026, 02:23:55) [Kotlin 2.1.0, JDK 21.0.11] on darwin`, then
what to type there; the `>>>` prompt follows on the line after the output, as in a terminal: the console reads from the top
down, the prompt going down with each line until the pane is full, and the pane scrolls from there. A click under the
prompt puts the cursor in it, and typing while scrolled up goes back down to it. The prompt shows no words, only a
block cursor that blinks while it has the keyboard (an outline while it hasn't); what it is for is in the tooltip of
`>>>`, and Enter sends the line. The two greeting lines are no log line, so Clear and a run leave them. The run's status and figures in the strips are **Run
details** on the Settings page, off by default: off, the strip over the console has only its level toggles, Find,
Details and Clear, and the steps under [Running](#running) that name the status are what it shows when on. With the
console hidden the run's status (`running 12s`,
`Finished in 41s`, `Failed after 3s` with the error in its tooltip, and the last `modelBuilder` build's phase and time)
moves up into the bar over the editor. Before the first run there is no status at all, unless a build has ended, whose
outcome stands in its place; the other controls keep their places either way.

## The editor

Indentation is drawn: a thin vertical line stands at every four leading spaces, so the lines of a block are joined
down its left edge, across its empty lines too; the guide of the block the caret is in is lit. The editor keeps
the indentation for you: **Enter** starts the new line at the current line's depth, one level deeper after `{`, `(`
or `[`, and between `{` and `}` it puts the closing brace on its own line below the caret. Enter also re-indents the whole
script as it goes: every line to its depth in braces and parentheses, continuing lines (`.map`, `?.let`, `?:`, `&&`, `||`)
one level in, the inside of strings and comments left alone, and the caret on the new line at its depth. *re-indent on
Enter* in [Settings](settings.md) (**Editor**) turns that off; Enter then only starts the line. **⌘⇧F**
(Ctrl+Shift+F elsewhere) re-indents the whole script at any time, the caret staying where it was in its line.
**Tab** inserts four spaces, or indents every line of a selection; **Shift+Tab** moves them back.

- There is one script and it is always `AMP/pluginDir/main.kts`, named at the left of the window's top bar, which is
  the sandbox's toolbar; it is saved as you type, so nothing says it was. The right end reads as IntelliJ's run
  toolbar, every button flat and of one size. First the **env**, where the IDE has its run configuration: a small box,
  the env the script is sourced in and a chevron (`default` unless the text says `environment(SettingsEnv("name"))`);
  choosing another from its list writes that line into the script (or rewrites the one there, keeping its profile) so
  the choice is in the text. Then **Run**, an outlined green triangle (an outlined red square while the script runs,
  which stops it); and **export**, an outlined arrow out of a tray: the script as a folder that runs with plain `java`,
  **⌘E**, Ctrl+E elsewhere; it opens the Templates window on the export, see [Exporting a script](#exporting-a-script).
- The file is saved as you type (a third of a second after the last keystroke). You can edit
  `AMP/pluginDir/main.kts` with anything else too; the editor loads it at start.
- **Highlighting** in a calm palette: keywords a steel blue, strings sand, numbers gray-blue, comments gray, and
  the sandbox API functions (`source`, `modelBuilder`, `metricsChart`, …) in the one accent, a muted teal, so you
  can see at a glance what talks to the app. The editor reads the strings and comments itself, so a `//` or a `#`
  inside a string (`source("https://…")`), an apostrophe (`"don't"`), an escaped quote, a `${…}` template or a raw
  `"""…"""` string ends where it ends in Kotlin, and the lines after it keep their own colours.
- **Current line**: the line the caret is on sits in a faint box that runs across the gutter and the text, and its
  number in the gutter is bright. Every number in the gutter stands on the baseline of its line.
- **Long lines** don't wrap: the editor scrolls sideways, as wide as its longest line, and is as wide as its pane
  again once that line is gone or the pane is wider. The text keeps its margin from the gutter and from the top
  edge when the editor takes the keyboard, however long the script or its lines: the first letter of every line and
  the first line stay in sight.
- **Folding**: every `fun`, `class`, `object` or `interface` whose line ends with `{` has a small arrow in the
  gutter. Click it, or press **⌘-** (Ctrl+- elsewhere) with the caret anywhere in the block, and the block folds
  down to its first line: `fun greet(name: String) { … }`. Click the arrow or the `…`, or press **⌘=** (Ctrl+=),
  to open it again; with **Shift** the two shortcuts fold or open every block. Typing into a folded block, or
  moving the caret into it, opens it, so nothing ever happens out of sight. Folding changes nothing in the file:
  saving and **Run** always see the whole script, and a fold stays in place while you edit other lines.
- **Completion dropdown**: start typing a name and a list of matching API functions opens under the caret with the
  signature and a one-line description. **Up / Down** move, **Enter** or **Tab** insert the call and put the caret
  between the parentheses, **Esc** closes it, **Ctrl+Space** opens it on demand. A function that needs a block goes
  in with its braces (`sessionMod("") {  }`, `modelBuilder("") {  }`), the caret in the name, or in the braces when
  the block is all it needs. After `m.` for an `m` that `sessionMod` or `initMod` gave, the list is what that model
  can be asked (`predict`, `scores`, and `version`, `labels` on a `sessionMod`); inside a `sessionMod("name") { }`
  block, also in a lambda in it, those come first.
- **Marks as you type**: a moment after you stop typing (a third of a second), the editor checks the calls to the
  script API, without running or compiling anything. A mistake gets a red wavy line, and its line number a red dot;
  rest the pointer on either and a short message says what is wrong. It marks a call with too few arguments
  (`ortLimited("x") { }`: *ortLimited needs max*) or too many, a named argument the function hasn't
  (`log("x", levle = …)`: *log has no parameter levle. Did you mean level?*), a literal of the wrong type
  (`initMod(12)`: *initMod takes a String, not a number*; `learningRate = 1`: *write 1.0, not 1*), and a member that
  `amie`, or a model taken with `val m = initMod(…)` or `sessionMod(…)`, doesn't have (`amie.lgo(…)`). A call that
  fits any of a function's overloads is right, and a block after the call counts as its last argument. Only what is
  certain is marked: a variable or an expression passed is never judged, nothing inside a string or a comment is
  looked at, and a name the script declares or imports itself is left alone, as is a call inside a block whose
  receiver may have a function of that name (`apply`, `with`, a class with a supertype). The marks follow your edits
  until the next check; one you type into goes until then.
- The API is available **without a prefix**. `log("hi")`, `source("x.csv")` and `modelBuilder("name", data)` are the
  same as `amie.log(...)`, `amie.source(...)` and `amie.modelBuilder(...)`; both spellings work in the same script.
  Only `amie.import` has distinct names without the prefix: `implementation("group:module:version")` (or
  `dependency(...)`) for a package and `environment(SettingsEnv("name", "profile"))` for an environment, because
  `import(...)` without `amie.` reads as a Kotlin import statement. **Tab** in the editor inserts four spaces.

## Templates

The bookmark on the rail (⌘1) opens the Templates window, which says how many templates run in the background, if any. **Keep as a template**
folds the save form away so the list comes first. **New template** at the top, or the **+** beside the `main.kts` tab
over the editor, which opens the window too, starts a new script: the editor is
emptied (what it held is kept under **Previous scripts**, as a load keeps it) and the save form opens with the cursor in
**Name**; write the script, then **Save**. A second **Load** within a second of another is ignored: a double click, or
a click on the row the first load moved under the pointer, would put back the script just replaced. Saving the script already in the editor is **Keep as a template** itself. Closing the window keeps what it held,
a half-typed name included. It is where scripts are kept to start from again, in
`AMP/templates/` (`<id>.kts` the code, `<id>.json` what is known of it):

- **Save** keeps the script in the editor under a **Name** (the form says so when that script is already a template's,
  unchanged, since saving it would only make a copy), with an **About** line and **Tags** (commas between
  them; tags are kept in lower case, each once). The metadata kept with it: where it came from (`main.kts`, a previous script, or *starter*), its line
  count, the env it sources itself in, what it opens (`model x`, `group x`, `package x.jar`, read from its
  `initMod`/`sessionMod`/`group` calls, comments aside), when it was made and saved, and how often and when last it was loaded.
  A name already kept is **Replace**d: the same template, made again, its creation time and loads kept.
- **Templates** lists them, the last saved first, one line each: the name, then Load, Run in background and ⋯ as
  icons. The name's tooltip has its about line, its tags and those facts. **Load** (the open-file icon) puts one in the editor, ready for **Run**; the script that was there is kept under
  `AMP/pluginDir/backup/` first. **Run in background**, the play icon, runs it beside what you are doing, the editor staying as it is
  ([below](#templates-in-the-background)). Its **⋯** has Load, **Export…** ([Exporting a script](#exporting-a-script))
  and **Delete…** (then the red Delete). A search field filters
  by name, tag, about or what it opens once there are more than six. A load says nothing in the console, the editor
  shows it; only a script that could not be kept says so. Its files are written off the window's thread.
- **Previous scripts** are those backups, the newest twenty first: **Load** brings one back (what was in the
  editor is kept in turn) and **Keep as template** fills the save form with it under the name `Script of <time>`,
  to rename and save; **Not this one** goes back to saving the editor's script.

The first time the pane's folder is made it is seeded: every starter script (log at every level, ask the dummy
model, a confidence gate; train a model from a csv, train with your own heads, fine-tune it on a new label, search
heads cross-validated, which texts to label next, read a model's metrics, named entities, embeddings, a group of
models asked together, a model to ask and nothing more, `sessionMod` as a block; Postgres; serve a model and call
it; the sessions that are open, the scope functions, what a script uses, a template in the background and the reader it talks to, templates you call like functions, what to do when something goes wrong), tagged `starter` and its
group, and the script `main.kts` held then, as *My script*. That happens once (`AMP/templates/.seeded` says so). A
starter the app gains later comes once to a folder seeded before it (`.seeded-level` says how far it got), unless a
template already has its name; a starter deleted stays deleted. A starter that gained a new title comes the same way:
the template under its old title is renamed and brought up to date, keeping its id, times and loads, as long as it is
still the starter (never saved over); one you saved over is yours and is left alone, and the starter comes beside it
under its new title. None of the starters needs an account or a server; the longer versions, with the explanations,
are in the [Cookbook](cookbook.md).

### Cloud save

Saving here is always the first thing that happens, and it works the same logged in, logged out and with the server
down. Under the save form the pane says what the cloud does with it:

- **Logged out** it says *Saved here only* and offers **Log in to save to the cloud**. That opens the log in page (or
  Create account) and comes back to the sandbox with the pane as you left it. Nothing is sent anywhere while you are
  logged out.
- **Logged in** it says *Logged in as …*, and after each save one of three plain lines: *Saved here. Saving to the
  cloud…*, *Saved here and to the cloud.*, or *Saved here. Not saved to the cloud:* followed by why (the server can't
  be reached, the login was not accepted, the cloud takes 20 saves a minute, a file is too large). A failed send loses
  nothing and blocks nothing: the file stays saved here and goes up on your next save, or at your next login.

What goes up is exactly your templates (`AMP/templates/<id>.kts` and `<id>.json`), `AMP/settings.json` and
`AMP/pluginDir/main.kts`, the same allowlist as the [backup](settings.md#backup). `session.json`, `keycloak.json`,
`databases.json`, `github.json`, tokens, models and logs are never sent, nor is anything else in `AMP`. Saves are
gathered for two seconds and sent from a background coroutine, so typing in the editor is never waiting for the network.
The starter templates the app ships are the same on every Mac and are not sent until you save over them.

**At login** (and when the app starts with you still logged in) what your account has and this Mac doesn't comes down:
templates and `main.kts` are written, `settings.json` applies at once, and a `main.kts` already open in the editor is
replaced by the one from the account. Each file is compared with the version both sides had at the last sync
(`AMP/cloud-sync.json`, hashes only, kept here):

- only the account changed it: it comes down in place, and the script it replaces is kept under `AMP/pluginDir/backup`;
- only this Mac changed it: it goes up;
- both changed it, or this Mac never synced it and the two differ: **both are kept**. The newer stays in place, the
  other is saved as `<name>-cloud-<time>.<ext>` or `<name>-this-mac-<time>.<ext>` under `AMP/pluginDir/backup`, where a
  script shows among *Previous scripts* and **Keep as template** turns it back into a template. The pane's line names
  the file. Nothing is overwritten without a copy.

Deleting a template here does not delete it in the cloud, and it does not come back at the next login. `AMIE_CLOUD_SAVE=off`
turns cloud save off for a run. The cloud's side is described in [Amie backend](backend-integration.md#cloud-save).

The editor's text size is in [Settings](settings.md) (**Editor**), and **⌥⌘+** / **⌥⌘−** / **⌘0** (Ctrl+Alt and
Ctrl elsewhere) inside the editor make it a point larger, smaller, or the default 14pt. ⌘+ and ⌘− without ⌥ fold
and unfold (see the keyboard summary).

### Templates in the background

Several saved templates can run at once, beside the script in the editor. **Run in background**, the play icon on a
template's row, starts it now, without input; the editor stays as it is and you can go on working. The pane's strip
counts what runs (`AMP/templates · 17 kept · 2 running`). A script starts one with `coroutineTemplate("name")`, and can then give it
input and read what it answers: the calls are in
[Templates in the background](sandbox-scripting.md#templates-in-the-background). **Run** is not involved: a run in the
background doesn't dim it, and the strip's `Running 12s` stays that of `main.kts`. At most eight run at once, fewer on
a small heap.

Before a template starts, memory is checked, whether the play icon, `coroutineTemplate` or `template` starts it:

- The heap has to have 64 MB free, what a run's engine and a small script hold.
- The machine has to have that free as well, plus the size of the encoder when the template opens a model and that
  encoder isn't loaded yet. An encoder loads outside the heap, so it counts against the RAM and not the heap.
- Before refusing, the app collects the heap's garbage and asks the system what it could give back, so a figure that
  only looks short doesn't stop a run.

What falls short is refused with a console line and, on the pane, in red under the row:

```text
Not started: 40.0 MB of heap free, a run needs 64.0 MB; stop a template or free a scope
Not started: 300.0 MB of RAM free, a run needs 464.0 MB with the encoder; close a session or another program
```

The check looks at the moment of the start, so several started within the same second don't see what each other is
about to take.

**While they run**, a light watch keeps to it. It is a thread that exists only while a template runs in the background,
and looks once a second at the free heap, a few calls on the runtime. When it has been under 32 MB (half of what a run
needs to start) three readings in a row, the heap is collected and read once more, since garbage makes it look fuller
than it is; if it is still short, the newest template is cancelled, with a console line in red that says which and why:

```text
Reader: stopped, because only 24.0 MB of heap is free, under the 32.0 MB the templates in the background need to go on. It is the newest of 3; if memory stays short the next newest is stopped too
```

One at a time: the next waits until that one has ended and memory has stayed short again for three readings, so what a
stopped run gives back is seen first. `main.kts` is never cancelled for this; while it runs it gets a warning line,
once each time memory runs short (`Memory is short: only 24.0 MB of heap is free ... main.kts is not stopped for it`).
A run that grows past the heap's limit outright is the JVM's to stop: its console line at the end says the peak.

**Running in the background**, the fold over TEMPLATES while there is a run to show, lists them: what runs first, the oldest on top, then the last
three that ended. Each row has the template's name, how it stands (*Running*, *Finished*, *Failed* with the reason in
its tooltip, *Cancelled*), how long it has run and, while it runs, **Stop** and a line of its own figures: what its sessions hold, what its thread
allocated and its cpu time (`holds no sessions · allocated 878.3 MB · cpu 1.39 s`), so rows side by side differ with what
each does. **Stop** also ends what that run started. A blocked `input()` or a sleep ends at
once. A busy script ends at its next `log`, `input` or `output`; until then its row reads *Stopping*.

**In the strip at the foot of the window.** While anything runs, the strip's left end says how many (`2 running`, `main.kts`
counted when it goes). Click it for a small panel over the strip: one line for each, with its name, its own figures and
how long it has gone, and **Stop** for that one alone (**Stop** on the `main.kts` line also ends the templates it
started): `holds 416.5 MB · allocated 878.3 MB · cpu 1.39 s · 5s`. The figures are the run's own, not the app's:

- **cpu** is the CPU time of the thread its script runs on, and **allocated** the bytes that thread allocated, which is
  what it churned through, not what it keeps. Both are kept after the thread ends, so nothing is lost when a run moves
  to another thread. A thread the script starts itself is not counted. For `main.kts` they include its compile; a
  template's count from the moment its script runs.
- **holds** is the weight of the sessions the run opened that are still open, as the files they loaded (the app's own
  estimate, since ONNX Runtime doesn't say what it keeps): a model's head, a package's jar, an ONNX file, and the encoder
  they run on. An encoder shared with another row is counted once, in the first, and the other row says
  `+ shared encoder`. A run with none open says `holds no sessions`.

The Templates pane's rows under **Running in the background** show the same figures. They are read off the UI thread,
every second while something runs and every two otherwise, from two calls on the thread bean and the sessions the run
opened. The strip's ✕ hides it, the panel with it, and **Memory** in the sidebar brings it back.

A run's console lines start with the template's name (`Reader: started in the background`, `Reader: finished in 3s`).
A failed one's name the template's own file and line, `reader.kts:3: java.lang.IllegalStateException: boom`, without
the underline of a failed `main.kts`, since that file isn't in the editor.

Every run has a row on the Models page's **Sessions** tab too, of the kind *background template*: **Terminate** cancels
it and what it started, and **Terminate all** cancels them all. A run started by `coroutineTemplate` outlives a script
that ends by itself; **Stop** on the script ends everything it started, nested runs included. A value of `template` is
closed when its script ends. Runs end with the app, which closes as it always does with a run in progress.

A run stays in the env that is current when it starts and changes nothing of it. A template that names another env, or
imports what this env doesn't have, is refused, and **Run in background** says why in red under its row: load it and **Run**
it, or run `main.kts` in that env first. A template that names no env runs in the current one.

## Exporting a script

The **export** button in the window's top bar (**⌘E**, Ctrl+E elsewhere), **Export…** on the Templates pane's
strip, and **Export…** in a template's **⋯** for that template, make a folder that runs the script with plain `java`:
no amie pilot, no Gradle, no window. A script does the same with `exportJvm("main.kts")`
([Scripting API](sandbox-scripting.md#the-api)). So does the command line, without opening the app:

```sh
./gradlew :desktopApp:exportScript --args="main.kts --out /some/new/folder"
./gradlew :desktopApp:exportScript --args="main.kts --out /some/new/folder --template reader --script"
```

`main.kts` can also be a template's id or a file. `--template` carries a saved template besides those the script
starts (as often as needed), `--script` chooses the script mode below, `--plan` only says what would go in, and
`--allow-missing` writes the bundle although something could not be included, and `--no-jars` leaves out the env's
Maven jars and the package jars: `require.txt` in the bundle lists each with the path to put it at, `run.jar --check`
says which are still missing, and a run fetches a missing Maven jar from Maven Central when it can reach it, while a
missing package jar stops it. A packaged app takes the same arguments after `--export-script`.

The pane shows what the script reaches before anything is written: the env, models, encoder, groups, packages,
databases, data, the Java it needs and the size, what a deployer has to supply (**To supply**), and what could not be
included (**Can't include**), which stops the export until it is dealt with. **Export anyway** writes it regardless and
says so in its README and `bundle.json`. Once written, the pane says where the bundle is, how big it came out, and the
exact `java -jar` line that starts it, with **Copy** beside it; **Show it** opens the folder.

**Two modes.** **Compiled**, the default, compiles `main.kts` and every template going in to classes with the same
compiler **Run** uses, and puts them in `lib/scripts.jar`: the bundle carries no compiler, starts without compiling,
and runs on Java 21 or newer. **Script** carries the text and the Kotlin compiler instead; `AMP/pluginDir/main.kts` can
then be edited in the bundle and compiles when it starts, which takes some seconds, and the compiler stops on Java 25,
so that bundle needs Java 21 to 24. A compiled bundle keeps the script's text too, for reading; editing it changes
nothing until the script is exported again. A script that doesn't compile is refused with its lines, and nothing is
written.

**Templates.** The pane lists every saved template. Those the script calls, with `template("name")` or `coroutineTemplate`, are checked and
stay so, since the script needs them; any other can be checked to go in too. Each template in the bundle is an entry
point of its own: `java -jar run.jar --template "name"` runs it instead of the script, `--list` says what the bundle
can run, and the templates call each other with `template` or `coroutineTemplate` through the bundle's own background runs, as in
the app and without it. `--template` gives a template no input: its `input()` is null at once and the run says so. A
template still waiting for input when the script has ended is told the same, its input is closed, and the bundle ends
instead of waiting for good; send its lines before the script ends.

**Why a folder and not one jar.** The encoder is over 400 MB and its native libraries have to be real files. `AMP/` has
to stay writable, since a script retrains models and writes logs there. A deployer has to be able to fill in secrets
and edit the database url. So a bundle is a folder, and `run.jar` is a thin jar naming everything in `lib/`:

| Path | What it is |
|---|---|
| `run.sh`, `run.bat`, `run.jar` | Start it. Both check the Java version first; `java -jar run.jar` is the same thing |
| `lib/scripts.jar` | Compiled mode: the script and its templates as classes (`Main`, `Template_<id>`), loaded over the env, so `run.jar` doesn't name it |
| `lib/` | Compiled: the Kotlin runtime, coroutines, serialization, ONNX Runtime and the tokenizers for this platform only, the amie runtime's own classes cut down to what a run reaches, the package jars, the Postgres driver when the script opens a database and the Ktor client when it saves state on the server. Script: the app's jars less the UI, TensorFlow and the Gradle API, the script engine among them, with the native libraries of every platform |
| `AMP/` | What the script works on, laid out as here, with `pluginDir/main.kts` and `templates/`. In script mode, edit the script there and run again |
| `models/bert/` | The encoder a model uses, and the named-entity model if the script opens it |
| `secrets.env` | What the deployer fills in, blank; an environment variable of the same name wins |
| `bundle.json` | The manifest: where it came from, the mode, the classes, what went in, each file's size and SHA-256 |
| `README.md` | How to run and deploy it and what to supply, written for this script |

**Size.** Measured on an Apple Silicon Mac with Java 21, the same script exported both ways:

| Script | Mode | Bundle | `lib/` | `java -jar`, start to exit | Peak memory |
|---|---|---|---|---|---|
| Calls two templates, no model | Compiled | 20 MB | 20 MB | 0.3 s | 87 MB |
| | Script | 189 MB | 189 MB | 4.3 s | 430 MB |
| Scope functions starter, dummy model and `bge` | Compiled | 436 MB | 20 MB | 2.9 s | 1.1 GB |
| | Script | 606 MB | 189 MB | 6 to 10 s | 1.3 GB |

The encoder is about 420 MB in both modes. In a compiled bundle's `lib/` ONNX Runtime and the tokenizers, with this
platform's natives, are most of it; a script bundle's also holds the compiler, its scripting jars, Ktor and the natives
of four platforms. `run.jar` itself is a few KB: it names what is in `lib/`, so keep the folder together.

A run ends with the same `Resources:` line as in the app, on standard error so that standard output stays the script's
own; each template's run says its own among the script's lines.

`./run.sh --check` says whether it can run, without running it: Java, the jars and models, native libraries, each
secret, and that the classes load (compiled) or the script compiles (script). What the script prints goes to standard
output and its errors to standard error, every level goes to `AMP/logs.txt`, and the exit code is 0 when it finished,
1 when it failed and 2 when it could not start. `--levels info,data,error,internal` chooses what is printed. A script
that leaves a model serving keeps the process up until it gets Ctrl-C or SIGTERM. With `AMIE_AMP_DIR` set the bundle
works in that folder, filling in what it lacks from its own `AMP/` and always taking the script, so a read-only image
can keep its data on a volume.

**What goes in** is read from the script's text. Names come from the calls of the script API, and from a `val` holding
the name. A name known only when the script runs stops the export, with its line, until a comment says what it will be:

```kotlin
// export: model intents
// export: file data/extra.csv
initMod(pick())
```

The kinds are `model`, `group`, `package`, `database` and `file`. A model takes its folder without what it was asked:
`sessions.log`, `incoming.jsonl`, the access logs, `dropped.csv`, plots and icon stay here. An env goes whole, as a Run
sees it, so nothing is downloaded. Any other string of the script that names a file is carried too; one under `AMP/`
keeps its place and one from elsewhere goes to `AMP/files/`, with the script pointed at it when it runs. A file a
data call needs and that isn't there stops the export; a string that merely looks like a path doesn't.

**What stays out.** `session.json`, `keycloak.json`'s client secret, `github.json` and anything named like a credential
are never copied, even when a script names one, and neither is a package's configuration file that holds something
like a key. A password in `databases.json` or in its url, and a model's serving key, become `$ENV:NAME`, listed in the
README and in `secrets.env`: `AMIE_DB_<NAME>_PASSWORD`, `AMIE_SERVE_<MODEL>_KEY`, or the name the setting already had.
Only the connections the script names go in; a script that names none and lists them takes them all. A script with
what looks like a password in its text stops the export, since the script goes in as it is. `saveState`, `loadState`
and `listStates` need a signed-in user, and a bundle has no browser: it takes `AMIE_REFRESH_TOKEN`, an offline token,
or `AMIE_ACCESS_TOKEN`, used until it expires, and writes no `session.json`.

**What it runs on.** Java 21, a JRE being enough. A compiled bundle runs on any later Java too, and on the platform it
was exported from: ONNX Runtime and the tokenizers go in with that platform's native libraries only (macOS arm64,
Linux x64 or arm64, Windows x64), which the README names; for another platform, export there, or put the whole
`onnxruntime` and `tokenizers` jars from Maven Central in `lib/`, which hold every platform. A script bundle carries
all four platforms; 22 to 24 are let through but untried, and 25 is refused, since Kotlin 2.1's script compiler stops
on it. Native libraries unpack under `AMP/cache/`, so that folder can't be a `noexec` mount, as Docker's plain
`--tmpfs` is; `DJL_CACHE_DIR` moves them. Linux means glibc, so not Alpine: ONNX Runtime ships no musl build. An
Intel Mac has ONNX Runtime but no tokenizers library in the jar. `importModelTensorflow` doesn't work in a bundle, and
a script importing Compose, TensorFlow, KotlinDL, Groovy or the Gradle API is refused by name, since those jars stay
behind. A database other than Postgres fails as it does here, for want of a driver on the app's class path.

**Templates and shell commands.** A template the script calls with `template` or `coroutineTemplate` goes with it, with whatever
that template reaches, and the process stays until it has ended; one that fails after the script ended makes the exit
code 1. It has to run in the script's env, as it does in the app, so one that names another env or imports what the
env lacks stops the export; a template checked in the pane to go in besides is held to the same. In a compiled
bundle a path the export moved to `AMP/files/` is compiled in as that place under `AMP/`, which a data call finds;
in a script bundle the text is pointed at the real path when it starts. Other work a script leaves on a thread of its
own stops with the process, unless a model is being served.
`shell` and `shellScript` run on the machine the bundle is on: a file named to `shellScript` is copied, but what a
command calls can't be, so the commands are listed in the README's notes for the deployer. Nothing feeds a bundle's
own `main.kts`, so `input()` there is null, as on **Run**.

The lines the app writes for itself stay in `AMP/logs.txt`, as they stay hidden in the console until *Internal* is on:
metrics tables, charts, `sessions.print()`. `--levels` prints them.

## Running

1. Press **Run** (the green square with the play icon over the editor) or **⌘↵** (Ctrl+Enter elsewhere) inside
   it; they are the same. While it runs the square is red and stops the run (**⌘.** does too, Ctrl+. elsewhere); ⌘↵ pressed again only logs "Script is already running".
2. The console strip reads `Running 12s` with a moving bar under it, and under the status a line of figures,
   `memory 1.20 GB, peak 1.40 GB · heap 340.0 MB of 4.00 GB · cpu 2.10 s · ort 12 runs`, read every second from the
   same meter that writes the `Resources:` line at the end: the memory the process holds now and the most it held
   during the run, the heap in use of what it may grow to, the CPU since the start and the ONNX Runtime calls so far.
   So a script that grows is seen growing before it ends. When the run ends the line keeps its final figures, dimmed,
   until the next run starts; the whole reading is in its tooltip. While `modelBuilder` works the status shows the
   phase and step (`modelBuilder 'x' embedding 40/120 · 43%`).
   **A run belongs to the app, not to the page.** Go to Models, Settings or anywhere else while the script runs, to look at
   Packages or Sessions: it goes on, its lines keep arriving in the console, and the strip at the foot of the window
   says `1 running` with the script's own **Stop**. Come back to the sandbox and it shows the run still going, with its
   live status, and **Stop** works as ever.
3. The strip ends on `Finished in 41s`, `Failed after 3s` (the first error in its tooltip, and in the console) or
   `Stopped after 12s` when you stopped it; the console has the same line at the internal level.
4. Right after that line, for a failed run too, the console's internal level has what the run used: memory, heap, CPU
   time, ONNX Runtime calls and sessions (see [The output console](#the-output-console)). A session the script left
   open is named at the info level, in view.

**When a run fails** the console says where. Each thing the compiler got wrong is a line of its own that begins with
its place in the script, `main.kts:2:14: Type mismatch: inferred type is String but Int was expected`, an import
that doesn't resolve included (at its own line, wherever it stands). An exception names the line of the script it
came through, `main.kts:6: java.lang.IllegalArgumentException: …`: the innermost line of main.kts, so in a function
of yours the line that threw, and in an API call that threw the line that made the call. An error a script throws
is named the same way (`java.lang.StackOverflowError`, `kotlin.NotImplementedError` from a `TODO()`). The place is
underlined. Click it and the caret goes there: a folded block over it opens, the line comes into view and the editor
takes the keyboard. The error lines show even with **error** toggled off, since a failure turns it on. The warnings
the compiler gives with an error (`warning: Variable 'a' is never used`) are info lines: they don't fail the run,
and the run's status counts the errors only, `main.kts:2:14: … (and 1 more)`. An error with no place in the script
(an env that couldn't be sourced, say) reads `Error: …`.

What happens underneath: the script's dependency calls are read **before** it compiles and the packages are put on
the classpath (configuration phase); then the script runs with the Kotlin JSR-223 engine on a background thread
(execution phase). Details in [Scripting API](sandbox-scripting.md).

## Sessions: what stays open after a run

A script ends; what it opened does not. `initMod("name")` opens a **session** of a model (one per model, reused by
the next run), `initMod("name.jar")` a session of a package (a fresh instance per call), and both stay open until
they are closed in a script or terminated on the Models page, **Sessions** tab, where each one shows its runs and a
log of what it was asked and answered. So the usual loop is: open the model at the top of the script, ask it,
read the console, edit, Run again (instant, the model is already loaded), and close it when you are done.
`ortClosable { }` closes what its block opened when the block ends; `ortStatic("name") { }` keeps it until it is
freed, `ortRequired("name") { }` while anything else is in session, and `ortLimited("name", 3) { }` keeps the
three newest. All of it, with the pitfalls, is in [Running sessions](sessions.md).

`sessionMod("name")` is the other way to ask a model: a snapshot of its head, which can't train or change it, leaves
nothing in its log and goes with the run or the block. The same tab lists **Scripts** over the sessions: the scripts
running and those that left something open, with what each holds and has used
([what the figures are](sessions.md#see-what-a-script-uses)).

## The output console

By default a line is just its message, in monospace so metric tables and ASCII charts keep their alignment, and
coloured by its level: an error in the muted red, a line the app itself wrote in a dimmer gray, the script's own
lines in the plain text colour. The console follows the newest line until you scroll up; scrolling back to the end
re-attaches it. While it is empty it says how to run the script.

| Level | Shown as | Used for |
|---|---|---|
| **internal** | dim gray text | Lines the app itself writes: build phases, metrics tables, charts, session messages |
| **error** | muted red text | Failures, Keycloak and backend errors, script exceptions |
| **data** | plain text | Data your script logs with `data(...)`, file dumps |
| **info** | plain text | `log(...)` and `info(...)` from the script, run start and end |

The strip over the console carries, at its right end, the four levels as toggles with their counts (`Internal 12`,
`Error 0`, …; click one to hide or show its lines, **All** turns them all on again), the columns icon that
adds the time, the level and the source (`main.kts`, `sandbox`, `internal`) in fixed columns before every line, as
the log file has them, and **Clear**, which empties the view. Nothing is lost: everything the console ever showed
is appended to `AMP/logs.txt`. When the console is narrow the toggles move under the status instead of squeezing it.

It works like an IDE's terminal. **⌘F** (Ctrl+F elsewhere), or the magnifier on the strip, finds in the lines shown: it
starts on the newest hit, Enter goes back to the one before, Shift+Enter on, and Esc closes it. **⌘K** (Ctrl+L
everywhere) clears. Up and Down in the input line bring back what you typed. A copy over several lines keeps them on
lines of their own, and with the details on keeps the columns apart by a space. Right-click offers Find and Clear
beside Copy. A `main.kts:12` anywhere on a line goes to that line in the editor, a web address opens in the browser, and
a script's ANSI colours (the 16, the 256 and true colour) are drawn. Scrolled up, the button at the corner goes back to
the newest line.
Every control on the strip says in its tooltip what it is for, which way it is right now and what a click does: a
level's toggle reads *On: its 12 lines show. Click to hide them* or *Off: its 3 lines are hidden, the count still
moves. Click to show them*; the columns icon says whether details are on; **All** says how many lines wait behind
the levels that are off; **Clear** says how many lines it would empty, and that the next run empties the console
anyway.

When the app starts only **info** is on, so the console shows the script's own `log(...)` lines and the start and
end of each run. The counts on the other toggles still move: `error 1` means one error line is waiting behind the
toggle (a failed run turns **error** on), and the strip's status says `Failed after 3s` with the error in its
tooltip either way.

Every run ends by saying what it used, for a failed run too. The `Resources:` line is at the internal level, out of
view until **internal** is on (the strip's figures and their tooltip say the same); the `Still open:` line, a session
the script left open, is at the info level, so it shows from the start:

```
Resources: memory 1.20 GB, peak 1.40 GB, 180.0 MB more than before · heap 340.0 MB of 4.00 GB · cpu 2.10 s · ort 12 runs in 640 ms · sessions 3 opened, 2 closed, 1 still open
Still open: web-ample #3
```

| Figure | What it says |
|---|---|
| **memory** | What the process holds at the end, heap and native together (ONNX Runtime's memory is native) |
| **peak** | The most it held during the run, read about once a second |
| **more or less than before** | How the end differs from the start |
| **heap** | The JVM heap in use at the end, of the most it may grow to |
| **cpu** | CPU time used during the run, on all cores together, so it can be more than the run took |
| **ort** | ONNX Runtime calls during the run and the time spent in them |
| **sessions** | ONNX Runtime sessions opened and closed during the run, then every session open when it ended, of any kind and from any run. So with a session left from an earlier run, or a package jar, the last number is not opened minus closed |

The figures are for the whole app process, since the JVM can't split them per script: a served model answering
during the run counts too. A figure the platform can't give is left out, such as the memory on Windows, where there
is no `ps`. **Still open** is a second line only when sessions stay open, with their names and numbers as on the
Models page, **Sessions** tab, the scope in brackets when one holds them, as in `web-ample #3 (static search)`, and
templates still running in the background as `(background)`. The templates `main.kts` called with `template(...)` are
closed for it as it ends, so they are given a moment to end and are not listed; one started with `coroutineTemplate`
goes on by design and is. A run that stays up for more than five minutes, such as
a gateway script, is read every 30 seconds from then on instead of every second. The words `Still open:` are a link:
a click opens the **Still open** fold, below, where each of those sessions has a Terminate button.

**Recent runs**, folded under the console strip, keeps the last 20 runs: when each began (the time for today's, the
date for an older one), how long it took, and *Finished*, *Stopped*, or *Failed* with the first error (all of it in the
tooltip). They are kept in `AMP/pluginDir/runs.jsonl`, so they outlast the console, which each run empties, and the
app.

Each of those rows also says what its run used, in short (`1.20 GB memory · 2.10 s cpu · 12 ort runs`), with the
console's lines in the tooltip. A run kept from before this was added has nothing there.

### Still open: the sessions, right there

**Still open**, folded under Recent runs with its count, lists every session open now, of any kind and whichever run
opened it, with the same rows as the Models page's **Sessions** tab: owner and number, kind, the scope that holds it
(`forever search`), its runs and age, and **Terminate**, which closes that one as the tab does. A row a kept scope
holds has **Free** too, which frees the whole scope (`ortScopes.free("search")`), and **Free all** at the top does
what **Terminate all** does on the tab: every session is closed and every background template cancelled. The list is
read every two seconds while the console shows, so a session a running script opens appears as it does, and one the
script closes goes. A long list scrolls inside the fold, so the console under it keeps its room, and in a narrow pane
a row's name and details are cut short before its buttons are. With nothing open the fold says so. See
[Running sessions](sessions.md#close-sessions).

### The command line

The `>>>` prompt on the line after the output takes a line. A command runs on its own, without a script, and what it
did follows at the info level. The line itself is echoed first (`>>> free search`), so what was typed shows:

| Typed | What it does |
|---|---|
| `help` | lists these commands |
| `sessions` | lists every open session, as the Still open fold does, with the scope that holds each |
| `scopes` | what the kept scopes hold, as `ortScopes.print()` says it |
| `free search` | closes what the kept scope `search` holds, as `ortScopes.free("search")` does; says how many were closed |
| `free all` | closes what every kept scope holds, as `ortScopes.freeAll()` does |
| `terminate #3`, `terminate web-ample` | closes that session (by number or by owner), as Terminate on its row does |
| `terminate all` | closes every session and cancels every background template, as Terminate all does |
| `clear` | empties the console, as the Clear button does |
| `reset` | forgets what the lines of script typed here defined |
| `eof` | tells the script waiting at `input()` that no more is coming |
| `log("hi")`, any other line of script | runs in the background, with or without a script running; `terminate console` ends one that goes on |

Plain words that are no command are said to be none. While a script waits at `input()`, every other line typed is its
input, as in a Run window: it shows in green and the prompt's tooltip says so.

**The console remembers, as Python's interpreter does.** The lines of script typed here share one interpreter, so what
a line defines is there for the lines after it: `val x = 12`, then `log(x)` prints 12; a `fun`, a `class` or an
`import` stays too, and `val x = 5` later gives `x` a new value. A line that ends in a value prints it (`twice(x)`
prints `24`); a line that ends in nothing, or in a shell result whose output already showed, prints nothing more.
`reset` forgets everything, and so does a change of env, since the lines were compiled against the old one's packages.
A line waits for the ones typed before it, so they run in the order they were typed. A line of script needs no script
around it, keeps going while you type on or close the sandbox, and reads input from the console as main.kts does.

**A block over several lines.** A line that isn't whole yet waits for the rest, and the prompt turns to `...`:

```
>>> for (x in 0..3)
... {
...     log(x)
... }
0
1
2
3
```

It waits while a bracket, a `"""` string or a `/*` comment is open, while the line ends in an operator, a dot or a
comma, and after a header with no body yet: `for (…)`, `while (…)`, `if (…)`, `else`, `try`, `catch (…)`,
`finally`, `when (…)`, `fun f(…)`. It runs once it is whole. A blank line sends it as it is when no bracket is open
(inside braces it is a blank line of the block), and **Ctrl+C** drops it. Ctrl+C with no block going stops the lines of
script that are running, as `terminate console` does; with text selected it is the field's copy where Ctrl+C copies.

## Keyboard summary

| Keys | Action |
|---|---|
| ⌘↵ (Ctrl+Enter) | Run main.kts |
| Enter | New line at the current indentation, deeper after `{`, `(` or `[`; re-indents the script unless Settings turns that off |
| ⌥⌘+ / ⌥⌘− / ⌘0 (Ctrl+Alt+= / Ctrl+Alt+− / Ctrl+0) | Text a point larger, smaller, or back to 14pt |
| Tab / Shift+Tab | Indent or dedent the line, or every line of the selection; outside the editor, move between the strip's controls (the one with the keyboard shows a thin ring, Space or Enter presses it) |
| ⌘⇧F (Ctrl+Shift+F) | Re-indent the whole script (Enter does it too unless Settings turns it off) |
| ⌘- (Ctrl+-) | Fold the block the caret is in; with Shift, every block |
| ⌘= (Ctrl+=) | Open the folded block at the caret; with Shift, every block |
| Typing a name | Opens the completion dropdown |
| Up / Down | Move in the dropdown |
| Enter / Tab | Insert the selected function |
| Esc | Close the dropdown |
| Ctrl+Space | Open the dropdown |
