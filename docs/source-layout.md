# Where the code is

The app's Kotlin is under `amieApplication/desktopApp/src/main/kotlin/org/example/project/`, the tests in
`src/test/kotlin/` under the same folders. Most of it is in `components/terminal/window/controller/`, whose folders
say what a file is:

| Folder | What is in it |
|---|---|
| (the folder itself) | The window: `TerminalWindow` (and `LogLevel`), `ToolWindows`, `CommandPalette`, `ConfigurationPanel`, `ConnectionPanel` |
| `models/` | The Models page (`ModelsPage` the layout, `ModelsPageState` its state and actions, `ModelsGuide` the guide tab) and what it shows of one model: list, snapshot, detail, tabs, Try, Improve, decisions, new model |
| `tabs/` | The other tabs of that page: groups, packages and their repository, sessions |
| `amp/` | The layout of the models in the AMP folder, `ModelFolders` (the one owner of `models/bert`, `models/intents`, `models/text`, `models/speech`), and their move into it at start, `ModelsMigration` |
| `pages/` | What the pages share: `ManageablePage`, `PageKeys`, notices, polling; the shared pieces of a page (Hint, Fold, SearchField, DetailPanel, …) are in `components/ui/viewport/`, see [The components](components.md) |
| `console/` | The console: lines and colors, find in it, the sessions it ends, what is running now and what it uses, the memory watch |
| `sandbox/` | The sandbox page: `CodingSandbox` (the layout), `SandboxStrips` (the editor header and the console strip), `SandboxRunStatus` (the run's dot, figures and outcome), `SandboxFolds` (Recent runs, Still open, Kept away), the editor with its folding and formatting, the templates pane, the examples, the export panel, the run history |
| `script/` | What runs a script: `ScriptRuntime` (`assembleScript`, `ScriptRunner`), `ScriptLogger`, `SandboxLogger`, `SandboxApi` (the API a script calls), templates, errors, `MainScriptRun`, background templates, shell commands |
| `modules/` | The AMP modules a script names by import: `AmpModules` (`readModule`, `updateModule`, ...), `ModuleMetrics`, `ModelVersions`, `ModelGroups`, `ModelJobs`, `OrtScopes`, `SharedModels` |
| `runtime/` | A model at work: the HTTP server, sessions, text and speech models, the session log and run records, run resources |
| `metrics/` | What is said of a model: metrics charts and images, score intervals, validation, requirements, the model card |
| `util/` | Small helpers: file sizes, local times, version compare, stamped reads, system stats, the desktop (file chooser, browser), number formats |
| `jetbrainDLBuilder/`, `modelLoaders/`, `envBuilder/`, `export/`, `pluginEngine/`, `sourceFile/`, `databases/` | Building models, loading them, the script environment, the compiled bundles, jar packages, data sources, databases |

A file's package is its folder, and a test is in the same folder as the file it tests.

## What must stay where it is

Saved scripts, the templates under `AMP/templates` and the code the sandbox generates name some classes by their full
name: `LogLevel` (in `TerminalWindow.kt`), the functions and types of `modules/` and the packages `jetbrainDLBuilder`,
`sourceFile`, `envBuilder` and `databases`. Moving those would break a saved `main.kts`, so they stay in their
packages. `SandboxApi` and `CompiledRuntime` (in `export/`) spell the other class names out, so they follow a moved class.

## Tests

`desktopApp/build.gradle.kts` sorts the tests into suites by package: `testModels` takes `jetbrainDLBuilder`,
`modules`, `runtime`, `metrics`, `util`, `modelLoaders` and `integrations`; `testScripts` takes the rest of `controller`;
`testUi`, `testPerf` and `testApp` take `ui`, `perf` and `bench`, and what is left of `org.example.project`. A
new folder under `controller/` is in `testScripts` unless it is added to `testModels`.

A test file is named after the source file it tests, `<Name>Test.kt` or `<Name><Aspect>Test.kt`, so that
`./test.sh file <Name>` finds it, and holds the tests of that one file or feature. What tests share lives in
`org.example.project.support`: `RecordingLogger` and `runScriptLines` (main.kts run on the real engine, every line it
logs kept), `NoGithubRepos` and `withNoGithubRepos` (`AMP/github.json` listing no repositories while a page is open,
so it stays off the network), `fakeKeycloak` (a mock engine answering as Keycloak does), `onAnyThread` (Dispatchers.Main
on the test's thread). The page tests drive a page by its texts with `ui/PageDriving.kt` (`click`, `await`, `settle`,
`type`, `press`); the training tests build models with `jetbrainDLBuilder/TrainingFixtures.kt` (`FakeEmbedder`,
`rows`, `writeCsv`); the page harness (`ui/PageHarness.kt`) is the real page off-screen with screenshots, extended by
`PageShotsTest` and `CodingSandboxTourTest`. A fixture a few tests of one folder share stays in that folder, as
`BackgroundRunFixture`, `PackageJarFixture` and `BackgroundTemplatesFixture` do.
