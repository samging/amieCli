# Settings

Route `settings`, opened from the gears icon at the foot of the sidebar, and from everything that asks a guest to sign
in; the window header has no account. The page scrolls, with the file tree in a box of its own at the foot. As in
VS Code's settings, each part is a title over a card of rows: a setting's name and a line about it at the left, its
control at the right, the rows divided by a thin rule. The controls are the app's own buttons and toggles.

## Account

The first section, because it is where signing in lives (see [Signing in](sign-in.md)). Signed out it says what
signing in gives, with **Sign in** and **Create account**, which open the login and register pages; those come back
here. Signed in it says who you are, with **Log out**, which ends the session here and at Keycloak. After a restart
it shows you as signed in as soon as it opens, whatever Keycloak takes to answer. The backup under it is only
available signed in, and says so otherwise.

### Backup

A guest sees that it needs an account, with **Sign in to back up**, and no button that does nothing.

**Back up now** stores your own work on the Amie backend under your account, in place of the last backup. It is one
[saved state](backend-integration.md#saved-states) named `amp-backup`, holding exactly these files and nothing else:

- the scripts in `AMP/templates`: each `<id>.kts` with the `<id>.json` that describes it
- `AMP/settings.json`
- `AMP/pluginDir/main.kts`

This is an allowlist (`settings/AccountBackup.kt`). `session.json`, `keycloak.json`, `databases.json`, `github.json`,
a model's `serving.json` with its key, models, logs, jars and anything else in the folder are never sent, and a link in
the folder is not followed. The one thing it can't tell is what you wrote: a password typed into a script goes with
the script.

The line under it says when the last backup was made and how many files it holds. The time comes from the backup on
the backend, so it is right on another machine, and it reads *Last backup unknown*, with the reason, when the backend
can't be reached.

The backend keeps a saved state of at most 1 MiB, measured on the JSON text it receives. A backup over that is refused
whole, naming its size and the largest files, and nothing is sent, so the last backup stays as it was. It is never
cut down to fit. A file that isn't UTF-8 text refuses the backup the same way, since it couldn't come back as it is.

**Restore** asks first, in a question under the backup (the same box every confirmation uses), and **Keep my files** changes nothing. It writes the backup's files back and
deletes nothing, so a template the backup doesn't have stays. A file that would change is first copied to
`AMP/pluginDir/backup/<name>-<yyyyMMdd-HHmmss>.<ext>`, as the editor keeps a script it replaces, so a replaced script
shows under *Previous scripts* in the sandbox's Templates pane. The message afterwards names each file replaced and
its copy. Settings restored apply at once, and a restored `main.kts` is in the editor the next time you open the
sandbox. A backup that names any file off the allowlist writes nothing at all.

### Cloud save

Logged in, what you save in the sandbox is also kept in your account, and logging in on another Mac brings it down. It
uses the same three kinds of file as the backup and is described under [Cloud save](sandbox.md#cloud-save) in the
sandbox page; the backup above stays a separate, manual snapshot.

## The rest of the page

**UI scale** (under *Appearance*): how large everything is drawn. The presets (80, 90, 100, 110, 125, 150%) apply at once; the slider
(70 to 150% in steps of 5) applies when it is let go. It is kept in
`AMP/settings.json` with the sidebar choices of the sandbox and the Models page and whether the Models tab's first
steps were dismissed.

**Editor**: the sandbox editor's *text size*, presets of 12, 13, 14, 15, 16 and 18pt (14 the default), its
lines spaced to match; kept as `editorFontSize`. **⌥⌘+** / **⌥⌘−** (Ctrl+Alt elsewhere) inside the editor change
it by a point (11 to 20) and **⌘0** sets it back.
*re-indent on Enter* (on by default, kept as `reindentOnEnter`): Enter re-indents the whole script as it starts
the new line ([Sandbox](sandbox.md)); off, Enter only starts the line at its depth, and ⌘⇧F still re-indents.
*Run details* under **Console** (off by default, kept as `runDetails`): the run's status (`Running 3s`, `Finished in
0s`) and the figures of what it used, over the console, or over the editor while the console is hidden. Off, the
console strip has only its level toggles, Find, Details and Clear, and the console only what the script logs.
The line under it says where the [script templates](sandbox.md#templates) are kept.

The sidebar is the same as on every page, so Sandbox and Models are one click away; the back arrow in the window's
top bar returns to the page Settings was opened from. The bar also has the page's sections as tabs (Account, Backup,
Appearance, Editor, Models page, Files, those that show): a tab scrolls to its section, and the one selected is the
section scrolled to. A row is its name and its control, the rest in its tooltip.

**Models page**: *first steps* shows the four first steps over the Models tab again after their **Dismiss** hid
them (kept as `firstStepsHidden`); they show while one of them is undone (see [Models page](models-page.md)).

**Model folders**: every model's folder in one list (intent, text, speech and imported models, by kind), each row its name, kind and full path, with **Copy path** and **Reveal**. A model's own page no longer shows its path.

**Files**: the AMP folder as a tree, everything the app writes (`models/`, `pluginDir/`, `env/`, the json
files). Every folder opens and closes, every file shows its size, **Refresh** reads it again and **Reveal** opens
it in the file browser. A file or folder is deleted from its row (**Delete**, then the question that opens under the row, with **Keep**
and a red **Delete**; a folder goes with everything in it). A folder left empty deletes itself: after a delete the folders left
holding nothing go, and every read of the tree prunes the empty ones, since the app makes any folder it needs
again. The root stays.
