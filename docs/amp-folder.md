# The AMP folder

Everything the app writes lives in `AMP/`. Both `./gradlew :desktopApp:run` and the packaged app (DMG, MSI, DEB)
keep it in the user's application-data folder: `~/Library/Application Support/amie pilot/AMP` on macOS,
`%APPDATA%\amie pilot\AMP` on Windows, `~/.local/share/amie-pilot/AMP` on Linux (under `$XDG_DATA_HOME` when that
is set). The Gradle run task hands the app its resources folder, just as the packaged app has one, and that is what
picks this folder. On its first start the folder is seeded from the `AMP/` shipped inside the app (`backend.json`,
`keycloak.json`, `componentSettings.json`, `pluginDir/`, `env/`, `sample-intents.csv`), which is made from
`amieApplication/desktopApp/AMP/` in the repository; the dummy model is written next. Started some other way, such
as running `MainKt` straight from the IDE, the app keeps `AMP/` in its working directory instead. `AMIE_AMP_DIR`
names another folder in every case; the tests use it to work in `amieApplication/desktopApp/build/test-amp`. The
`amie.amp.dir` system property does the same when `AMIE_AMP_DIR` isn't set: an [exported bundle](sandbox.md#exporting-a-script)'s
runner uses it for the `AMP/` beside it, and `amie.models.dir` for its `models/bert/`.
Every model lives under `models/`, one folder per kind ([below](#the-models-folder)).
Nothing is kept anywhere else on the machine, so copying this folder moves your scripts, models, metrics, plugins
and sign-in to another checkout or machine.

| Path | What it holds | Tracked in git |
|---|---|---|
| `pluginDir/main.kts` | The sandbox script, saved as you type | yes |
| `pluginDir/assembly.kts` | The script as it was assembled for the last Run (the prelude, the env, main.kts) | yes |
| `pluginDir/backup/main-<yyyyMMdd-HHmmss>.kts` | The script a template replaced in the editor; the Templates pane's **Previous scripts**. What a [restore](settings.md#backup) replaces is kept here too, named the same way after the file | yes |
| `pluginDir/corePlugins.json` | The plugin jars `importPlugin` knows | yes |
| `packages.json` | The plugins chosen per device on its configuration page | yes |
| `<file>.csv` | A training or validation csv picked from elsewhere, copied in so the model can find it again | yes |
| `.<name>-to-label.csv.next` | A queue being written; it replaces `<name>-to-label.csv` when complete | no |
| `pluginDir/runs.jsonl` | The sandbox's last 20 runs, one line each: when it began (UTC), how long it took, whether it finished, a failed one's first error and what it used; Recent runs shows them. Not shipped in the packaged app | no (this machine's runs) |
| `pluginDir/*.jar` | Package jars, opened by scripts with `initMod("x.jar")`; jars fetched from the Amie server land here | yes |
| `pluginDir/*.jar.source.json` | Where a fetched jar came from: download URL, website page, version, fetch time; `repo` and `sha` for a jar from GitHub | yes |
| `github.json` | The GitHub repositories package jars are looked for in (`repos`, default `samging/jar-jar`) and an optional `token` for private ones; written on first use | no (it may hold a token) |
| `pluginDir/.incoming/` | A downloaded jar while it is checked; removed when it fails the check | no |
| `pluginDir/collections.json` | The package collections: name and member jars | yes |
| `pluginDir/*.jar.package.json` | Metadata written beside a jar that ships none (or over the jar's own): config files, env, Java, entry class, tests | yes |
| `pluginDir/*.jar.tests.json` | The last test run of that package: each test's input, answer, pass/fail | yes |
| `pluginDir/config/<jar>/` | The configuration files a package reads: its templates are written here, the package resolves them as resources | yes |
| `env/<env>/groups.json` | The model groups of that env: name, models, icon | yes |
| `env/<env>/<group>.sessions.log` | What the swarm of that group was asked and answered; when one of its models keeps no texts (`"keepTexts": false`), each text's length instead of the text, as in that model's own log and in the swarm's console lines. Its other models then log the texts the group asks them the same way, in their `sessions.log` and console lines; a text asked of one of them directly is logged as its own `module.json` says | yes |
| `pluginDir/*.jar.sessions.log` | What every session of that package was asked and answered (open, run, error, close lines) | yes |
| `models/intents/modules.json` | The registry of the models: each name and the folder under `models/intents/` it lives in | yes |
| `models/intents/<name>/model.onnx` | An exported model | yes |
| `models/intents/<name>/dropped.csv` | The rows a build dropped, and why (only for a model that keeps texts) | yes |
| `models/intents/<name>/icon.<ext>` | An uploaded icon (**Upload image** on the About tab) | yes |
| `models/intents/<name>/module.json` | Its manifest: encoder, dimensions, labels; its version and what made it (`kind`: build, fine-tune, calibration, rollback; `parentVersion`, `fineTunedOn`), the rows per label it learned from (`dataRows`, `fineTunedRows`, for the model card), the `temperature` folded into its last layer, and the mean of its training features (`featureMean`, base64 floats, for a fine-tune without the data; not kept when `keepTexts` is false) | yes |
| `models/intents/<name>/metrics.json` | One validation entry per build, fine-tune, calibration, validation or rollback (tables and charts read this) | yes |
| `models/intents/<name>/guide.md` | The model's guide, its **Guide** tab, published with it; between its two `amie:model-card` marker lines the [model card](models.md#the-model-card), written again after every run | yes |
| `models/intents/<name>/versions/v<N>/` | The ten heads before the current one: `model.onnx`, `module.json` and `run.json` (the run it was scored with), for `rollback` | yes |
| `models/intents/<name>/serving.json` | Host, port, path, methods, listening, the min score and fallback label when set, and who may ask when set (`apiKey`, `cors`, `maxBodyBytes`, `rateLimitPerMinute`: [Serving a model](serving.md#who-may-ask-and-how-much)); an `apiKey` written `$ENV:NAME` is read from there when a request comes | yes (with a key set it holds the key, so keep the tracked seed's keyless) |
| `models/intents/<name>/incoming.jsonl` | Requests the served model received | yes |
| `models/intents/<name>/access.log` (`access.1.log`) | One line per request its listener answered, whatever the status (time, request id, client, method, path without query, status, ms, text length, bytes, outcome; never a text), and its start and stop lines; moved to `access.1.log` past 2 MB ([Serving a model](serving.md)) | yes |
| `templates/<id>.kts`, `templates/<id>.json` | The sandbox's [script templates](sandbox.md#templates): the code, and its name, about, tags, source, env, lines, what it opens, times and loads; `.seeded` marks the first seeding done | yes |
| `models/intents/<name>/plots/*.png` | The metrics plots (per run: scores, confusion, and for runs that kept them how the heads learned and the calibration; across runs; from the sessions), drawn again when `metrics.json` changes or when an older version of the app drew them (`plots/.style` says which style did); `compare.png` is the last comparison of a kept head with the current one (**Compare** under Versions), drawn by each comparison | yes |
| `models/intents/<name>/sessions.log` | What every session of that model was asked and answered (open, run, error, close lines); for a model with `"keepTexts": false` in its `module.json` (built with `buildOptions(keepTexts = false)`, like the model site's) each text's length instead of the text | yes |
| `env/<name>/env.lock.json` | What a script environment has imported | yes |
| `env/caches/` | Maven downloads | yes |
| `sample-intents.csv` | 45 `text,label` rows over greeting / lights / weather, written on first start for trying New model and Validate | yes |
| `<name>-to-label.csv` | The model's queue: the texts it is least sure about (`textsToLabel`), the answers corrected on the Try line, and the unsure answers of a batch pasted there (`queueGuesses`), with its guess beside the label column. Renamed with the model, deleted when the model is destroyed | **no** |
| `cache/embeddings/<encoder>-<hash>/vectors.bin`, `encoder.txt` | The sentence vector of every text embedded so far, keyed by the SHA-256 of the text (not the text), one store per encoder (`encoder.txt` names it); started again past 512 MB; nothing a model that keeps no texts embeds goes in (its builds, fine-tunes, calibrations and comparisons); `embeddingCache.clear()` empties it | **no** |
| `relations.json` | Which test file belongs to which training file (`relate`) | yes |
| `componentSettings.json` | Configured devices | yes |
| `databases.json` | The Postgres connections a script opens with `database("name")`: url, user, password (any of them written `$ENV:NAME` reads an environment variable, or that line of a bundle's `secrets.env`), written with a `main` entry on first use | **no** |
| `backend.json` | Amie backend, website and S3 service addresses | yes |
| `keycloak.json` | Keycloak server, realm, client id and **secret** | **no** |
| `session.json` | The signed-in session (access, refresh and id tokens) | **no** |
| `settings.json` | The UI scale, where the sandbox's tool windows stand and how big they are, whether the Models page's sidebar shows, whether the first steps over the Models tab were dismissed (`firstStepsHidden`), the editor's text size (`editorFontSize`) and re-indent on Enter (`reindentOnEnter`) and Run details (`runDetails`); written at the first change | yes |
| `logs.txt` | Everything the console showed, every level; past 20 MB it moves to `logs.1.txt` (replacing the one before), so two files are kept | yes |

## The models folder

| Folder | Holds | Was, before November 2026 |
|---|---|---|
| `models/bert/`, `models/bert/bge/` | The NER model and the bge encoder every intent model is trained on; `./AMP/setup/setup.sh` puts them there | the same |
| `models/intents/<name>/` | The trained intent models (the table above), with `models/intents/modules.json` as their registry | `modules/` |
| `models/text/<name>/` | The text models, Llama and Qwen: ONNX Runtime GenAI exports | `textModels/` |
| `models/speech/<name>/` | Whisper and the voices `speech.jar` runs | `pluginDir/config/speech/models/` |
| `models/imported/<name>/` | Models brought in from outside, such as a Hugging Face repo: the downloaded files under their repo paths (`onnx/model.onnx` stays as it is) and a `source.json`; `initMod("<name>")` opens one | new |

The app moves an older folder into place when it starts (`ModelsMigration`, before anything reads a model): an old
folder whose new one isn't there yet is renamed, on the same volume, so nothing is copied; a symbolic link moves as a
link. When both are there nothing moves: the app reads the new one and the console says which folder to tidy. Nothing
is deleted or overwritten, and every move is logged. A folder that can't be written is read as it is. `speech.jar` reads
its models where `models=` in `pluginDir/config/speech/speech.properties` says, so the move sets that to
`models/speech` when the setting is empty or names a folder that is gone; a setting that names a folder that is there is
left alone. `ModelFolders` is the one owner of these paths in the code; until the next release it still reads an old
folder whose new one isn't there.

An imported model's `source.json` says where it came from and which format to open it in:

```json
{"source": "huggingface", "repo": "owner/name", "revision": "<commit sha>", "format": "onnx", "files": [{"path": "onnx/model.onnx", "size": 90387630}], "downloadedAt": "2026-11-06T10:00:00Z", "task": "text-classification", "labels": ["greeting", "lights", "weather"]}
```

`format` is `onnx`, `torchscript`, `tensorflow` or `tflite`; `task` is the Hub's pipeline tag (null when it has none)
and `labels` what the model's `config.json` names (`id2label`, in order), both read by the Models page without opening
the model; a `source.json` from before has neither, which reads as none. A download in progress lives in
`models/imported/.incoming/<name>/`, each file as `<path>.part` until its size checks. The folder moves into place
only when it is whole, so a folder under `models/imported` is always complete. A download cancelled from the Models page
removes its `.incoming` folder; one cut short in any other way leaves it, for the next download to go on from.

## Configuration files

`keycloak.json` is written with defaults on first start:

```json
{
  "baseUrl": "http://localhost:8080",
  "realm": "master",
  "clientId": "amie",
  "clientSecret": "<the amie client's secret>",
  "redirectPort": 48080
}
```

Environment variables `KEYCLOAK_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` override it.

`backend.json` names the Amie backend, the website and the S3 service that keeps package jars; `AMIE_BACKEND_URL`,
`AMIE_SITE_URL` and `AMIE_S3_URL` override it. With
Keycloak on port 8080, run the backend on another port (`AMIE_BACKEND_PORT=8090`) and set `baseUrl` here.

## Metrics format

`metrics.json` is a list of runs; the last one is the current model:

```json
{
  "runs": [
    {
      "createdAt": "2026-10-14T14:02:11Z",
      "samples": 240, "trainSize": 192, "validationSize": 48,
      "accuracy": 0.917, "macroF1": 0.905,
      "labels": { "lights": { "precision": 0.933, "recall": 0.933, "f1": 0.933, "support": 15 } },
      "confusion": [[14, 1], [1, 14]],
      "warnings": [],
      "validation": "split 20%"
    }
  ]
}
```

`confusion[truth][prediction]` is indexed like the label list in `module.json`.

Times in these files (`createdAt` here, in `module.json` and in `groups.json`, a package report's `ranAt`, a jar's
`fetchedAt`) are instants in UTC, as the trailing `Z` says; the Models page and the console show them in this
machine's local time. A time written into text that is kept, such as a rollback's note ("v1 brought back (made
2026-10-21 04:03 +02:00), replacing v2"), carries its offset from UTC, so it reads right on any machine.

Runs written since versions are kept carry more, all optional (older runs have none of it and read the same):
`kind` (build, fine-tune, calibration, validation, rollback) and `version`; `analysis` with `ece`, the
`reliability` bins, the `selective` gates, the `mistakes` and `suspects` (texts only when the model keeps texts),
the `temperature`, `eceBefore` and a fine-tune's learning `curve`; `baseline`, the head before a fine-tune or a
calibration scored on the same rows; `accuracyRange` and `macroF1Range` (`low`, `high`: where the score fell in the
middle 95% of 1,000 resamples of the run's rows, see [how far the scores hold](models.md#how-far-the-scores-hold));
`leakage` (`checked`, `exact`, `near`, the `warning` it gave, and for a model that keeps texts up to ten `pairs` of
a scored row and the row it learned from, see [rows it learned from](models.md#rows-it-learned-from)); and a
`note`. Each entry of `candidates` adds the cross-validation scores
(`cvAccuracy`, `cvMacroF1`, `cvSpread`, `folds`), where early stopping stopped and which epoch it kept
(`stoppedAt`, `keptEpoch`), and its learning `curve` (up to 41 points of loss and accuracy on the training and
validation rows).
