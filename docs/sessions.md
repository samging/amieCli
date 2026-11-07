# Running sessions

A **session** is a model or a package that the app holds open in memory so it can answer questions right away:
the ONNX head of an intent model with its encoder, one instance of a package jar in its own class loader, or a
bare ONNX Runtime session. A session is opened once, asked as often as you like with `inference(text)`, and stays
open until it is terminated: it survives the end of the script that opened it, the next run of the script, and
switching pages. The **Sessions** tab of the Models page lists every open one, with its log and a Terminate button, and
so does the **Still open** tab of the sandbox's console, where the console's `free` and `terminate`
commands do the same by name ([Close sessions](#close-sessions)).

This page is the practical guide: how to open a session, talk to it, keep it warm between runs, give it a scope,
read what it did, and close it. The function reference is in [Scripting API](sandbox-scripting.md), the page itself in
[Models page](models-page.md#sessions).

## The five kinds

| Kind | Opened by | How many | What `run(text)` returns |
|---|---|---|---|
| **Intent model** | `initMod("name")` in a script, **Open session** on the Models page | **One per model.** A second `initMod("name")` returns the session that is already open | `label:probability` lines, best first (`lights:0.91`, `weather:0.06`, …); `predict(text)` is the label alone, `scores(text)` a label → score map |
| **Package** | `initMod("name.jar")` | **One per call.** Every `initMod` makes a fresh instance of the jar's class in its own class loader | What the package's `run(text)` returned, one line per element; `answer(text)` the first. No `predict`/`scores`: a package has no labels |
| **Session model** | `sessionMod("name")` in a script | **One per head.** Everything asking for the same head of a model shares one snapshot, which goes when the last lets go | `label:probability` lines, from the head it was taken on |
| **ONNX session** | `importModelOnnx("file.onnx")` | One per call | The raw model's output; no log is kept |
| **Model group** | `group("name")` in a script, **Open swarm** on the Models page | **One per group.** A swarm over one session per model of the group (each the model's own session, reused if open); closing the swarm closes the sessions it opened | The labels merged over the models, each with its best score; `vote(text)` the label most models give, `each(text)` every model's own label, `ask(text)` every raw answer. Logged to `AMP/env/<env>/<group>.sessions.log`; a group with a model that keeps no texts logs only each text's length, there and in its models' logs |

The bundled NER model, `initMod()` with no name, is not a listed session: it is an ordinary object your script
owns, so close it yourself when you are done, or open it in a [scope function](#scope-functions).

A template running in the background is listed with them as a *background template*, though it answers nothing and has
no log: it is there so that it can be seen and terminated. See
[Templates in the background](sandbox-scripting.md#templates-in-the-background).

## Open one and ask it something

Every fresh `AMP/` folder has the **dummy** model (labels `greeting`, `lights`, `weather`), so this script works
before anything was trained:

```kotlin
val m = initMod("dummy")                              // opens the session, or returns the open one
log(m.predict("turn the lights on"))                  // lights: the predicted label
log(m.inference("turn the lights on").first())        // lights:0.61
m.inference("hello there").forEach { log(it) }        // every label with its probability
val scores = m.scores("hello there")                  // {greeting=0.65, weather=0.48, lights=0.36}
log(m.getStatus().toString())                         // the module manifest: encoder, dims, labels
```

`predict` is the label alone, `scores` every label with its score, `inference` the raw lines; all three are
described in [Reading a prediction](sandbox-scripting.md#reading-a-prediction).

Press **Run**. The console shows `initMod: 'dummy' session ready (…)`, then your lines; the strip over it says `Finished in 2s`.
Open **Models** (second sidebar icon) and the **Sessions** tab: `dummy  #1`, *intent model*, `2 runs`,
open for a few seconds. The session is still there: the script ended, the session did not.

Run the script again. This time the console says `initMod: 'dummy' is already open as session #1, that one is
used`, the run count goes up and no new session appears. A model has one session at most, on purpose: a script
run ten times does not pile up ten copies of the model in memory.

A package behaves differently, because packages are bottles with their own state:

```kotlin
val echo = initMod("amie-demo-1.0.0.jar")             // the demo package: a new instance every call
echo.run("hello").forEach { log(it) }
log(echo.getStatus().toString())                      // id, jar, class, source, url, page, runs
echo.close()                                          // releases the instance and its class loader
```

Without the `close()`, every run of the script leaves one more `amie-demo-1.0.0.jar #n` open under Sessions.
That is fine while you experiment (terminate them on the page, or `packages.closeAll()` at the top of the
script), but a script you run often should close the packages it opens.

Where the jar comes from: the active environment, then `AMP/pluginDir`, then a path, then the Amie server, which
stores it in `AMP/pluginDir` so the next open is local. `packages.available()` lists what the server offers.

## Keep a session warm between runs

Loading a model takes a moment (the encoder is a BERT model); asking it takes milliseconds. Because an intent
model's session outlives the script, the pattern for iterating on a script is simply to open the model at the top
and **not** close it:

```kotlin
val m = initMod("intents")                            // first run: loads; every later run: instant
listOf("turn the lights on", "what's the weather", "hi").forEach { text ->
    log("$text -> ${m.predict(text)}")               // the label; m.inference(text) for every label:score line
}
```

Edit the texts, press Run, read the console, repeat. The session keeps counting runs across your edits. Close it
when you are done (`m.close()`, or Terminate on the page) and the next `initMod("intents")` loads it again.

For a package, keep the session across runs by not closing it and reusing the **same** session on the next run:

```kotlin
val echo = packages.open.firstOrNull { it.jarName == "amie-demo-1.0.0.jar" && !it.closed }
    ?: initMod("amie-demo-1.0.0.jar")
```

(`packages.open` is the list of open package sessions, oldest first.)

## Scope functions

A script can say how long what it opens should live, instead of closing each session by hand. The four block functions
take a name and a block, run the block and return its value, so `val label: String = ortClosable { ... }` works. A bare
`initMod` outside any block behaves exactly as the rest of this page says, so scripts without them don't change.

| Function | What it does | A session that was already open |
|---|---|---|
| `ortClosable(name) { }` | What the block opens is for that block alone. It is closed when the block ends, by a return or an exception, the last opened first. A handle kept past the block fails with `is closed`, which is what one-time use means | Used and left open, with a console line saying so: the block frees only what it opened itself |
| `ortStatic(name) { }` | What the block opens stays open across runs until it is terminated or freed. Running the block again finds the same model session | Adopted: the scope keeps it from now on. If a closable around the block held it, static wins and the closable no longer closes it |
| `ortTimed(name, idleSeconds) { }` | Kept like `ortStatic`, and freed by itself once nothing has used it for `idleSeconds`: the block not run again, no session it holds asked. Its rows on the Sessions tab count down | Adopted, and used from then on |
| `ortLimited(name, max) { }` | Kept like `ortStatic`, at most `max` sessions under the name: when one more is opened the oldest is closed first | Adopted, and one of the `max` |
| `ortScopes.print()` | Lists what each static, timed and limited scope holds, a timed one with the time it has left, a limited one with how full it is, and returns those lines | Listed once it is adopted; a session no scope holds isn't listed |
| `ortScopes.free("name")` | Closes what that scope holds, the last opened first, and returns how many were closed | Closed if the scope adopted it |
| `ortScopes.freeAll()` | Closes what every static, timed and limited scope holds, and returns how many were closed | Closed if a scope adopted it; a session no scope holds stays open |

A template called in an `ortClosable` block belongs to that block, however deep the call is. It runs inside the block,
so what it opens is the block's too, and when the block ends the template ends with it: first its input closes so it can
finish, then, if it still goes on after its grace time, it is cancelled. Only then does the block close what was opened.
A `template(...)` called inside that template belongs to the same block, and so does a `coroutineTemplate`. An `ortStatic`
block inside keeps what it opens, as the innermost block decides. A delegate used again in a later block starts a new run.

The names are optional for the first two: `ortClosable { }` and `ortStatic { }` go by `closable` and `static`. This is
the starter **Scope functions: ortClosable and ortStatic** in the sandbox's Templates pane:

```kotlin
// ortClosable frees what its block opens when the block ends, so a handle is good for that one block.
// ortStatic keeps what its block opens across runs, until ortScopes frees it or it is terminated.
// ortTimed keeps it until nothing has used it for so many seconds; ortLimited keeps at most so many.

// A session open before a block is left as it is, so this starts with dummy closed.
initMod("dummy").close()

// The block's value comes back; the session is closed by then, however the block ended.
val (label, handle) = ortClosable("one time") {
    val m = initMod("dummy")
    m.predict("turn the lights on") to m
}
log("the block answered $label")

// One-time use: the handle the block gave back is closed.
try {
    handle.predict("hello there")
} catch (e: IllegalStateException) {
    log("after the block: ${e.message}")
}

// Kept under a name. Stop the script here and Models > Sessions says static search on its row.
val search = ortStatic("search") { initMod("dummy") }
log(search.predict("what's the weather"))
ortScopes.print().forEach { log(it) }

// Closes what search holds and says how many.
log("freed ${ortScopes.free("search")} session(s)")

// Kept while it is used: once nothing has asked it for 40 seconds it is freed by itself. Its row counts down.
val recent = ortTimed("recent", 40) { initMod("dummy") }
log(recent.predict("hello there"))

// At most two under the name, the oldest closed when a third is opened. A model has one session, so this
// takes dummy over from recent; packages and ONNX files are new on every call, so for them it fills up.
val pool = ortLimited("pool", 2) { initMod("dummy") }
ortScopes.print().forEach { log(it) }

// Closes what every block keeps, so this script leaves nothing open.
log("freed ${ortScopes.freeAll()} more")
```

What to keep in mind:

- **Blocks nest and the innermost decides.** A closable inside a static block frees what it opened and leaves what the
  static block holds alone. A static block inside a closable keeps what is opened in it after the closable has ended.
  The same goes for timed and limited blocks, which keep like a static one.
- **What a block tracks** is what the script opens itself: `initMod("name")`, `initMod("name.jar")`, `initMod()`,
  `group("name")` and `importModelOnnx(...)`. `initAmie` stays a one-off inside a block, opened and closed for the
  call. `serve("x")` holds its own model, so a closable around it doesn't stop the listener.
- **Packages and ONNX files are new on every call.** A static block keeps one more of them on each run, as a bare
  `initMod("x.jar")` leaves one more; a model has one session, so a model is held once however often it runs. That is
  what a limited block is for: `ortLimited("pool", 3) { initMod("x.jar") }` run again and again holds the three newest.
- **A timed scope counts use when it looks.** One daemon timer serves every timed scope, set to the nearest time up.
  When it is, a scope whose sessions ran since the last look counts as used and gets its `idleSeconds` again; running
  the block again counts at once. So what it holds goes between `idleSeconds` and twice that after its last use, never
  before, and the console says `ortTimed 'recent': nothing used it for 40s, 1 closed`.
- **A swarm answers for what it opened.** Closing a swarm closes the sessions it opened, even one a kept scope
  adopted meanwhile, and a closable closes the swarm it opened with them.
- **A session is held by one scope at most.** A second static, timed or limited block that asks for it takes it over
  from the first.
- **A name is one scope.** `ortTimed("search", 60) { }` after `ortStatic("search") { }` makes `search` a timed scope
  with what it holds, and the console says so; the kind and settings asked for last are the ones in force.
- **The block in force belongs to the thread.** Work a block starts on another thread is not tracked: it behaves as
  a bare `initMod` and stays open, which is the safe direction.
- **`ortForever` is `ortStatic` now.** A saved script that calls it still runs, and the console says the new name on
  each call; the editor no longer offers it.

The **Sessions** tab says what holds a session on its row, such as `static search`, `timed search, 40s left`,
`limited pool, 2 of 3` or `closable`. **Terminate** works on all of them as before, and the scope forgets what was
closed, so `ortScopes.print()` doesn't list it again. A row a kept scope holds has **Free** beside Terminate, which frees
the scope as `ortScopes.free("search")` would: everything it holds is closed, that session among them. The sandbox's
**Still open** tab has the same rows, and `free search` typed into the console does the same without a script.

## Ask without touching the model: sessionMod

`initMod("name")` gives you the model's session, which is the model as it is: it moves to every new head and logs what
it is asked. `sessionMod("name")` gives you a **snapshot**: the head and the labels the model has now, read together
and kept in memory. It is a model to ask and nothing more, and works on its own, with nothing else open:

```kotlin
val m = sessionMod("dummy")                  // an object
log(m.predict("turn the lights on"))
log("v${m.version}, ${m.labels}")

val same by sessionMod("dummy")              // the same thing, as a delegate

val label = sessionMod("dummy") {            // for one block: its functions at hand, gone when the block ends
    log(scores("turn the lights on").toString())
    predict("turn the lights on")             // what the call returns
}
```

- **It can't change the model.** It has `predict`, `scores`, `predictions`, `run`, `answer`, `inference` and
  `getStatus`, then `name`, `version`, `labels` and `close`. It isn't an `InteractionModel`, so `m.fineTune(...)`
  doesn't compile and nothing that takes a model takes it.
- **It stays on its head.** A fine-tune, a build under the same name, a rollback, a rename or a destroy of the model
  doesn't reach it: it answers as it did, where a session moves to the new head. Take it again for the new head.
- **It leaves no trace on the model.** What it is asked goes in no `sessions.log` and no run count of the model, and
  the model's session isn't opened. Its own runs are on its row on the Sessions tab.
- **It is cheap to share.** Everything that asks for the same head shares one snapshot, on the model's shared encoder,
  so a snapshot costs its head and no more.
- **It goes by itself.** The block form when the block ends, the others when the run ends or with `.close()`, the last
  to let go closing the snapshot. **Terminate** ends it for all who hold it. Neither touches the model.

A jar has no head, so `sessionMod("x.jar")` is refused. Use `initMod` for a session to keep warm between runs, with
its log, and `sessionMod` for a script that must answer the same through a retrain or has no business in the model's
log. The starters **A model to ask, nothing more** and **sessionMod as a block** are ready to run.

`sessionMod` was called `sharedModel` before. A saved script that still says `sharedModel` runs as it did, in all three
forms, with one console line naming the new name; the editor no longer offers the old one.

## Read what a session did

Every intent-model and package session keeps a log of what it was asked and answered:

| Line | Written when |
|---|---|
| `open` | the session was opened (or reused: *a model has one session*) |
| `swap` | a new head of the model took the place of the one the session answered with, which was closed once its answers were done |
| `run` | each `inference` call: the text, the first three answers, how long it took |
| `error` | an `inference` call threw: the text and the exception |
| `close` | the session was closed, with the number of runs |

Read it on the Models page, **Log** on the session's row, or in the file next to the owner:
`AMP/models/intents/<name>/sessions.log` for a model, `AMP/pluginDir/<jar>.sessions.log` for a package. The file keeps
the history of terminated sessions too; the page keeps the last 300 lines of an open one and **Log** shows the last 60 (with *… n earlier lines in
sessions.log*).

```
2026-10-14T17:40:02.118Z dummy #3 open  opened as session #3
2026-10-14T17:40:05.402Z dummy #3 run   "turn the lights on" -> lights:0.61, weather:0.22, greeting:0.17 (14 ms)
2026-10-14T17:41:10.009Z dummy #3 close closed after 1 runs
```

From a script, `packages.print()` logs one line per open package session (`#1 amie-demo-1.0.0.jar
(org.example.Echo) · pluginDir · runs 4`), and `sessions.print()` lists every ONNX Runtime session with its
inputs, outputs, runs and whether it is busy; an intent model's head shows there as `module:<name>`. The Sessions
tab shows the same at a glance: owner and `#id`, kind, *inferring* while it answers (a gray mark when idle), runs, open for how long.

## See what a script uses

Over the sessions, the tab lists **Scripts**: every script running, and each that ended and left something open, with
the sessions it opened under it. A script goes by the template it is, word for word; anything else, an edited template
too, is `main.kts`. Run again, it keeps its row and takes over what it left open. The row goes once nothing of it is
left.

```text
What a script uses
template · running 12s
holds 400.0 MB in 2 sessions · 8 answers · cpu 1.20 s · allocated 84.0 MB
```

| Figure | What it is |
|---|---|
| **holds** | What the sessions it opened weigh: heads, jars and encoders, as file sizes, since ONNX Runtime doesn't say what it holds. An encoder counts once however many share it |
| **answers** | What those sessions answered |
| **cpu**, **allocated** | The CPU time and the bytes allocated on the script's own thread, kept from the end of the run on. Threads the script starts and ONNX Runtime's own aren't in them, and allocated isn't memory held: most of it is garbage soon |

Each session has a resources line of its own under its row:

```text
dummy  #3          intent model · 14 runs · 2m 10s
head 9 KB · encoder 415.6 MB, shared by 3 · 12 ms a run · last used 3s ago
```

| Kind | What its line says |
|---|---|
| **Intent model**, **session model** | `head`, the model's ONNX head on disk; `encoder`, the encoder folder it runs on, said once with how many open sessions share it (`shared by 3`), left out while that encoder isn't loaded; the time a run takes on average; when it was last used |
| **Package** | `jar`, the jar on disk; the time a call takes; when it was last used |
| **ONNX session** | `file`, the bare ONNX file; the time a run takes inside ONNX Runtime; when it was last used |
| **Model group** | `heads`, its members' heads together; their encoder once; the time a run of the swarm takes; when it was last used |
| **Background template** | What its run meter reads right now, the figures the console prints when it ends: `memory 1.20 GB, peak 1.40 GB · heap 340.0 MB of 4.00 GB · cpu 2.10 s · ort 12 runs in 640 ms` (of the whole process; what each figure is, in [The output console](sandbox.md#the-output-console)) |

A session that hasn't run yet has no time and no last use. Over the rows, one line sums the tab up:

```text
Process 1.85 GB, of which encoders 415.6 MB · heap 340.0 MB of 4.00 GB · 20 sessions weigh 436.0 MB
```

What the process holds of the machine's memory (from the same reading as the status strip at the foot of the window), how
much of that is the encoders loaded outside the heap, the heap against its `-Xmx` limit, and what the open sessions
weigh together: each one's own file and every encoder once. File sizes are read as the page polls; they are one look
at the file each, the same look that tells whether it changed.

The starter **What a script uses** asks a model for 30 seconds, so there is time to open the tab and watch it.

## What runs where

- `inference` **blocks** the script until the answer is back. The model work itself runs on the app's ONNX
  threads, never on the UI thread, so the window stays responsive while a script waits.
- One text at a time goes through a model's encoder. Two scripts asking the same model take turns; the Sessions
  tab shows *inferring* while a call is in flight.
- Only one script runs at a time. Pressing **Run** during a run logs `Script is already running`; the sessions a
  running script opened stay open after it ends, whether it finished or failed.
- A script cannot be stopped from the window. Terminating the session it is using makes its next `inference`
  throw `Model <name> (#id) is closed`, which ends the script with `Failed after …`.
- **Serving** (Listen on the Models page) and **Validate** do not use your session: Listen loads its own instance
  of the model and closes it on Stop; a validation opens a session for its run, logs every row to it, and closes
  it at the end. Terminating a session never stops a listener.
- A **new head** of the model (a fine-tune, a build under the same name, a rollback, a client's `/retrain`) doesn't
  close your session: the new head is opened beside the one answering, put in its place once the answers in flight
  are done, and the old one is closed after. The session keeps its id, its run count and its log, which gets a
  `swap` line (`head v1 → v2 (fine-tune) swapped in; the head before was closed once its answers were done`); a
  script holding it just gets the new head's answers from then on, and a swarm over it too. A listener swaps the
  same way, on the same port. Only a model that stops keeping texts closes its session instead. A `sessionMod`
  doesn't move: it stays on the head it was taken on.
- A model's encoder (the BERT part, hundreds of MB) is loaded once and shared by every session, listener, build
  and validation on the same encoder folder; it is closed when the last of them closes. Cancelling an ONNX Runtime
  session (`sessions.cancel`, `sessions.cancelAll()`, **Terminate all**) ends what was running on it and frees it,
  but doesn't break the models using it: a model session or a listener whose head or encoder was cancelled opens
  it again on its next text, so only the call that was running when the cancel came fails.

## Close sessions

| Call or button | What it ends |
|---|---|
| `m.close()` | this session; a second call does nothing |
| The end of an `ortClosable` block | what that block opened itself, the last opened first, after the templates it started have ended ([Scope functions](#scope-functions)) |
| `ortScopes.free("name")`, `ortScopes.freeAll()` | what that static, timed or limited scope holds, what every one of them holds |
| An `ortTimed` scope's time up | what it holds, once nothing has used it for its `idleSeconds` |
| One more than `max` opened in an `ortLimited` scope | the oldest it holds |
| `packages.closeAll()` | every open package session (each package's `close()` is called, its class loader dropped) |
| `sessions.cancel("module:intents")`, `sessions.cancel("3")` | ONNX Runtime sessions by name or id; in-flight runs are terminated first. The model it belonged to stays open and opens it again on its next text |
| `sessions.cancelAll()` | every ONNX Runtime session, including the heads of open models and the shared encoder; open models and listeners open what they need again on their next text |
| `m.close()` on a `sessionMod`, the end of its block or of the run | this hold of the snapshot; the last to let go closes it |
| **Terminate** on the Sessions tab (also on a model's or package's own panel), or in the sandbox's **Still open** tab | that session; on a session model the snapshot, for all who hold it |
| **Free** on a session's row (Sessions tab and Still open tab), for one a kept scope holds | what that scope holds, as `ortScopes.free("name")` does |
| **Terminate all** on the Sessions tab, **Free all** in the Still open tab | everything open: swarms, model sessions, session models, package sessions, ONNX sessions, and every template running in the background |
| `free name`, `free all`, `terminate #3`, `terminate name`, `terminate all` typed into the sandbox's console | the same as the buttons, without a script; `sessions` lists what is open first ([The command line](sandbox.md#the-command-line)) |
| `job.cancel()`, `templateRuns.cancelAll()`, **Stop** in the Templates pane | a template running in the background, or all of them |

After a close, the session's row disappears, its log file keeps the history, and any further `inference` on the
closed object throws. Sessions do not survive a restart of the app: it starts with none open.

## When something is off

| Console says | What it means |
|---|---|
| `this sessionMod of dummy was closed` | It was asked after its `close()` or past the block it was for. Take it again |
| `the snapshot of dummy v1 was terminated` | **Terminate** ended it for all who held it. Take it again for the head the model has now |
| `initMod: 'x' is already open as session #3, that one is used` | Normal: a model has one session; you got the open one |
| `Error: Model x (#3) is closed` | The session was terminated (**Terminate** or **Terminate all** on the page, `close()`), or the `ortClosable` block it was opened in has ended; call `initMod("x")` again |
| `ortClosable 'closable': model x #3 was open already, so it is used and left open` | Normal: a closable frees only what it opened itself |
| `initMod: 'x' head was cancelled, opened again as #12` | A cancel (`sessions.cancel`, `cancelAll()`, Terminate all) ended the model's head; it was opened again for this text |
| `encoder: bge was cancelled, opened again as #14` | The same for the shared encoder: it opens its ONNX Runtime session again on the next text, for every model holding it |
| `Module 'x' not found in …/AMP/models/intents` | No model of that name was built; see the Models tab or `AMP/models/intents/` |
| `Module 'x' can't run: head: model.onnx missing in x/; labels: 1 for 2 outputs: a` | The model lacks something it needs, so nothing of it was opened; the message names all of it. `initMod`, **Open session** and Listen refuse when the encoder folder, its `model.onnx`, `tokenizer.json` or `config.json` is missing, when the encoder's hidden size isn't what the head expects, when the head file is missing, or when there isn't one label per output. A `config.json` with no `hidden_size` doesn't count, since a DistilBERT config says `dim`. The model's **Requirements** tab shows the same lines |
| `Package 'x.jar' can't run: key.properties: still its template, fill it in; $API_KEY: not set and required. Its config folder is …` | The package lacks something its metadata says it needs, so no instance of it was made; the message names all of it and the folder. `initMod` and **Test run** refuse when a required config file is missing, when a required one still equals its template (written there on that first open, for you to fill in) or when a required environment variable is not set. Fill it in and open it again. A package that declares nothing runs as before, and its panel's **REQUIREMENTS** shows the same verdict |
| `package x.jar not found: not in the env, AMP/pluginDir, as a path, or on the server (…)` | The jar is nowhere local and the Amie server does not list it; check `packages.available()` and `AMP/backend.json` |
| `x.jar has no package class: a public class with run(String): List and close(), and a no-arg constructor` | The jar does not follow the package contract, see [Packages](sandbox-scripting.md#packages) |
| `Script is already running` | Wait for `Finished` or `Failed`; sessions are unaffected |
| Many `name.jar #n` rows under Sessions | Package sessions are one per `initMod` call; close them in the script or press Terminate all |
