# Models page

Route `models`, opened from the second icon in the sidebar (the cube), which the arrow chip at the far left of the
tab strip, right where the sidebar meets it, hides and shows (the arrow turns round while the sidebar is away) (the choice is kept in `AMP/settings.json`, as the sandbox's is). It is a full page (not a side panel) in
`components/terminal/window/controller/ModelsPage.kt` (its state and what each action does; each area of it is a
file beside it: `ModelsList.kt`, `ModelDetail.kt`, `ModelTabs.kt`, `ModelTry.kt`, `ModelImprove.kt`,
`NewModelPanel.kt`, `GroupsTab.kt` (a group's Try line in `GroupTry.kt`), `PackagesTab.kt`, `SessionsTab.kt`, `Notices.kt`, `ModelIcons.kt`,
`ModelsSnapshot.kt` for what it reads, `ModelsDecisions.kt` for what it decides (the list's order, what Improve
suggests, a queue row's chips, how a plan is worded), `FirstSteps.kt` for the first steps over the Models tab,
`PageWidgets.kt`), and it re-reads its state while open. What it showed last
is kept for the life of the app (`ModelsCache`), so coming back to the page shows the models at once; the first
read after start-up is local only (files, sessions, listeners) and the one slow read, the server's jar listing,
is fetched right after and cached for a minute by `JarPackages`. Nothing on the page waits for the network.

The page keeps to one quiet scheme, the same grays as the sandbox, and everything on it is square: no rounded
corners anywhere, in the window frame, the sidebar, the header, the chips, the status marks or the charts. There
are three kinds of button: **one filled dark-blue button per place** for the action
that creates, builds, validates, opens or fetches (**New model**, **Build**, **Open session**, **Validate**,
**Add**, **Create**, **Fetch**, **Save**), a **dark-red** one for what ends or removes something (**Stop**,
**Terminate**, **Destroy**), and **bare text** for everything else (the **Save** under ⚙ too), with a faint square under the pointer. The
chips that are on or off (methods, run numbers, console scope) are bare text too, lit in the tab strip's gray
when on; the label chips under a wrong answer and in the queue (and **+ new label**) have a thin outline while
off, and **⋯** shows a bordered square under the pointer. The icon chips (the sidebar's arrow, and the sandbox's)
are white icons on no square. Two hues sit on the grays, as accents and never as fills: **blue** for what is primary,
chosen or focused (the filled button, a link, the focus ring, a focused field's border, the open tab's bar, the selected
row's edge and its faint wash, the line of a build or a download under way) and **green** for what runs, succeeded or is
done (the status marks, the Run icon, the bar of a notice that ended well); red stays for what ends, removes or failed.
Nothing is vivid: the tokens the rest of the app shares (`AccentColor`, `AccentInk`, `AccentWash`, `SuccessColor`,
`DangerColor` in `ui/theme/Color.kt`) are muted too, so every screen calms down with the page. Muted is not faint, though: every word reaches 4.5:1 against what it is drawn on, and the icons,
the status marks and the focus ring 3:1 (WCAG 2 AA; `ContrastTest` holds each colour where it is used, the muted
words of a lit line one step lighter than the rest). On-screen words are kept short; the explanations live in the
tooltips.

## Finding your way

- **Every button, tab and status word has a tooltip** (`components/ui/viewport/Tooltip.kt`, `AppTooltip`): rest
  the pointer on it for half a second and a square dark box says what it does, or, on a disabled button, what is
  still missing. The status words (`inferring`, `2 open`, `on server`, `on GitHub`, `missing`) say what they
  mean for that row (a quiet state such as idle is only the gray mark and its tooltip); the status strip at the foot of the window explains its numbers.
- **Almost nothing is reachable only by right-click.** Every model, group and package row has a **⋯** button
  (shown while the pointer is over the row and on the selected row) and every detail panel header has one at its
  right end; a session row's *Copy log* and *Copy `#id`*, and a Maven Central hit's actions, are on right-click
  only. Both open the same square
  menu the right-click opens (`SquareContextMenu`), with the actions that end or remove something in red. A
  model's menu groups its actions under small headings (*Check*, *Improve*, *Groups*, *Share*, *Manage*). Esc or a
  click elsewhere closes it.
- **Slow actions say so on their button**: *Opening…*, *Starting…*, *Stopping…*, *Publishing…*, *Fetching…*,
  *Saving…* (then *saved* for a moment), *Rolling back…*, *Picking…*, *Asking…*, *Teaching…*, *Building…*,
  *Terminating…*, *Comparing…*, *Queueing…*, *Testing…*, *Removing…*, *Adding…*, and the
  button is disabled until the action ends.
- **Notices are readable**: what an action did, or why it failed, appears in a strip under the tabs with the whole
  text (it wraps and can be selected), a bar at its left edge that is red for a failure, and **Dismiss**. A notice
  after a change to a model's head (a fine-tune, calibration, retrain or rollback) also has **Undo**, which makes
  the head before it the model again; that notice has an **Undo** in turn. Destroying a model asks first in the
  same kind of strip, with the full consequence spelled out and a red **Destroy** button.
- **Forms open in the panel, not above the page.** **New model** and **New collection** take the place of the
  detail panel on the right while they are open, so the list never moves. While New model's form is open its
  button is a red **Close** (hidden while a build runs), and every form has a red × at the right of its hint
  line; either brings the selected item back. A form has no title, only a muted line saying what it takes. Everything that closes something inside the
  page (a form, the Try table, a comparison, a collection's jar list) is red, like the window's own close button,
  and the window header shows no page title. A build in progress is one line at the top of the list with a thin progress bar; a fine-tune or a
  calibration shows there the same way (*fine-tuning name*, *calibrating name*).
- **Empty states say the next step**: a filter that matches nothing names the query and offers **Clear**; the
  Sessions tab with nothing open says how a session is opened; the server group of the packages list says it is
  still asking the server; a model without a run says *no validation yet* beside **Validate**.
- **The first steps show someone new where to begin** (`FirstSteps.kt`): a strip over the Models tab names four,
  *build one from the sample data*, *ask it something*, *correct an answer* and *fine-tune it*, and ticks each once
  AMP shows it done, whatever did it (a script counts as much as the page): a model trained on data or scored (the
  dummy, only written, doesn't count, even validated); a run line in a model's `sessions.log` or a request its server
  answered; a labeled row in a model's queue, or a fine-tune on one; a fine-tuned head (or a fine-tune run, after a
  rollback past it). A step not done yet is a button that goes where it is done: the first opens **New model** with
  the sample data chosen, the second and third put the keyboard on the **Try** line of the selected model (the
  third on one that keeps texts, the only ones that take corrections; the first such in the list when the selected
  one doesn't), the fourth opens its **Improve** tab. Its tooltip names the model, or says what the step waits for.
  The strip shows while a step is undone; once all four are done its bar turns green and it says *all done* until
  the page closes, so an AMP where everything was done before never shows it (nor are the steps read again on that
  page, so one coming undone later doesn't bring it back). **Dismiss** hides it for good, kept in
  `AMP/settings.json`; Settings shows it again (*first steps* under **Models page**).
- **The command palette finds anything** (`CommandPalette.kt`): **⌘K** (Ctrl+K), or the icon at the right end of
  the tab strip, opens one field over the top of the page. What you type picks among every model, group, package
  and session (choosing one goes to its row; a session goes to its model, group or package, an ONNX session to the
  Sessions tab) and the actions of the item whose panel is open, the same ones its **⋯** menu lists, which run
  exactly as from the menu. The letters of each word you type have to come in order (`ftf` finds *Fine-tune on
  file…*), words in any order, and a word may also match the item an action belongs to (`file showcase`); the start
  of a name or of a word counts most. Up and Down move the lit line and Enter runs it (the lit line stays on its
  item while the lists change under it, a model added or a session opened); a click runs the line clicked; Esc or a
  click outside closes the palette.
- **The keyboard reaches the lists, the filters, the Try line and the tabs** (`PageKeys.kt`). While a text field
  has the keyboard, the keys it uses stay its own: only Esc and the ⌘ shortcuts reach the page. A click on a row
  takes the keyboard back to the list, and the tooltips of the tabs and the filters name their shortcut. **Tab**
  and **Shift+Tab** move between the page's controls, and the one that has the keyboard shows a thin blue square
  round it (only after Tab: a click leaves none; on a filled button a dark line keeps it apart from the fill); Space
  or Enter presses it (`FocusRing.kt`, the same on every page). For a screen reader, an icon that stands alone says
  what its tooltip says, an icon beside its own words says nothing more, and the filters are called "filter".

| Keys | Action |
|---|---|
| Up / Down | Select the row above or below in the list on show (models, groups or packages); the rows of a folded section are skipped, a package listed twice (in a collection and where it lives) is visited where it first shows, and the selected row scrolls into view once (opening a fold doesn't scroll the list back to it) |
| Home / End | Select the first or the last row of the list |
| Enter | Put the keyboard on the selected model's **Try** line (Models tab), or the selected group's (Groups tab); Enter there asks. On a control Tab moved to, Enter presses that control instead; on text Tab moved to (which presses nothing), it goes to the Try line as before |
| Tab / Shift+Tab | Move the keyboard to the next or the previous control; it shows a thin ring, and Space or Enter presses it |
| ⌘F (Ctrl+F) | Put the keyboard on the list's filter; Enter there goes back to the list with the filter kept |
| ⌘1 to ⌘4 (Ctrl+1 to Ctrl+4) | Models, Groups, Packages, Sessions |
| ⌘K (Ctrl+K) | Open the command palette |
| Esc | Close what is open, one thing per press: a menu; then a strip (the fine-tune plan, a flag, a new group, a rename, a destroy); then a form in the panel (New model, From Hugging Face, New collection, From Maven Central); then the filter's text; then leave the text field |

## Layout

The page's tabs are in the window's top bar, as the sandbox's tools are, so there is no row over the page: four tabs
with their counts, **Models**, **Groups**, **Packages** and **Sessions**, after the arrow chip that hides the sidebar,
and the command palette's icon at the right end. There is no back button: the sidebar leads to the other pages. Signing in happens under Account in [Settings](settings.md#account) and
on this page is only needed for **Publish**; everything else works without an account. (The website is not opened from the
page any more; a publish notice names the address that lists what you published.) Each tab but Sessions is a flat list on the left, a 1dp rule that can be dragged to resize the list, and one
bordered detail panel on the right for the selected item; the Sessions tab is one full-width list. The tab strip already names the section and counts its items,
so there is no title above the list: its first line is the filter box (its tooltip says what it matches) with
the one action of the list beside it (**New model**, whose form starts with where the model comes from, a csv or Hugging Face; **New group**; **Add**, which offers a jar from this machine
or a new collection; **Terminate all** on the Sessions tab, shown while something is open, its filter only past
four sessions).

At the foot of the window sits the status strip: what the process holds of the machine's memory, the heap against its
`-Xmx` limit, the native memory, and the BERT encoders loaded (the heavy part, shared by every session; one that was
cancelled is left out until its next text loads it again). It turns red near the limit. Its ✕ hides it until the app
closes and **Memory** in the sidebar brings it back; while it is hidden nothing reads the memory.

## Models

The list has one line per module in `AMP/models/intents`: the name, its last accuracy (or `not validated`), its version
once it has more than one (`v3`), `serving :port` while it listens, *in group…* for the groups it is in, its open sessions, the status word at the
right and the ⋯ button; a running one is
underlined in green. Models are grouped by the data they were trained on (the csv name the builder records in
`module.json`; the dummy sits under *written, not trained*, older models under *unknown data*), so the same data
trained twice sits together. Groups with a running model come first; inside a group the running models come
first, then the most accurate. The filter box matches the name, a label, the status, the data and the file a
model was validated on. Clicking a line selects it; the panel on the right shows that model only, with the status
line (listening address or *not serving*, the version once there is more than one, the data it was trained on,
*in group1, group2*; its tooltip counts
a listener's requests, abstentions and errors) under its name and
the actions at the right of the header:

| Action | What it does |
|---|---|
| **Open session / Terminate session** | The filled button. Loads the model (encoder and head) as a session, the same as `initMod("name")` in the sandbox, or closes it. A model has one session at most |
| **Listen on :port / Stop** | Starts or stops the HTTP listener (`runtime/ModelServer.kt`) with the settings as shown; the label names the port it will use |
| **⚙** | Opens the serving settings under the header: **Host / Port / Path / Methods**, **Min score** and **Fallback** (under the min score the model abstains, answering the fallback label or none: [Serving a model](serving.md#answering-only-when-it-is-sure)), with the min score its last run suggests and **Use**, then **Access**, folded until one of its fields is set: **Key** (shown as dots, with **New key** and **Copy**), **Origins**, **Max bytes** and **Per minute** ([who may ask and how much](serving.md#who-may-ask-and-how-much)); **Save** writes them all to `AMP/models/intents/<name>/serving.json`; a port outside 1–65535, a min score that isn't a number from 0 to 1, an origin that isn't one or a limit under 1 is named and keeps Save and Listen disabled (a min score of 0 is none); the order is Host, Port, Path, Min score (with its suggestion and **Use**), Fallback, **Access**, then Methods (POST/GET) on one row with **Save**, the POST and GET URLs with **Copy** and **Copy curl**, *applies after Stop and Listen* while it listens, and last **Requests**, folded: the last twelve lines of the model's `access.log`, newest first (time, status, ms, method and path, outcome), errors in red, the whole line with its request id in the tooltip ([Serving a model](serving.md)). The gear stays lit while the block is open |
| **Publish** | Uploads the model as `<name>.onnx`, `<name>.module.json` and `<name>.guide.md` to the Amie backend (`POST /upload`, `progLanguage=onnx`) with the Keycloak token, then asks the website to refresh its listing and says in the notice strip whether `/dashboard?username=<you>` lists the files, naming that address; a guest is sent to Account in Settings. A backend that cannot be reached ("Host is down", "Connection refused") is an address problem in `AMP/backend.json`, not a sign-in one |
| **⋯** | The whole menu: Open session, Listen/Stop; *Check*: Validate on sample data, Validate on file…; *Improve*: Fine-tune on file…, Calibrate on file…, Texts to label from file…, Roll back to v`<n>` (the newest kept head), Retrain on `<data>.csv`, Retrain on file…; *Groups*: Add to group …, New group with …; *Share*: Publish to the website, Remove from the website; *Manage*: Rename…, Reveal folder, Copy `initMod("name")`, Terminate model, Destroy model…. Only what applies shows: Open session without a session, *Stop listening* while it listens, Fine-tune, Calibrate and the two Retrains not during a build, Texts to label only for a model that keeps texts, Roll back only with a kept head, Terminate model only while it runs; for a guest Publish and Remove read *(sign in)* |

**Try** is the line under the header, above the tabs (*Ask it something*): type a sentence and press Enter (or **Ask**). The answer
shows under it: the label, its score, the next three labels with theirs, and how long it took. The first question
opens the model's session if none is open (the button says *Opening…*), as **Open session** does, and the session's
log keeps the line. When the answer is wrong, the line under it, *wrong? it is*, has a chip for each of the other
labels (the likelier first, the rest behind **more**) and **+ new label** for one the model doesn't have yet.
A chip puts the sentence in the model's queue with that label (`teach` in `jetbrainDLBuilder/LabelQueue.kt`), and
the line then says *taught*, with **Show the queue**, which opens the Improve tab. A model that doesn't keep
texts answers the same way but offers no correction, since nothing it is asked may be written down; the line says
so.

**Several lines pasted** into the Try field at once are a batch, asked one by one: only the lines pasted, not the
sentence the field held, which stays in the field (less whatever the paste replaced, so a paste over a selection
takes the selection away); blank lines and repeats are left out, and past 200 lines the rest are too (the line says
how many). One line pasted goes into the field as text. Lines pasted while a sentence or a batch is still being
asked start nothing, and *still asking: paste once it ends* shows beside the field for a moment. The answers show as a table under the field in
place of a single answer: each sentence with its answer and score, the runner-up and its score, and the
milliseconds, with *unsure* beside the answers whose two best scores are less than 0.2 apart (the row's tooltip says
how far apart). Past eight rows the table scrolls, so the tabs under it stay where they are. The line over it says
how far it got (*asking 4 of 10…*, with **Stop**), then how many were asked, how many it was unsure of and the mean
time of an answer. **Copy as csv** copies the table as `text,answer,score,runner-up,runner-up score,ms`, quoted
where needed. For a model that keeps texts, **Queue the 3 unsure** puts those sentences in its queue as rows to
label, each with its guess, its score and the runner-up, after the rows there; a text the queue has already stays as
it is. The line then says how many went in, with **Show the queue**. A model that doesn't keep texts is never offered
this. **×** closes the table, and a sentence asked on its own takes its place. Rename and Destroy wait while a batch
is asked; Stop ends it, keeping the answers so far, as does Terminate session.

**Rename…** opens a strip at the top of the page with the current name in a field; **Rename** is enabled once the
new name is a module name (letters, digits, dots, dashes, underscores) no other model has. The folder under
`AMP/models/intents` moves, `module.json`, the guide's title and `modules.json` say the new name, its queue of texts to
label (`AMP/<name>-to-label.csv`) is renamed with it, labels and all, the sessions on it are closed, and a listener
comes back under the new name (a serving path that was the old default becomes the new default). A trained model can
be renamed like any other; scripts then open it with `initMod("newname")`. A name whose queue file is in `AMP/`
already, one no model owns (left by hand, or by a model of that name destroyed before destroying removed queues),
is refused rather than merged: the renamed model would take those labels for its own and fine-tune on them. Move or
delete that file first.

**Destroy model…** asks first: the strip names the folder that goes, with every run and its logs, and the model's
queue when it has one. The csv files it was trained on stay in `AMP/`, since other models may use them.

Neither is offered in the middle of something: while the model is being built, trained again, fine-tuned,
calibrated or validated, **Rename…** and **Destroy model…** say to wait for that to end, and so do **Rename** and
**Destroy** in their strips when one of those began while the strip was open (the strip stays, for once the model
is free). **Roll back** and a notice's **Undo** wait the same way. Whatever still ends after a rename or a destroy
(a fine-tune started from a script, a regrade of the queue, texts to label being picked) writes nothing back under
the old name, so a destroyed model doesn't come back and a renamed one gets no twin; its console line says why. A
build doesn't write over a model renamed to its name meanwhile either, and a fine-tune or a calibration doesn't
write over a head the model got meanwhile (rolled back, trained again, or destroyed and built anew).

**Retrain** trains the model again under the same name: on the csv it was trained on as it is now (edit or add
rows to the file, then retrain), or on another csv (**Retrain on file…**, also **Other file…** on the Data tab).
Nothing that opens the model has to change: the head is rebuilt in place, the sessions on the old head are
closed so the next `initMod` opens the new one, a listener comes back on it, the description, icon and guide stay
(also ones changed while it trains, as with a fine-tune or a calibration), and the Metrics tab keeps every earlier
run beside the new one, so the runs show whether the new data helped.
Only one build runs at a time; the line at the top of the list follows it as for a new model.

**Fine-tune on file…** (⋯ menu, the Improve tab, and at the right of **Files** on the Data tab) teaches the trained
model more from a csv instead of training it again: a label it doesn't know yet (three rows of it at least) gets an
output of its own, and rows it got wrong move it towards the right answer, while the rows it learned from before
are replayed and its weights are held near where they were, so what it knew stays. Nothing starts at once: a strip
at the top of the page first says what it would do (`planFineTune` in `jetbrainDLBuilder/FineTune.kt`, worked out
without training): the rows and which of its labels they are, the new labels and their rows, the earlier rows it
would replay and from which files, the rows the preprocessor would drop, and that the version it has now stays.
It warns about a new label spelled like one it has (*light* beside *lights*), rows that give a text it learned
under another label, and files it was trained on that are gone from `AMP/` (nothing to replay). A new label with
fewer than three rows is named in red, and so is anything that keeps the fine-tune from starting; **Fine-tune**
stays disabled then, and its tooltip says why. **Fine-tune** starts it, **Cancel** changes nothing. The notice
says the versions and the accuracy before and after on the same held-out rows, with **Undo**, and the Metrics tab
has the run. It is `fineTuneClassifier`, the same as `fineTune(...)` in a script, with its default options
([Fine-tuning](models.md#fine-tuning-teaching-a-model-more)).

**Calibrate on file…** fits one temperature on labeled rows the model hasn't learned from and folds it into the
head, so a score of 0.8 is right about 80% of the time; the answers, and so the accuracy, stay the same. The
notice says the temperature and the calibration error before and after, with **Undo**.

Every retrain, fine-tune and calibration keeps the head it replaces (the ten newest, in
`AMP/models/intents/<name>/versions/`). **Roll back** (on a kept head under **Versions** on the About tab, or **Roll
back to v`<n>`** in the ⋯ menu for the newest one, and under **Versions** on the Improve tab) makes that head the
model again as a new version, with the labels and the scores it had; the head that answered is kept in turn, so a
rollback is undone the same way, which is what the notice's **Undo** does.

**Compare** on a kept head asks it and the head that answers now about the same labeled rows, changing nothing: no
run is added and no session opened. It offers the csv the model was trained on, the one it was last validated on and
the ones fine-tunes taught it (those still in `AMP/`), or **On a file…**. Under the list then: *v1 → v3 on 40 rows
of check.csv*, one line with accuracy and macro F1 then and now and how many rows it answers right now that it
didn't and wrong now that it got right (*every row answered as before* when none moved), and one figure (`plots/compare.png`): each label's F1 then (a dark square)
and now (a light dot) on one axis, with accuracy and macro F1 under them, and the confusion change, how many more or
fewer rows of each label got each answer (then→now in the cell; on the diagonal more is better, off it fewer). Both
heads are scored over the labels of both, so a label one of them doesn't have reads *new* or *gone*, and its rows
count as answered wrong by that head; rows of labels neither has are left out and counted. For a model that keeps
texts the rows answered differently follow, those it gets wrong now first (fifty show; the tooltip has both scores);
one that keeps no texts gets the counts only. The comparison stays until **×** or until the model's head changes,
and shows on both tabs. One comparison runs at a time: **Compare** waits while another runs (of any model), while a
build runs and while the model rolls back, and **Roll back**, **Undo**, **Rename** and **Destroy** wait while a kept
head of the model is compared. A fine-tune or retrain started meanwhile isn't held up; the comparison, of the head
before it, then doesn't show. The rows' vectors go to the embedding cache only when both heads keep texts.

**Texts to label** writes the texts the model is least sure about, closest calls first, to
`AMP/<name>-to-label.csv` with its guess beside an empty `label` column: from what it was asked over HTTP (the
button at the right of **Ran on** on the Data tab, shown once it has requests) or from a csv or txt of texts
(**Texts to label from file…** in the ⋯ menu, **Find texts to label** on the Improve tab, whose menu also offers
**Varied close calls**: texts unlike each other among the closest calls, [What to label next](models.md#what-to-label-next)). That file is the
model's queue: the Improve tab lists it to label with a click, and corrections made on the Try line land in it too.
It also shows under **Files** as *to label*, with how many rows have a label so far, **Open in viewer** to fill
them in by hand, and **Fine-tune on it** once at least one has. Rows labeled already stay at the top when you ask
again. A model that doesn't keep texts has neither action and no queue.

Under the header the panel has its own tabs, each with a tooltip that says what it holds:

| Tab | What it shows |
|---|---|
| **About** | The description (editable; **Save description** appears under it once it has changed and writes `module.json`), then the **Icon**, **Details**, **Training** and **Versions** blocks (**Icon**: a glyph from the set, or **Upload image**, kept as `models/intents/<name>/icon.<ext>`; Training also has its validation rows, the heads tried and what the preprocessor did). Every uppercase block title on these tabs is a line that folds its block away and back (open by default): **Details**: created / encoder / head / labels (the model's folder is in Settings, **Model folders**); **Training**: architecture, data, the version and what made it (*v3 · fine-tune of v2*), the files it was fine-tuned on, the temperature when it is calibrated, whether it keeps texts, rows, epochs, batch size, learning rate, hidden units, final loss, train accuracy; **Versions**: the head that answers now and every kept one, each with what made it, when, its labels and accuracy (the tooltip has its head and note), and **Compare** and **Roll back** on the kept ones |
| **Improve** | Making the model better in one place; its count is the labeled texts of the queue it still answers otherwise. **What would help** (shown when something would): what its last run and its requests suggest, each with the button that does it: the dummy's fixed weights (**Fine-tune on the sample data**), labels with an F1 under 0.6 (**Fine-tune on file…**), the labels it takes for others (*“lights” taken for “weather” 6×*: every pair of the last run's confusion matrix off the diagonal with two rows or more, the most first, three named; a model that keeps texts gets **Show the rows**, a menu of the pairs when there are several, which opens the Metrics tab on that run with **answered wrong** open on the pair's rows and brought into view, and a model that keeps none gets the counts and **Fine-tune on file…**), scores that don't say how often it is right (calibration error 0.1 or more and not calibrated: **Calibrate on file…**), requests over HTTP not looked at (**Pick texts to label**), training rows that look mislabeled (**Open** the csv). **Teach it**, with **Find texts to label** (from its requests or from a file, under **Closest calls** or **Varied close calls**, texts unlike each other among them) and **Fine-tune on file…** at the right: the queue, one line per text with what the model guessed and its score, a chip per label (the one it has lit; a click labels it, a second click takes the label away; with many labels only the likely ones are chips and **other label** has the rest), **+ new label**, and **×** to take the text out; the first eight show, then **Show all**. A labeled text the model answers right shows ✓ *answers it*. Under the queue, how many texts are labeled and how many of them it answers right, and **Fine-tune on *n* rows**, which fine-tunes on the labeled rows of the queue through the same strip as any fine-tune; once it answers every labeled text right the button steps back to **Fine-tune again**. After every change of the head made on the page (a fine-tune, calibration, retrain, rollback or undo) the queue's guesses are worked out again with the new head (`regradeQueue`, texts embedded before come from the cache), so the queue shows what the model answers now. **Versions**: the same list as on the About tab, with **Compare** and **Roll back**. **Scores**: whether it is calibrated, its calibration error on the last run, and **Calibrate on file…**. **Retrain**: **Retrain on `<data>.csv`** and **Other file…**. A model that doesn't keep texts has no queue; Teach it says to fine-tune it from a csv |
| **Guide** | `AMP/models/intents/<name>/guide.md`, the guide shipped inside the model, rendered as light markdown; **Edit** and **Save** change it (**Cancel** while editing; before a guide exists it reads *default guide, not saved yet* with **Edit and save**), and it goes along when the model is published. It holds the **model card** (version, data and rows per label, earlier versions, the last run's scores, calibration, what it mixes up, its texts), written again after every run and on Save between two marker lines the tab doesn't show ([the model card](models.md#the-model-card)); what is being typed stays as typed when a run writes it |
| **Metrics** | One line: the run chips (the latest first, each chip's tooltip names what made the run, its data, rows, accuracy and time) and **Validate**, which offers **On the sample data** and **On a file…** and adds a run (`metrics/ModelValidation.kt`). Under it the run: when, *made by* (build, fine-tune, calibration, validation or rollback, and the version), the rows, the scores (in brackets, where each fell in 1,000 resamples of the rows, so how far it can be trusted: [how far the scores hold](models.md#how-far-the-scores-hold)), *before* for a fine-tune or calibration (the head before it on the same rows), *calibration* (its calibration error and temperature) and a *note*, then the run's warnings, a line each (the one about rows that repeat a row it learned from names them in its tooltip, or says that a model that keeps no texts keeps only their count: [rows it learned from](models.md#rows-it-learned-from)). A run that came from a build has **Heads compared**: every head the build trained, with accuracy, macro F1, train accuracy and loss on the same validation rows (and *cv F1*, the mean over the folds ± their spread, when it cross-validated), F1 per label under each, what the diagnostics said of it, and the chosen one marked; a head's tooltip adds its cross-validation and where early stopping stopped. The chosen run's charts follow: scores per label (with macro F1's range as a bar under its mark), the confusion matrix, how the heads learned (every head's accuracy on its training and validation rows, epoch by epoch, the kept epoch outlined) and how far the scores can be trusted (reliability bars against the diagonal, and what answering only above a score keeps), then **answered wrong** (the validation rows it got wrong, the surest first; a run keeps the 25 it was surest about. Opened from **Show the rows** it lists one pair's rows under a chip, *lights → weather*, that shows them all again, as does choosing another run; when the run kept fewer of the pair's rows than it got wrong, a line says how many) and **labels that look wrong** (training rows the cross-validated heads doubt), both folded, then scores by run and, under its own **From the sessions** block, how sure the model is and how long an answer takes, from the sessions log. While a validation runs, Validate reads *validating n/total*. The charts are PNG files drawn by `metrics/MetricsImages.kt` into `AMP/models/intents/<name>/plots/` whenever the metrics or the log change, in one muted slate hue; a run from before the curves and calibration were kept draws its two charts as before |
| **Data** | **Files**, with **Fine-tune on file…** at the right: the csv files it was trained, validated and fine-tuned on and the queue to label, each with rows per label and text lengths as bars, a folded preview and **Open in viewer**; the trained-on file has **Retrain** (the same name, on the file as it is now) and **Other file…**, the queue to label has how many rows are labeled and **Fine-tune on it**; then **Ran on**: what its sessions answered and what the server served, with **Texts to label** once it has requests |
| **Requirements** | `metrics/ModelRequirements.kt`: everything it needs to run, checked on this machine, each line marked met / not met / information (the mark's tooltip says which). `initMod`, **Open session** and Listen refuse a model on the lines about the model itself, the encoder, the head and the labels |
| **Console** | The console lines that name the model, or with **all models** everything the models, packages and sessions logged; the box's height can be dragged |
| **Sessions** | The sessions it owns, each with **Log** and **Terminate** |

### Icons and groups

Every model has an icon: the chip by default, or one from a small catalog (lightbulb, cloud, chat, home, music,
shield, star, bolt, heart, language, science, work, pets, school, cart, hub, groups) chosen under **Icon** on the
About tab and kept in `module.json`, or an image of your own: **Upload image** there copies a png, jpg or svg
into the model's folder as `icon.<ext>`, which then shows in the list, on the panel header and in groups (and
goes along when the model is published); **Remove image** goes back to the catalog icon.

No row says *idle*, *running*, *stopped*, *open* or *closed*: the green underline under a row is running (a
session open, a listener on, a swarm open), its absence idle. Only the other states keep a word (*inferring*,
*on server*, *2 open*, *missing*).

Groups have their own **Groups** tab: every group of every env in a list (icon, how many models, which, the
underline while its swarm is open), folded by env, and the selected one in the panel. **+ New group** there makes
an empty one to fill with **Add model**.

A **group** is a named set of models that are asked together: a swarm. A model's ⋯ menu offers **Add to group
<name>** for every group it is not in and **New group with <model>…**, which opens a strip at the top of the page
asking for the name and the **env** the group belongs to. Groups live in the env, as packages do: each env keeps
its own in `AMP/env/<env>/groups.json`, a script sourced in that env finds them with `group("name")`
(the default env first, then any), and `internalEnv()` lists them with the packages. Selecting a group on the Groups tab shows its panel: **Open swarm** / **Terminate swarm**, the
models (a row each; **Detach** takes one out; **Add model** adds one), the icon picker, where it lives and, while
open, the swarm's session with its log. **Remove group** leaves the models alone. A destroyed or renamed model is
taken out of (or renamed in) every group. A group with a model that keeps no texts keeps what it is asked out of
every log: its own, its console lines, and those of its other models, which write each text's length instead; a
text asked of one of those models directly is logged as its own `module.json` says.

A group's panel has a **Try** line under its header, as a model's has: a sentence asked of all its models at once,
the way its swarm asks them (the first question opens the swarm, as **Open swarm** does, and Enter on the list comes
here). Under it shows the group's answer, each label's best score across its models (what `predict` and `inference`
give in a script) with the three labels after it; then the vote, the label most of its models give (a tie goes to
the higher best score), with how many give it; then a row per model with its own answer and score and the two
labels after them. A model that gave none says so: it couldn't be opened, it failed (its Console tab says why), or
it joined the group after the swarm opened. The answer goes once a model is added to the group or detached from it,
since its rows and vote were of the models before. A group's answers aren't corrected here; each model is taught on
its own page.

Groups are also made by **dragging** on the Models tab: take a model's row and drop it on another model's row
to open the new-group strip with the two of them, or on the zone that appears over the list while you drag
("drop here for a new group") for a new group with that one; adding to a group that exists is **Add to group**
in the ⋯ menu or **Add model** on the Groups tab. The ghost beside the pointer says what
the drop would do, and the row under it is lit. A click without movement still selects the row.

### New model

**Check data** next to **Build** runs the preprocessor on the chosen file without training and shows its report
under the fields: what it would clean, drop (with the reason) and refuse, and whether a build could start. The
reasons are explained in [Models and metrics](models.md#before-training-the-preprocessor).


**New model** beside the filter box opens the form in the place of the detail panel, and **Close** stands where the
button was while it is open. The form's first line, **From**, says where the model comes from: **A csv** (the form
below) or **Hugging Face** (the one in [From Hugging Face](#from-hugging-face)); the chip switches the form, and
while a build runs only Hugging Face is offered. The csv form is open on its own when there are no models: **Name**,
**About** (a sentence kept in `module.json`), **Data** (Choose a csv with a text
column and a label column, or **Sample data** for `AMP/sample-intents.csv`, 45 rows over `greeting`, `lights` and
`weather`; the name is filled from the file name) and an optional **Test** file to validate on instead of a 20%
split. **Options** are chips, all off but *keep texts*, which is the build as it always was: **cross-validate**
scores every head over 5 folds and keeps the best mean (and lists the rows whose label the heads doubt),
**more heads** compares 12 heads instead of 4, **stop early** ends a head's training once it stops improving and
keeps its best epoch, **calibrate** fits the temperature as part of the build, and turning **keep texts** off
makes a model that never writes a text down, for one that answers other people. Each chip's tooltip says what it
costs. As soon as the data file is chosen the form reads it and shows how many samples and which labels it holds,
with **rows per label** as bars, the most first, so a label with few rows shows before the build.
While **Build** is disabled the line beside it (and its tooltip) says what is still missing: a data file, a name,
or a csv without the two columns. **Build** runs the same `buildClassifier` the sandbox's `modelBuilder` runs, on
a background thread; while it runs one line at the top of the list shows the phase, step and a thin progress bar,
and under it every head scored so far (architecture, accuracy, macro F1, train accuracy, loss, the chosen one
marked once all are compared; the tooltip of a line has its F1 per label), so the comparison is visible while the
next head trains. When it ends the new model is selected. Only one build runs at a time. **Close** brings the
selected model back.

A csv chosen from anywhere else on the machine (**Data**, **Test**, **Validate on file…**, **Retrain on file…**)
is copied into `AMP/` first (a newer copy replaces the one there), so the model finds its data again by its name
later: the Data tab, the list's grouping and a retrain all look the file up in `AMP/`. A file already under `AMP/`
is used where it is.

A fresh checkout has one component already: the **dummy** model (`modules/AmpModules.kt`, `ensureDummyModule`),
a fixed one-layer head over the encoder with the labels `greeting`, `lights`, `weather`. It is written when
`AMP/models/intents` is empty, so the page always has something to configure and `initMod("dummy")` works before any
training. The sample csv (`ensureSampleData`) has the same labels, so the first thing to try is **Validate on
sample data** on the dummy (its weights are fixed, so expect poor numbers) and then **+ New model** with
**Sample data** to see what a trained model's metrics look like.

### From Hugging Face

The **Hugging Face** chip on the first line of the **New model** form opens this one in the place of the detail panel.

**Search** finds models on the Hub in the formats `initMod` opens. Under it, a menu picks the task, as the Hub files
models (**Any task**, the default; **Text classification**, **Token classification**, **Feature extraction**, **Text generation**,
**Text to speech**, **Speech recognition**, **Audio classification**, **Image classification**), and the chips narrow the
format: **All supported** (the default), **ONNX**, **TorchScript**, **TensorFlow** and **TFLite**. A task with no words
lists the most downloaded models of it, so the speech models or the classifiers of the Hub are a click away. The search
waits until typing pauses (300 ms), and a new search cancels the one under way. Each row is a repo: its id, its task,
downloads and likes, and at the right the formats it has. The formats are read off the repo's files, so a repo that only has weights is left out. TorchScript has no
tag of its own on the Hub (`pytorch` mostly means weights), so a TorchScript search keeps only repos with a `.pt` or `.pth`
file. When the Hub doesn't list a repo's files, the row says *Check the files*. **Show more** brings the next results. A
search with no results says so, and so does one that can't reach the Hub. Clicking a row looks that repo up.

**Repo** takes a repo id (`owner/name`) or a huggingface.co address, including a folder's (`/tree/main/onnx`) or a file's
(`/blob/main/onnx/model_quantized.onnx`). **Look up** asks the Hub what the repo holds at that revision (the default
branch unless the address names another). Nothing is downloaded at this point, beyond the repo's `config.json` when
it has one (read for the model's kind and its labels). The panel then shows:

- the repo, its commit, its **Task** as the Hub files it, and from its config the **Model type** and the **Labels** it
  answers with (`id2label`, the first twelve and how many more), so a classifier's labels are known before it is
  downloaded;
- the runnable files by format, each with its size: **ONNX** (`*.onnx`, the `onnx/` folder and the quantized variants,
  with an ONNX file's external data), **TorchScript** (`*.pt`, `*.pth`), **TensorFlow SavedModel** (`saved_model.pb` with
  `variables/`) and **TFLite** (`*.tflite`);
- **Not runnable here**: weights-only files (`*.safetensors`, `pytorch_model.bin`, `*.gguf`, `*.h5`), each with a short
  reason, shards counted as one row;
- the default pick, already chosen. That is the file an address named, or else a plain `model.onnx` (the root's before
  `onnx/`'s), a full model before an 8-bit variant and an 8-bit variant before fp16, then the other formats. Click another
  file to change it. An encoder and decoder pair (`encoder_model.onnx` with `decoder_model.onnx`, a T5, BART, Marian or
  Whisper export) is taken whole, the full variant of each; `amie hf import` offers every encoder and decoder of the
  folder numbered and takes the numbers you give, and `initMod` opens the pair as two sessions (see [Encoder and decoder
  pairs](sandbox-scripting.md#encoder-and-decoder-pairs));
- **Comes with**: the config and tokenizer files that go along with the pick (`config.json`, `tokenizer.json`,
  `tokenizer_config.json`, `special_tokens_map.json`, vocab and merges files, `generation_config.json`), from its folder
  and the folders above it, and what a speech or an image model needs besides: a `voices/` folder (a Kokoro export's
  voices), the `.onnx.json` of a Piper voice, `tokens.txt`, `lexicon.txt` and `espeak-ng-data/` of a VITS export, a
  `labels.txt` or `classes.txt`, and a pair's `preprocessor_config.json`, which says how a sound or an image becomes its
  encoder's input;
- **Name**: the folder under `AMP/models/imported`, which defaults to the repo's last part, made a folder name.

**Download** runs in the background, apart from the page, so leaving the page leaves it running. A line at the top of the
list shows the file coming in, how much has come and a progress bar, with **Cancel**, which throws away what came. Each
file arrives as `<path>.part` under `AMP/models/imported/.incoming/<name>/`. Its size is checked against the Hub's before
it takes its name, and only once every file is in does the folder move to `AMP/models/imported/<name>`, in one rename. A
closed app, a lost connection or a full disk never leaves a half file under its real name. The next download of the same
commit goes on from what came: a whole file isn't asked for again, and a `.part` is resumed. When the download ends, the
model is selected and a notice gives its `initMod` line.

Each failure says what happened and what to do: no such model or revision, no connection, a gated or private repo (with or
without `HF_TOKEN`), nothing that runs here, no room on the disk or a folder that can't be written, a file that came in
short. A token for a gated or private repo comes from the `HF_TOKEN` environment variable alone. It goes only to
huggingface.co, never to the storage a big file is sent on to, and it is never logged, printed or stored.

Imported models are listed under their own fold, **Imported**, after the intent models. Each row shows the model's
format, its task when the Hub filed it under one, its source repo and its size, and the filter finds a model by any of
them or by one of its labels. Its panel shows the task, the labels its `config.json` names (written into `source.json` as
the model came in, so nothing is opened to say them), the commit, when it was downloaded, its folder and every file.
**Delete** (or **Delete…** in its ⋯ menu) asks first, then removes `AMP/models/imported/<name>`. A folder put there by
hand is listed too, its format read off its files.

## Packages

Jars come from six places, each a fold of the list: `AMP/pluginDir`, the env, Maven Central, the website's
downloads page (**On the server**), GitHub (**On GitHub**, the repositories in `AMP/github.json`) and the
website's **store**: the packages people published under their
accounts (`/api/all-packages`), each with its author (**by name** on the row). **Fetch** downloads a store jar
from the address its listing gives into `AMP/pluginDir`, as does the first `initMod("name.jar")`.

The list has one line per package jar a script can open, first under the **collections** and then under the
folding groups by place: **In AMP/pluginDir**, **In the env**, **From Maven Central**, **On the server** (the
website's `/api/jars`, else the S3 service's `/api/aws/jars`; a line says so while the server is still being
asked), **In the store** and **On GitHub**, the last three folded by default. Each line shows the Java the jar
needs and its version, and at the right `2 open`, `inferring`, `on server` or `on GitHub` (an idle jar has no
word) (the tooltip says what that means) and the ⋯ button; an open one is underlined in green. The filter
box matches the jar name, version, Java, a collection name, the class a session opens on, and any class or
resource name inside the jar, so a class can be found without knowing which jar holds it. **Add** beside the
filter box offers **Jar from this machine…** (copied into `AMP/pluginDir` and imported into the env), **From
Maven Central…** and **New collection…**.

### Repository

Under the installed packages, the **Repository** section searches what the Amie server offers (`JarPackages.listing`,
the jars of the website's downloads page and its store, and of the GitHub repositories in `AMP/github.json`), by name,
version or author. **Fetch** on a result downloads the jar into `AMP/pluginDir`, where a script opens it with
`initMod("x.jar")`; a jar already there says *on this machine*. When the repository can't be reached the section says
why, naming the addresses it tried, instead of showing an empty list, and **Try again** asks again at once; what could
be read still shows beside the reason. It needs an account: a guest sees why, with **Sign in to search**, which leads to
Account in [Settings](settings.md#account), and the repository is asked nothing.

### What a package needs

A package jar's panel has three blocks the libraries do not: **Requirements**, every line marked met, not met or
information: the Java it needs against the Java this app runs, its package class, whether it ships metadata,
its config folder, each config file and environment variable it declares, and what its last test run came to. A
required file that is missing or still its template, or a required variable that is not set, is not met, and
`initMod` refuses the jar until it is;
**Config**, the folder `AMP/pluginDir/config/<jar>/` with **Reveal folder**, where the package's files are read
from (as resources, through the `amie.package.config` property and a `configure(...)` method; templates for the
missing files are written on the first open) and the declared files with their state; and **Tests**, the test
runs the jar declares and the last run's answers, each marked pass or fail. **Test run** in the header (also in
the ⋯ menu) opens a fresh session with the configuration in place, runs the declared tests (or one call with
"hello" when there are none), closes it and reports in the notice strip and the block, where every test shows
what it asked (`run("…")`) and the whole answer line by line, with the reason when it failed.

Under the header the jar's **flags** say what it works with at a glance, as coloured tags: its version (blue),
the Java it needs (slate, red when this app is too old for it), its language (violet) and how its last test run
went (green or red), then the jar's own flags from its metadata (`flags: [{"text": "stable", "color": "green"}]`)
and the ones you add with **Add flag** (also in the ⋯ menu): a word and one of six colours (slate, green, amber,
red, violet, blue), kept in the sidecar metadata beside the jar and removable from the ⋯ menu. The same flags
show on the jar's row in the list. A jar with no metadata
gets **Write metadata template** in its ⋯ menu, which writes `AMP/pluginDir/<jar>.package.json` to fill in. The
format is in [Sandbox scripting](sandbox-scripting.md#what-a-jar-needs-metadata-configuration-and-test-runs).

### From Maven Central

**Add → From Maven Central…** opens a form in the place of the detail panel with one box. Type a name (`gson`,
`okhttp`), a class name, `group:artifact` for the versions of one artifact, or paste a full
`group:artifact:version` as in a Gradle dependency; Maven Central is asked as you type and the hits are ordered
here, the artifact named like what you typed and the well-known groups first (the server's own order buries
them). Every hit is a row with
its newest version and publication date and its own **Add**, which resolves that version and its dependencies
into the env (`AMP/env/<env>/env.lock.json`, files under `AMP/env/caches`), exactly what
`implementation("group:artifact:version")` in a script does. A pasted coordinate is the first row. A thin bar
shows while the library is being resolved, and the form closes itself when it is done: the library then sits
under **From Maven Central** in the list, selected, with the same Source, Build and Contents sections as any jar
(the coordinate is in Source), a **Remove** button that takes it out of the env (the files stay cached), and a
menu that copies the `implementation("…")` line. A script can import its classes from the next run without
declaring it. These are libraries, not packages: nothing opens them with `initMod`.

### Collections

A collection is a folder of jars that belong together, kept in `AMP/pluginDir/collections.json`
(`pluginEngine/PackageCollections.kt`), typically the jars that run on one Java: "jvm21" with every plugin that fits.
**Add → New collection…** opens the form in the place of the detail panel: a name, the jars to put in (toggles),
and a **By Java** shortcut per Java version found among the jars (`jvm17`, `jvm21`, …) that names the collection
and picks every jar that runs on that Java or older. Each collection is a fold at the top of the list with its
jars as rows; moving the pointer over a row shows **Detach**, **Add jar** lists the jars that are not in it yet (and turns into a red **Close** while that list shows),
and **Delete folder** removes the collection but keeps the jars. A jar can be in several collections, and its panel header says which.

The detail panel describes the selected jar without running anything (`pluginEngine/JarInspector.kt`, read once
per file version). Its header has the status line (status, `java 17+`, `kotlin` or `java`, version), **Fetch**
(filled) for a jar still on the server or **Publish** for a jar on this machine (uploads it to the website under
your account; the server refuses a jar without `run(String): List` and `close()`, with the reasons in the
notice), and the ⋯ menu with the same plus **Open its page on the website**, **Reveal file** and Copy
`initMod("name.jar")`; its ⋯ also has **Reveal config folder**, **Detach from collection** and **Remove flag …**
when they apply. Under it a row of the jar's flags, the `initMod("name.jar")` call, then folding sections, closed
until opened, except Requirements when something is unmet or the jar ships metadata, Config when it declares files
and Tests once they ran:

| Section | What it shows |
|---|---|
| **Source** | where it is, the website page, the exact download URL, when it was fetched and published (from `<jar>.source.json`), the file |
| **Build** | what built it (the manifest's `Created-By`, `Build-Jdk-Spec`), the class file version and the Java it needs, whether this app's Java can load it, language, size |
| **Contents** | the class a session opens on (the one with `run(String)` and `close()`) and the `Main-Class`; a filter box; **Classes** grouped by package and **Resources** grouped by folder, each group folding; the entry class and main class are marked; clicking a line copies its full name |

Then **Sessions**: the sessions open on it, each with **Log** and **Terminate**. There are no Run or Quit
buttons: a package is a bottle, not something the page controls. A script opens its own instance with
`initMod("name.jar")`, calls `run(text)` (or `answer(text)`) and `close()`, and a jar that is only on the server is fetched into
`AMP/pluginDir` on first open (see [Packages](sandbox-scripting.md#packages)).

## Sessions

(What a session is and how to use one from a script is in [Running sessions](sessions.md); this is the tab.)

The tab lists everything that is open as flat rows named after the owner (`dummy  #3`, `echo-1.0.jar  #1`) with
what it is (intent model, package, model group for a swarm, ONNX session, session model, background template), the script block
that holds it (`static search`, `timed search, 40s left`, `closable`; see [Scope functions](sessions.md#scope-functions)), *inferring* while it answers (idle is
the gray mark), how many runs it has done and for how long
it has been open; with more than a few, a filter box matches owner, kind or `#id`, and **Terminate all** sits
at the right of that first line. **Log** unfolds what the
session was asked and answered: one line per `inference` call (`"turn the lights on" -> lights:0.97,
weather:0.02 (12 ms)`), plus open, close and error lines, with the time of each. The same lines are appended to
the owner's file, `AMP/models/intents/<name>/sessions.log` for a model and `AMP/pluginDir/<jar>.sessions.log` for a
package, so the history of a terminated session is not lost (`runtime/SessionLog.kt`). **Terminate** ends a
session: intent model sessions (from **Open session** or `initMod("name")`; `runtime/ModelSessions.kt`), package
sessions (`pluginEngine/JarPackages.kt`; terminating calls the package's `close()` and drops its class loader)
and ONNX Runtime sessions opened on a bare file with `importModelOnnx` (`modelLoaders/OrtSessions.kt`;
terminating cancels in-flight runs; these keep no log). The ORT session inside a model session is not listed
twice; it goes with the model session. A [template running in the background](sandbox.md#templates-in-the-background)
has a row as well, with its name and how long it has run, no count of runs and no log; Terminate cancels it.
**Terminate all** ends all of them, the swarms first. Right-click a row for Terminate, Show log (*Hide log* once
shown), Copy log and Copy `#id`; a session row has no ⋯ button.

Each row has a resources line under it: for a model its head and its encoder on disk, the encoder said once with how
many share it, the time a run takes and when it was last used (`head 9 KB · encoder 415.6 MB, shared by 3 · 12 ms a run
· last used 3s ago`); for a package its jar, for a bare ONNX session its file, for a group its heads together, and for a
background template what its meter reads now (`memory 1.20 GB, peak 1.40 GB · heap 340.0 MB of 4.00 GB · cpu 2.10 s ·
ort 12 runs in 640 ms`). Over the rows a totals line says what the process holds, how much of it is the encoders, the
heap against its limit, and how many sessions are open and what they weigh together (`Process 1.85 GB, of which
encoders 415.6 MB · heap 340.0 MB of 4.00 GB · 20 sessions weigh 436.0 MB`); what each figure is, in
[Running sessions](sessions.md#see-what-a-script-uses). A *session model* is a snapshot from `sessionMod("name")` (`modules/SharedModels.kt`): **Terminate** ends it
for whatever holds it and leaves the model alone. Over the sessions, **Scripts** lists every script running and each
that left something open (`runtime/ScriptRuns.kt`), named after the template it is word for word, else `main.kts`, with
what it holds, its answers, its CPU and what it allocated, and its sessions under it; what the figures are is in
[Running sessions](sessions.md#see-what-a-script-uses).

