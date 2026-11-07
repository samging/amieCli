# Getting started

## What you need

| Requirement | Why | Notes |
|---|---|---|
| JDK 21 | The desktop module pins a Java 21 toolchain | Gradle's foojay resolver downloads one if it is missing |
| Gradle wrapper | `./gradlew` from the repository root | Gradle 9.3, configuration cache on |
| BERT models | `modelBuilder` and `initMod` embed text with the `bge` encoder; `initMod()` runs the NER model | The NER folder (`model.onnx`, `tokenizer.json`) with `bge/` inside it: `AMP/models/bert` for a Gradle run, where `./AMP/setup/setup.sh` downloads them, or `AMIE_MODELS_DIR` (`BundledModels.kt`). The packaged app ships them in `app/resources/models/bert/` |
| Keycloak (off) | Sign-in, backups and publishing | The desktop app has no accounts for now; `AMIE_ACCOUNTS=1` turns them on, with Keycloak in Docker on `http://localhost:8080` |
| Amie backend (optional) | Devices, packages, **Publish** | Address in `AMP/backend.json`; see [Amie backend](backend-integration.md) |
| Android SDK (optional) | Only for the Android app | `sdk.dir=...` in `local.properties` |

## Build and run

All commands run from the repository root.

```bash
./gradlew :desktopApp:run                 # run the desktop app
./gradlew :desktopApp:compileKotlin       # compile only (fast check)
./gradlew :desktopApp:packageDistributionForCurrentOS   # DMG / MSI / DEB under amieApplication/desktopApp/build/compose/binaries/main/, BERT models included
```

Packaging copies the BERT models into the app (the `bundleModels` task) and fails when they are not there: point
it at your copy with `-Pamie.modelsDir=<folder>` or `AMIE_MODELS_DIR` (the folder with `model.onnx` and
`bge/model.onnx`). A package is built for the system it is built on, so the DEB comes from a Linux machine.

**The lightweight build** (`-Pamie.lightweight=true`, the default on an Apple Silicon Mac and on arm64 Linux such as a Raspberry Pi 5) leaves
out TensorFlow 1.15, KotlinDL and their JavaCPP, about 700 MB of jars that are mostly native libraries for other systems.
Training, fine-tuning, calibrating and serving are the app's own Kotlin heads on ONNX Runtime and work the same. It
carries DJL's TensorFlow 2 engine instead, so `importModel` and `initMod` open TensorFlow SavedModels in it (the full
build can't, as TensorFlow 1.15's classes clash with it); both builds open TorchScript on PyTorch
([Models from outside](sandbox-scripting.md#models-from-outside)). Settings shows **Build · Lightweight · no TensorFlow 1.15** in it. The arm64 DEB can be made on an
Apple Silicon Mac in an arm64 Linux container (`eclipse-temurin:21-jdk` with `fakeroot` and `dpkg-dev`, the
models mounted and named with `AMIE_MODELS_DIR`, then `./gradlew :desktopApp:packageDeb`); it installs into
`/opt/amie-pilot` with a menu entry, and keeps `AMP/` in `~/.local/share/amie-pilot/AMP`.

In IntelliJ or Android Studio open the repository root and use the run configurations in the toolbar.

## Running the tests

```bash
./gradlew :desktopApp:test
```

The desktop module's tests need no services and never touch your `AMP/` folder: the test task points `AMIE_AMP_DIR`
at `build/test-amp` and the backend, site and Keycloak addresses at fake hosts, and the HTTP clients under test
get a mock engine. They cover the editor (folding, formatting), the prediction helpers, the Postgres driver and
config, every script of the cookbook and the starter templates (compiled through the sandbox's assembler), the templates store (save, replace, seed once), the
serving access log (one line a request, never a text), the
Keycloak client (password grant, refresh, logout, what each failure says, issuer, signature and expiry checks on
the token), the backend client (profile, upload, publishing, the SSO handoff, saved states, error reports, an
unreachable backend), a model session over a Mockito model, and the training kit (regularisation, schedules,
early stopping, cross-validation, the head search, fine-tuning, calibration, versions and rollback, the texts to
label, the embedding cache and the charts) over a fake encoder. `TrainingGoldenTest` holds the weights a default fit
gives to the bit, so a change that moves the default training fails there. Five test classes have cases that run the
real BERT encoder, so those only run when asked (the rest of each class runs always):

```bash
AMIE_E2E_ENCODER=1 ./gradlew :desktopApp:test --tests '*TrainingGoldenTest' --tests '*RealEncoderFineTuneTest' --tests '*EncoderLifecycleTest' --tests '*OrtScopesScriptTest' --tests '*ModelGroupsScriptTest' --tests '*VersionCompareTest' --no-configuration-cache --rerun
```

The first checks that a default build on the sample data still writes the same `model.onnx`, byte for byte; the
second builds on the sample data, teaches the model a `music` label from a dozen sentences, checks it still answers
the old labels and rolls it back. The third opens and closes a model session 30 times and checks that the shared
encoder is given back each time, that a model closed twice gives it back once, that a cancelled encoder or head is
opened again on the next text, and that a listener, a rename and a destroy let go of the encoder and the port.
(`ModelServerListenerTest` starts and stops a listener 20 times on a free port without the encoder, in the normal run.)

`PageKeyboardTest`, in the normal run, opens the real Models page off-screen on three small models built with the
fake encoder and presses every shortcut of the page (the list's moves, Enter to the Try line, ⌘F, ⌘1 to ⌘4, and
Esc on each kind of thing it closes, the command palette, and Enter to a group's Try line), checking where the selection and the keyboard end
up. It lists no GitHub jars while it runs, so it needs no network. `TryPasteTest` pastes into the Try line with the
paste key, from a clipboard of its own: several lines after a sentence asked are a batch of the lines pasted alone,
the field keeps what it held but for a selection pasted over, and a paste while it is asking starts nothing and says
so. `SelectionStaysTest` checks that the palette's lit line stays on its item when the page lists the items again in
another order, and that a list scrolls its selection into view once, at its first copy, and not again when a fold
opens on it. `FirstStepsPageTest` opens the page from the empty snapshot of the app's first start on a model built,
fine-tuned, asked and corrected with the fake encoder, and checks that the first steps stay hidden, also once the
correction is taken away; then it opens the page again (the strip shows that step), gives the correction back (*all
done*) and dismisses them. `FirstStepsStripTest` checks the strip alone; with `AMIE_PAGE_SHOTS` set it also saves it
fresh, half done and done. `NewModelPanelTest` checks that a csv a first step asks for goes into an open New model
form with what was typed in it kept. `MixUpsPageTest` gives two models built with the fake encoder a validation run
with lights and weather swapped: What would help names the pairs, **Show the rows** opens the Metrics tab on the
chosen pair's rows, brought into view in a window too low to show them otherwise, its chip shows them all again, and
the model that keeps no texts gets the counts and no rows. `FocusRingTest` checks the ring Tab gives a control (and
that a click leaves none, Space and Enter press it, and on the Models page Enter presses the control Tab moved to
rather than going to the Try line, goes on to the Try line from text that presses nothing, and held down on a button
presses only the button); `ContrastTest` computes WCAG's ratio for every colour against each surface it
is drawn on, and for a heatmap's text on every shade of the ramp. `SandboxJumpTest` checks the sandbox's places: a
console line's place calls back with its line and column (also while its tooltip shows, which used to throw), the
editor puts the caret on a place, opens the fold over it and brings it into view, and in the real sandbox a script
that doesn't compile names its own line, not one of assembly.kts, and is kept as a failed run.
`SandboxEditorWidthTest` checks that the editor lays its text out as wide as its view, narrower once the console
comes up beside it, and wider, unwrapped, for a line longer than the view, the width read off the field's own lines
being the text's measured unwrapped. `ConsoleFollowTest` checks that a console
opened on more lines than it shows starts at the newest and follows the next.

`PageShotsTest` renders the real Models page off-screen and uses it the way a person would: it clicks rows, tabs
and buttons by their text, types on the Try line, and saves a PNG of every state worth a look. It builds two
showcase models with the real encoder in the test AMP folder (a minute or two) and a group of the two, so it only
runs when given a folder for the pictures. The harness itself (the window, the showcase models, the shots) is
`ui/PageHarness.kt`, which `CodingSandboxTourTest` extends too:

```bash
AMIE_PAGE_SHOTS=/tmp/shots ./gradlew :desktopApp:test --tests 'org.example.project.ui.PageShotsTest' --tests 'org.example.project.ui.CodingSandboxTourTest' --no-configuration-cache --rerun
```

The tour starts with the first steps, on a page of their own opened with the queue's labels taken away, so *correct
an answer* is the step to take: taken on the Try line (*all done*, the bar green), then **Dismiss**, and Settings with
the toggle that shows them again. Then it goes through the
model list and a model's About and Metrics tabs, its Improve tab with the queue half
labeled, its first version compared with the head that answers now on what its fine-tune taught it (the figure, then
the rows that changed), a Try answer corrected with a new label, the fine-tune plan blocked and then ready, the notice with Undo
after the fine-tune, the ⋯ menu, New model (Train on a csv…) with the sample data, a batch of sentences pasted on the Try line (copied
as csv, which it checks, and its unsure answers queued), a model that keeps no texts with a sentence and a batch
(checking on the way that nothing it was asked was written anywhere in AMP), the Groups tab with the group of the
two asked a sentence on its Try line (the same check after it), the Packages and Sessions tabs, and Settings. Last,
on a page of its own as it adds a run, both models are validated on the sample data with five lights rows labeled
weather and three weather rows labeled lights: the Improve tab names what it mixes up, **Show the rows** opens one
pair's rows, and the model that keeps no texts gets the counts (the same check after it, its csv holding a sentence
nothing may keep). What the page copies goes to the harness, never to your clipboard.
`AMIE_PAGE_STEPS` runs steps of your own instead, separated by `;`, such as
`click:Metrics;wait:1000;shot:metrics` or `ask:turn the lights on;until:wrong? it is;shot:answer`. The other steps
are `named:text` (a click on the first control whose screen-reader name holds that text, as an icon-only
control's tip does: `named:Where Listen serves`), `at:x,y` (a click there), `hover:x,y` (the next shot keeps the
pointer there, so its tooltip shows),
`type:text`, `key:Enter` (`key:Cmd+F` holds ⌘, Ctrl off macOS; `key:Tab` moves the keyboard on, its ring showing, as in the app), `paste:one|two|three` (those lines into the Try
line at once, as a paste gives them), `clipboard:name` (what the page copied, as `name.txt`), `scroll:text` (scrolls
until what shows that text, or has it as its description, is in view: `scroll:Versions`), `menu` (the selected
model's ⋯ menu),
`building:showcase:fineTune` and `built` (the page sees a build, fine-tune or calibration of that model start and
end, without training anything), `busy:ms` and `tree:name`, which writes the page's semantics tree as text so you
can see what there is to click. The showcase models are removed at the end;
`AMIE_PAGE_KEEP=1` keeps them for the next run. `AMIE_PAGE_HEIGHT` sets the window's height (1000 by default; the
window is 1500 wide). `show:sandbox` (or `show:settings`, `show:models`) switches the window as the sidebar would;
in the sandbox, `code:one|two` puts those lines in the editor in place of its text, and `run` presses Run.

`CodingSandboxTourTest` (part of the same command, or alone with `--tests 'org.example.project.ui.CodingSandboxTourTest'`)
shoots the sandbox and needs no showcase model: three runs of main.kts (one that finishes, one that throws in an API
call, one that doesn't compile), the console's places, the tooltip of one, the caret put on its line, and Recent
runs. The script, assembly.kts and the recent runs of the test AMP folder are put back as they were after.

While the harness runs, `AMP/github.json` lists no repositories, so the Packages tab stays off the network.

### UI goldens

The tour's shots are also a visual regression check. `amieApplication/desktopApp/src/test/resources/ui-goldens/` keeps
a golden PNG of every state the tour shoots, and this compares a new tour with them:

```bash
AMIE_UI_GOLDENS=check ./gradlew :desktopApp:testGoldens --no-configuration-cache --rerun
```

It runs in an AMP folder of its own, `build/golden-amp`, emptied first and given the seed's demo jar as its one
package, so nothing other tests leave in `build/test-amp` shows in a golden, and in a JVM of its own, so nothing
another test did once per JVM (the backend's file, the ORT cache) is missing from it either. `AMIE_UI_GOLDENS=check
./gradlew :desktopApp:testUi` takes the tour too, after the suite; the shots go to `build/ui-goldens` (or
where `AMIE_PAGE_SHOTS` says). A pixel has changed when one of its channels moved by more than 2 (of 255), and a shot
passes while no more than 0.002% of its pixels changed: about 30 of a 1500×1000 shot, a stray pixel but never a
word. What shows a value of the run, which the next run shows otherwise (a time, how long a thing took, the memory
figure of the status strip, a session's number, a folder's path), is marked on the page with `Modifier.variesByRun()` and left out, both
where the golden had it and where the shot has it. A check that fails names each shot that changed, how many pixels
and where, and saves `<name>.diff.png` beside the shots: the shot dimmed, what changed in red, what was left out in
dark blue.

When the change is meant, make the goldens again and commit them with it:

```bash
AMIE_UI_GOLDENS=update ./gradlew :desktopApp:testGoldens --no-configuration-cache --rerun
```

`update` writes the goldens, and `marks.json` with where each one showed something of its run, only once the tour
got to its end, and drops the golden of a state the tour no longer shoots. Something new on the page that changes
from run to run needs `variesByRun()`, or every check after it fails there. The goldens hold this machine's fonts and
text rendering (macOS); on another system, make them there first.

Two more switches turn on checks that need something outside the repository: `AMIE_CHECK_JARS=<folder>` checks
every jar in that folder as `initMod` and a fetch would (`JarPackagesScriptTest`, nothing of the jars is run),
and `AMIE_LIVE_GITHUB=1` lists the jars of the real GitHub repository (`GitHubJarsTest`). Coverage is on only when
asked for:

```bash
./gradlew :desktopApp:test -Pcoverage && ./gradlew :desktopApp:jacocoTestReport -Pcoverage   # build/reports/jacoco/test
```

The services themselves are tested in the Amie
repository (`README.md` there, "Testing"): unit tests for the backend and the S3 service, and
`scripts/e2e-auth-publish.py`, which runs sign-in, registration, publishing and SSO against the running stack.

## First start

1. The app opens on the **sandbox** with `main.kts` open. No account is needed: the sandbox, the models and the
   metrics work on this machine. A session saved in `AMP/session.json` signs you back in without asking; otherwise
   you are a guest, and signing in is under **Account** in **Settings** for when you want publishing, backups or the
   package repository; see [Signing in](sign-in.md).
2. Open the templates (the disk icon on the strip over the editor) and press
   **Load** on *Ask the dummy model*, then press **Run**. Everything the app writes goes into its AMP folder, which is
   `~/Library/Application Support/amie pilot/AMP` on macOS whether it was started with Gradle or installed; see
   [The AMP folder](amp-folder.md).

## The three screens

- **Sandbox** (the prompt icon, `>_`, first in the left sidebar): the editor with **Run** over it, and the output console.
  [The sandbox](sandbox.md).
- **Models** (the cube, second sidebar icon): every intent model with its metrics, data, requirements, console and
  sessions; the package jars and their collections; and every open session. Open to everyone; only
  **Publish** needs a sign-in. [Models page](models-page.md), [Models and metrics](models.md).
- **Settings** (the gears at the foot of the sidebar): your account with its backup and package search, UI scale, the
  editor, the AMP files. [Settings](settings.md).

The device dashboard (route `home1/`) is not reachable at the moment: no sidebar icon leads to it and the route
sends you on to the sandbox. Its screens are described in [Devices](devices.md).

The window's top bar (30dp, dragging it moves the window) is the page's own toolbar, as an IDE's, with no title:
the sandbox has its script's name, **+**, the env, **Run** and export there; the Models page its tabs; Settings a back
chevron and its sections as tabs. On Windows and Linux the window's minimize, full screen (F11 too) and close end it.
On a Mac the window is the system's, its title bar see-through under the bar: the red, yellow and green buttons sit at
its left, the green one (or **View → Enter Full Screen**, ⌃⌘F) gives full screen in its own Space, and Esc or the same
again leaves it. The account isn't there: who is signed in,
**Sign in**, **Create account** and **Log out** are all under **Account** in Settings.

At the foot of the window a 22dp status strip, as in VS Code, shows the process memory and the heap as text at the right.
Its ✕ hides it until the app closes, and **Memory** then appears in the sidebar to bring it back.
