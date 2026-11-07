# Set up Amie on a new Mac

From nothing to talking to Amie, in about 20 minutes, most of it downloads. These are the steps a fresh download
was taken through on 3 November 2026: two clones, one setup script, one Gradle run.

## What you need

- A Mac (Apple Silicon was tested) with **about 8 GB free**: 4 GB of models, the rest for Gradle and the build.
- `git`, and access to the private `samging/amieAppStable` and `samging/jar-jar` repositories. `gh auth login`
  followed by `gh auth setup-git` is the quickest way.
- Nothing else. Gradle downloads a JDK 21 when there is none. Amie is a desktop app on its own: there is no account
  to make and nothing to sign in to.

## 1. Clone both repositories side by side

```bash
mkdir -p ~/amie && cd ~/amie
git clone https://github.com/samging/amieAppStable.git
git clone https://github.com/samging/jar-jar.git
```

`setup.sh` takes the package jars (face, speech, microphone, mail, browser, mac, memory, files, spotify) from the
`jar-jar` folder next to the app's. Until [amieAppStable#1](https://github.com/samging/amieAppStable/pull/1) and
[jar-jar#1](https://github.com/samging/jar-jar/pull/1) are merged, add `-b app-update` and `-b jars-update`
to the two clones. Without them you get the older app and jars.

## 2. Download the models

```bash
~/amie/amieAppStable/AMP/setup/setup.sh
```

It took 11 minutes and wrote 3.9 GB to `~/Library/Application Support/amie pilot/AMP`:

| What | Size | For |
|---|---|---|
| Llama 3.2 3B | 2.6 GB | Chat, and deciding what you meant when the intent model isn't sure |
| BERT NER and the bge encoder | 830 MB | The intent model, which `setup.sh` puts in `AMP/models/bert` |

Everything goes under `AMP/models/`: `bert/`, `text/` (Llama), `speech/` (Whisper and the voice), and `intents/` once the talk has trained its model. An older install, with `textModels/`, `modules/` and the speech models under `pluginDir/config/speech/`, is moved there by the app when it next starts; `setup.sh` skips what is already in either place.
| Whisper small.en | 358 MB | Hearing you |
| Supertonic 3 | 139 MB | Amie's voice |

It also copies the jars, the voice scripts, `voice-actions.csv` and the Talk to Amie template. Run it again
whenever you like: it resumes downloads and skips what is there. `AMIE_QWEN=1` also downloads Qwen 2.5 1.5B, for
quicker, simpler answers.

## 3. Start the app

```bash
cd ~/amie/amieAppStable && ./gradlew :desktopApp:run
```

The first build downloads Gradle's dependencies and takes a few minutes. The app opens on the Sandbox.

## 4. Talk

1. Open **Templates**, pick **Talk to Amie**, and choose **Load**.
2. Press **Run**. The first run trains the intent model from `voice-actions.csv`, which takes about half a minute.
3. Type `talk` in the console and press Return. The face appears over your windows and says hello.
4. Speak when the face listens. Say "goodbye" to end.

### The permissions macOS asks for

macOS asks before an app may listen or control other apps; Linux and Windows don't, and Amie asks nothing there.

| When | macOS asks | If you said no |
|---|---|---|
| The talk starts | To let the terminal that started Gradle (or the Amie app, once packaged) use the microphone | Amie says it can't hear you and opens System Settings > Privacy & Security > Microphone. Turn it on and start the talk again |
| Amie first uses Mail, Calendar, Reminders, Notes, Music or Spotify | To let it control that app, once per app | That action answers with an error. Turn it on under Privacy & Security > Automation |

Without microphone access macOS gives an app silence rather than an error, so the talk checks first instead of
listening to nothing.

Things to try, all of which worked on the fresh download:

| Say | Amie |
|---|---|
| "Hello Amie, how are you today?" | Answers through Llama |
| "Remember that I like green tea" | Saves it to `AMP/pluginDir/config/memory/memories.txt` |
| "How is my battery?" | "Battery is at 69%, charging." |
| "What song is this?" | Names what Spotify plays |
| "Goodbye" | "Talk soon!" |

The full list is in [Talk to Amie](voice.md).

## Optional

- **Spotify by name.** "Play Drake" opens a search unless Spotify keys are set. Make an app at
  developer.spotify.com, then put `clientId=` and `clientSecret=` in
  `AMP/pluginDir/config/spotify/spotify.properties`. Pause, next and "what song is this" need no keys.
- **Mail.** Add your accounts to the Mail app first. Amie only sends a mail after you say yes to the draft.
- **Start talking at launch.** `AMIE_AUTORUN=1 ./gradlew :desktopApp:run` runs the loaded `main.kts` as soon as the
  app opens.
- **Accounts.** Signing in, backups, cloud save and the package repository are off in this desktop version.
  `AMIE_ACCOUNTS=1` turns them back on, with Keycloak and the Amie backend running ([Keycloak setup](authentication.md)).
- **Another data folder.** `AMIE_AMP_DIR=<folder>` for both `setup.sh` and the app keeps a second, separate Amie.
- **A Mac app with the models inside.** Run
  `./gradlew :desktopApp:packageDistributionForCurrentOS -Pamie.modelsDir="$HOME/Library/Application Support/amie pilot/AMP/models/bert"`.

## When something goes wrong

| You see | Do |
|---|---|
| `No space left on device` | Free a few GB. Downloads resume when you run `setup.sh` again |
| `No BERT models at ...` | Run `setup.sh` again, or set `AMIE_MODELS_DIR` to a folder with `model.onnx` and `bge/model.onnx` |
| A jar is missing, or old | Make sure `jar-jar` sits next to `amieAppStable`, on the branch from step 1, and run `setup.sh` again |
| "I can't hear you yet" | Allow the microphone for your terminal under Privacy & Security > Microphone, then restart the app |
| `Property logger is not found in the bindings` | Fixed in `a0133dc`: pull. Until then, start with `JAVA_TOOL_OPTIONS=-Damie.prewarm=false` |

More in [Troubleshooting](troubleshooting.md).
