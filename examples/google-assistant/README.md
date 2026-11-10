# A Google assistant: one model, six packages

One template, `google-assistant`, that shows the parts of amie working together:

1. **The data.** 210 training requests and 70 test requests written for seven actions: calendar, tasks, drive, email,
   translate, web lookup and news. The rows are kept in the script and written to `AMP/assistant-train.csv` and
   `AMP/assistant-test.csv` the first time it runs. `log(train)` lays the file out as pandas' `df.info()` does.
2. **The model.** `modelBuilder("assistant-router", train, buildOptions(folds = 5))` trains heads on the bge encoder
   and keeps the best by macro F1 on the test file. Five folds put a range on the score and find the training rows
   whose label looks wrong.
3. **Its metrics.** `validateMetrics`, `metricsChart`, `confusionMatrix`, `calibrationChart`, `mistakes` and `suspects`.
4. **The assistant.** Each request goes to the package of the action the model picks, with the command that package
   understands. A request the model isn't sure of (under 0.6) asks nothing.

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

`setup.sh` adds the package jars that aren't in `AMP/pluginDir` yet and saves the template. Arguments go to amie,
such as `--amp ~/scratch/amp`. The first run trains the model in under a minute. It needs the bge encoder in
`AMP/models/bert/bge`.

`google-translate.jar` and `browser.jar` need no account. The Google packages need a sign-in once. Until then they
answer `needs setup` with the command that does it:

```bash
amie package setup google-calendar.jar
```

## What it prints

```
metrics 'assistant-router' @ ...: 280 samples (210 train / 70 validation on assistant-test.csv)
accuracy 1.000  macro F1 1.000
...
suspects 'assistant-router' · v1 · build · 4 of 210 training rows doubted by heads that didn't see them (5 folds)
label       suggested  score  label's  text
calendar    news       0.964  0.036    "read me today's events"
...
"translate good morning to german" -> translate 1.00 -> google-translate.jar "to german: good morning"
  Guten Morgen
"who was ada lovelace" -> web_lookup 1.00 -> browser.jar "find ada lovelace"
  Augusta Ada King, Countess of Lovelace ...
"what's on my calendar this week" -> calendar 1.00 -> google-calendar.jar "week"
  needs setup: google.properties: Still its template, fill it in; amie package setup google-calendar.jar
"play some jazz music" -> not sure (translate 0.54, under 0.60); no package asked
```

The test file is easy, so the scores are perfect. The suspects are the more useful part: "read me today's events" is
labeled calendar but sounds like news to the heads that didn't see it. Fix such a row in `assistant-train.csv` or keep
it, then run again. The model is rebuilt each run.

## Change it

```bash
amie template edit google-assistant
```

- Add a request to `requests` to see where it goes.
- Raise `minScore` to make the assistant ask less often when unsure.
- Add an action: rows for it in `trainRows()` and `testRows()`, and a `Route` with its package and command. Delete the
  two CSV files in `AMP` so they're written again.
