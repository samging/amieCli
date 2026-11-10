# A Google assistant: one model, six packages

One template, `google-assistant`, written on the amie API from start to end:

| Step | amie calls |
|---|---|
| Build the router once, from rows kept in the script | `hasModel`, `modelBuilder(name, buildOptions(folds = 5)) { trainRows() }` |
| Its metrics | `validateMetrics`, `metricsChart`, `confusionMatrix`, `calibrationChart`, `mistakes`, `suspects` |
| Check it on 70 requests it never saw | `quietly { sessionMod(model) { predict(...) } }` |
| Answer each request with a package | `initMod("x.jar").run(command)`, inside `ortClosable` so every session closes at the end |
| A package that isn't set up | `handleBy`, which turns the refusal into the setup command |
| Requests typed at the terminal, or sent by another script | `input()`, `output()`, `template("google-assistant")` |

The model has seven actions: calendar, tasks, drive, email, translate, web lookup and news. A request it isn't sure of
(a top score under 0.6) asks no package.

| Action | Package | Command it gets |
|---|---|---|
| calendar | `google-calendar.jar` | `week` or `today` |
| tasks | `google-tasks.jar` | `tasks` |
| drive | `google-drive.jar` | `search <name>`, else `recent 5` |
| email | `gmail-reader.jar` | `unread 5` |
| translate | `google-translate.jar` | `to <language>: <text>` |
| web_lookup | `browser.jar` | `find <words>` |
| news | `browser.jar` | `news <topic>` |

Every command only reads. Nothing is created, changed or deleted on the Google account.

## Run it

```bash
examples/google-assistant/setup.sh
```

```bash
amie template run google-assistant
```

It builds the model the first time (under a minute; it needs the bge encoder in `AMP/models/bert/bge`), prints the
metrics, answers nine requests, then waits for yours: type a request and press Enter, Ctrl-D to end. A package jar that
isn't in `AMP/pluginDir` is fetched from GitHub when `initMod` first opens it.

`google-translate.jar` and `browser.jar` need no account. The Google packages need a sign-in once. Until then they
answer `needs setup` with the command that does it:

```bash
amie package setup google-calendar.jar
```

## Call it from another script

A template is a function to other scripts. Each call sends it a line and returns what it answers:

```kotlin
val assistant by template("google-assistant")
log(assistant("translate see you tomorrow to french"))   // ... google-translate.jar "to french: see you tomorrow"  à demain
assistant.close()
```

## What it prints

```
metrics 'assistant-router' @ ...: 210 samples (168 train / 42 validation on split 20%)
accuracy 1.000  macro F1 1.000
...
held-out requests: 70 of 70 routed right
"translate good morning to german" -> translate 1.00 -> google-translate.jar "to german: good morning"
  Guten Morgen
"who was ada lovelace" -> web_lookup 1.00 -> browser.jar "find ada lovelace"
  Augusta Ada King, Countess of Lovelace ...
"what's on my calendar this week" -> calendar 1.00 -> google-calendar.jar "week"
  needs setup: amie package setup google-calendar.jar
"play some jazz music" -> not sure (translate 0.50, under 0.60); no package asked
Type a request, Ctrl-D to end
```

The requests are short and distinct, so the scores are high. A request from none of the seven actions can still score
high on one of them: "open the weather app and dance" gets calendar 0.91. The 0.6 gate catches only the unsure ones;
[clinc-oos](../clinc-oos) shows how to measure and choose such a threshold.

## Change it

```bash
amie template edit google-assistant
```

- Add a request to the list in step 4 to see where it goes.
- Raise `minScore` to make it ask less often when unsure.
- Add an action: rows for it in `trainRows()` and `testRows()`, and a `Route` with its package and command, then
  `amie model remove assistant-router` so the next run builds the model again.
