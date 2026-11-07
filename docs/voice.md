# Talk to Amie

Speak, and Amie does what you said or answers out loud. `./AMP/setup/setup.sh` puts everything in place once: about 4 GB
of free ONNX models, the shell scripts and the template. Then Templates > Talk to Amie > Load, and Run. The script loads nothing until you type `talk` in the console.

## A companion on your Mac

Amie has a face, greets you, remembers what you ask it to, and works the Mac through small jars with no keys. `face.jar`
floats over your windows: it listens, thinks, talks and smiles along with the talk, and you can drag it anywhere.
Close the app while it talks and it keeps going; the menu bar's face brings the window back or quits.



| Say | What happens |
|---|---|
| "Any new email?", "Read the second email" | `mail.jar` reads the Mail app. Add your accounts there first |
| "Reply to the first email and say I'll be there" | Llama writes it, it opens as a draft in Mail, and Amie reads it out and asks. Only a yes sends it; anything else leaves the draft |
| "Write an email to Anna saying I'm late" | The same draft and question, to the address of Anna's last mail in your inbox |
| "Search the web for pizza near me" | `browser.jar`, as a search in the browser |
| "Play Daft Punk", "Next song", "What song is this" | `spotify.jar` |
| "Make a note to buy coffee", "Set a timer for five minutes" | `mac.jar`, on Music or Spotify (`mac.properties`) and Notes. A timer lasts while the talk does |
| "Open YouTube", "Open the browser" | `browser.jar`, in your default browser or the one set in `browser.properties` |
| "Open Notes", "Quit Spotify", "Set the volume to 30", "How's my battery?" | `mac.jar` |
| "What's on my calendar today?", "Remind me to call mum" | `mac.jar`, on Calendar and Reminders |
| "Open man.webp", "Create a new file main.py", "Read notes.txt", "Find my resume" | `files.jar`, which finds by name in your home folder and never deletes or overwrites |
| "What's on my calendar tomorrow?", "Put a haircut on my calendar on Friday at 3 pm" | `mac.jar` |
| "Who wrote War and Peace?", "What's happening in Poland?" | `browser.jar` reads Wikipedia and the news, and Llama answers from that alone |
| "Any mail from the bank?" | `mail.jar` searches your inbox |
| "Turn it up", "Dark mode", "Take a screenshot", "How much space is left?" | `mac.jar` |
| "Close this tab", "What are my newest files?" | `browser.jar` and `files.jar` |
| "Goodbye" | Ends the talk. If you stay quiet, the face falls asleep and wakes when you speak |
| "Remember that I like green tea" | `memory.jar`, one line each in `AMP/pluginDir/config/memory/memories.txt` |
| "Catch me up" | mail, calendar and battery together |

Amie hears you out: `microphone.jar` records until you stop talking, up to 20 seconds. When the intent model is at least
98.5% sure it acts at once, which takes no time. Otherwise Llama reads your words and picks what to do, which takes a few
seconds, and anything that makes no sense gets "Sorry, I didn't catch that" instead of an action. To teach it how you talk,
add rows to `voice-actions.csv`, delete `AMP/models/intents/amie-intents`, and it trains again. To answer faster and hear a little
less, set `whisper=whisper-base.en` in the Packages tab.

The first use of each app makes macOS ask to let Amie control it, under System Settings > Privacy & Security >
Automation. Mail and the calendar are read out as they are: only a chat or a catch-up goes through Llama, and text in an
email can't trigger an action.

## What happens to what you say

1. `microphone.jar` records five seconds, and Whisper turns them into text.
2. The `amie-intents` BERT intent model picks an action. It trains itself from `AMP/voice-actions.csv` on the first
   Run. Under a score of 0.5 the words count as a question.
3. The action runs: a script of `AMP/pluginDir/voice`, or Llama.
4. The Supertonic voice speaks, and `microphone.jar` plays it.

When the next turn starts, the console says where the last one's time went, from Whisper on, without the wait for you
to speak: `Took … ms: hearing … ms, intent … ms, Llama … ms, voice … ms, playing … ms`. A part that didn't run that
turn isn't listed, and Llama counts every question it was asked.

| Action | For example | What it does |
|---|---|---|
| `weather` | "What's the weather in London?" | `weather.sh`, from wttr.in |
| `list_files` | "List the files in my downloads" | `list-files.sh` |
| `find_file` | "Find the file called notes" | `find-file.sh`, by Spotlight on a Mac |
| `web_search` | "Who wrote Hamlet?" | `web-search.sh`, from DuckDuckGo, then Llama |
| `open_terminal` | "Open a terminal in downloads" | `open-terminal.sh` |
| `close_terminals` | "Close all terminals" | `close-terminals.sh`, only when the model is at least 0.9 sure; what runs in them ends too |
| `edit_file` | "Edit the file called notes" | `edit-file.sh`, in the default text editor |
| `open_vim` | "Open the readme in vim" | `open-vim.sh`, in a new Terminal window |
| `brew_install` | "Brew install ripgrep" | `brew-install.sh`, only when the model is at least 0.9 sure, and only a formula name |
| `chat` | "Tell me a joke" | Llama |

To teach it another action, add rows to the CSV, delete `AMP/models/intents/amie-intents`, and add a branch to the `when`
of the script.

## What Amie manages

Llama, the intent model, Whisper, the voice and `microphone.jar` are sessions of the app: they are in Still open, under
their own names, and Stop or Terminate ends them. `initMod("whisper")` and `initMod("voice")` open the ones
`speech.properties` names, and a block such as `ortClosable` holds them like any model. Each loads when first used and
stays until talking ends. Their memory is native, so the session shows what they weigh on disk.

Which Whisper, which voice, how fast it talks and how many threads it uses are in `speech.properties`, and the
microphone in `microphone.properties`. `speaker` picks who talks: 0 af, 1 af_bella, 2 af_nicole, 3 af_sarah, 4 af_sky,
5 am_adam, 6 am_michael, 7 bf_emma, 8 bf_isabella, 9 bm_george, 10 bm_lewis. Any Piper voice of sherpa-onnx also works
if you put it beside them and name it in `voice`. Both open in the Packages tab. The models sit beside that file, in
`AMP/models/speech`.

## When a command fails

Each command goes through `.patchBy(::explain)`. A command that fails hands what it said to `explain`, which asks Llama
why and speaks the answer. `patchBy` takes any function, so the same error can go elsewhere:

```kotlin
shellScript("voice/brew-install.sh", listOf("wget"))
    .patchBy(::log)                                  // the console
    .patchBy { error -> speak(error) }               // the voice, word for word
    .patchBy { error -> log(llama.answer(error)) }   // a model
    .patchBy(::handleBy)                             // the local text model, with main.kts and the env in view
```

What a failed command printed is in the error terminal too, with why it failed: `errorLog()` pulls its last lines, and
`handleBy()` hands them to the local text model, so a script can ask about the whole of what the console showed in red,
not only the one command.

`textModel` in the script picks the model: `llama3.2-3b` (about 3 GB of memory) or the faster `qwen2.5-1.5b` (about
1.3 GB). Other exports go in `AMP/models/text`. An export that needs a graphics card won't load here.

## In an exported bundle

Export the template as any script. The intent model, the text model, the two packages, the speech models and the shell
scripts all go along, and `talk` is read from standard input. `speech.jar` carries the native library of Apple silicon
only, so the bundle runs on those Macs.
