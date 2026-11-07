# Troubleshooting

## Sign-in

**I am signed out every time the app starts.** Before 2026-10-14 the app only had Keycloak's normal refresh token, which
the realm expires after 30 minutes without use, and a Keycloak that was not reachable at start also deleted the
session. Now every sign-in asks for an offline token, and a Keycloak that is unreachable or answers with an error of
its own keeps the session. If Account in Settings still shows you signed out: check `AMP/logs.txt` for lines starting with `auth:`. "Keycloak refused the saved session" means
the realm revoked it (sign in again); "couldn't refresh … kept, will retry" means Keycloak is down, see below.

**"Couldn't renew your session, Keycloak isn't answering".** You are still signed in; the access token expired and
Keycloak can't renew it right now. Start Keycloak and try again; there is no need to sign in.

**"Keycloak is not reachable" on the login page.** Run `docker ps`. The Keycloak container restarts in a loop while
its Postgres container is down:

```bash
docker start my-ubuntu-image-postgresql-1
docker start my-ubuntu-image-keycloak-1
curl -s http://localhost:8080/realms/master/.well-known/openid-configuration | head -c 100
```

**"Wrong username or password" with the right password.** Use the username; the realm does not log in by email.

**"Client 'amie' rejected".** The client is confidential: put its secret in `AMP/keycloak.json` or
`KEYCLOAK_CLIENT_SECRET`.

**The browser sign-in hangs.** The realm's frontend URL points at an old LAN address. Fix it under Realm settings →
General in the admin console (see [Keycloak setup](authentication.md#stale-frontend-url)).

**Port in use when signing in with the browser.** Change `redirectPort` in `AMP/keycloak.json` and add
`http://localhost:<port>/*` to the client's redirect URIs.

## Sandbox

**`import("…")` without the prefix does not compile.** Kotlin reads it as an import statement. Use
`implementation("group:module:version")`, `dependency("…")`, `environment(SettingsEnv(…))` or `amie.import("…")`.

**"env '<name>' must be selected with a literal environment(SettingsEnv("<name>", ...)) or amie.import(SettingsEnv(...)) to be sourced".** The environment call must be written
literally in the script (`environment(SettingsEnv("name", "profile"))` or `amie.import(SettingsEnv(...))`); it is
read from the text before the script compiles.

**modelBuilder fails to embed.** The encoder folder is missing; see [Getting started](getting-started.md#what-you-need).

**metricsChart says there are no metrics.** The model was never built in this `AMP/`, or its name differs from the
folder under `AMP/models/intents/`.

## Models page and backend

**"The backend rejected the Keycloak token (401)".** The backend was started without the Keycloak verifier
settings, or its `KEYCLOAK_ISSUER` does not match the realm's frontend URL. See [Amie backend](backend-integration.md).

**"Connection refused: ensure the Amie backend is running".** Nothing listens at the address in `AMP/backend.json`.
With Keycloak on 8080, run the backend with `AMIE_BACKEND_PORT=8090`: the app moves a `baseUrl` on 8080 to 8090
by itself (and saves it) when 8090 answers; set it by hand only when the backend is somewhere else.

**Publish reads *Publish (sign in)*.** You are a guest; pressing it opens Account in Settings, where you sign in. Publishing uploads under your account, so it needs a Keycloak sign-in;
the metrics and statistics on the page do not.

## Performance tests

`desktopApp/src/test/kotlin/org/example/project/perf/` measures the app and asserts each figure under a budget about
three times what it measured on the Mac it was written on; the budget and that measurement stand beside each other in
the test. Every figure is printed as `perf <name>: <value> (budget …)`, so a run on a slower machine says what it got.
The tests that open a model use the real bge encoder with `AMIE_E2E_ENCODER=1` (seconds to load, hundreds of MB
outside the heap) and a stand-in model without it, so the figures are of the app around the model either way.

```bash
cd amieApplication
./gradlew :desktopApp:test --tests 'org.example.project.perf.*' --no-configuration-cache --rerun
AMIE_E2E_ENCODER=1 ./gradlew :desktopApp:test --tests 'org.example.project.perf.*' --no-configuration-cache --rerun
```

| Test | What is measured | Budget |
|---|---|---|
| `StartupPerfTest` | `main`'s root composed off screen: the first frame, and the sandbox on screen after the start route sends on | 4 s, 12 s |
| | `BundledModels.root` and `SharedEncoders` when first touched; with the encoder, its first load, a second acquire, the last release | 50 ms each; 10 s, 50 ms, 2 s |
| `ModelsPagePerfTest` | `readSnapshot(remote = false)`, what the page reads every 1.5 s, with 1, 20 and 60 models in `AMP/models/intents`; it may grow linearly in the count and no worse | 150 ms, 400 ms, 1 s; 60 at most 3.5× what 20 added |
| | The Models page composed on an empty cache with 60 models: its first frame, the first model on screen | 5 s, 8 s |
| `MemoryPerfTest` | A session opened, asked and closed 20 times: heap drift after `System.gc()`, the encoder's holder count back where it was, no ORT session left | 24 MB |
| | Two models on one encoder folder load it once: the second open costs its head only | first 10 s, second 1 s |
| | `sharedModel("dummy")` taken ten times: one snapshot with ten holders, freed by the last close, the heap and the encoder back | 24 MB drift |
| | Eight background template runs at once: started, all ready on their own engines, the heap they hold, the ninth refused, what stays once they end | 2 s, 45 s, 900 MB or 85 % of the heap, 150 MB |
| | *Terminate all* in the page's order: no model session, shared model, package, ORT session or run left, no encoder loaded | 3 s |
| `WindowsPerfTest` | Sandbox → Models → the model's detail panel → back → Settings → back, ten rounds through the real sidebar and header: no exception in the log, threads not climbing after the second round, at most the status strip's own two or three `SystemStats.read()` calls in five seconds once the Models page is away | first round 15 s, median 9 s, 6 threads, 4 reads |
| | The whole window opened and closed ten times (each start composes the Devices dashboard on its way to the sandbox); no read of the process once they are closed | first 12 s, median 5 s, 6 threads, 1 read |
| `PopupsPerfTest` | The command palette (a popup of its own over 300 entries) opened and closed 40 times: no popup left, its hold on the page's keys given back each time, no thread; a query over 5000 entries | first 10 s, median 3 s, 6 threads; 1 s |
| `ConsolePerfTest` | A script printing 40000 lines from a thread of its own: the console stays between 18000 and 20000 lines with the newest, `logs.txt` has all of them, the heap is back after clearing | produce 20 s, drain 30 s, 40 MB |
| | Eight threads of 2000 lines: every line in the file and the console, each thread's in order; 15 megabyte lines pass 20 MB and `logs.txt` moves to `logs.1.txt`; `clearConsole` over 10000 lines | 20 s; 30 s; 500 ms |
| `ConsoleCapViewPerfTest` | A full console shown and followed takes 3000 more lines in thirty batches of 100: the oldest 2000 go at once when the cap is passed (one by one, every batch composed all 20000 lines again: 1.2 s a batch), the view stays at the newest | compose 40 s, mean batch at the cap 600 ms |
| `ConsoleCommandsPerfTest` | `scopes`, `clear`, the aliases (`open`, `still`, `close`, `?`) in any case, `terminate` by name closing every session of that name; 300 commands in a row answered with no thread left; `terminate all` over ten models, a bare ORT session and three background templates | 20 s, 6 threads; 5 s |
| `RunResourcesPerfTest` | The Resources line of a run that burns CPU and opens and closes a session: memory within the machine's RAM, peak at least the end, heap within its limit, CPU between a quarter of the burn and every core, one session opened and closed; a run that holds 192 MB says at least 48 MB more and a heap of 60 MB | the machine's own limits |
| `RunMeterPerfTest` | A run that sleeps two seconds: the CPU the meter's own thread spends on its three readings (the `ps` child's own CPU is outside the JVM) | 1 % of a core |
| | 30 runs started and stopped: no `amie-run-meter` thread left | 6 s |

A figure over budget fails the test with the line it printed. On a machine slower than the budgets allow, the printed
figures say by how much; the budgets are in the tests, next to the measurement they were taken from.

## Slow hardware

To see what the app costs on a machine, start it with `--bench` (on a Pi: `./scripts/pi-bench.sh`); [Raspberry Pi 5](pi5.md) has what it measures and what it found.

The app runs on a Raspberry Pi (4 GB, four cores, an SD card) and on older Macs with 8 GB. What costs most there is
the encoder: bge is 800 MB on disk and about 1.2 GB once ONNX Runtime has loaded it, outside the Java heap, and it
is loaded once and shared by every session, listener, validation and build on it.

**What to set.** The heap may grow to 35% of the machine's RAM (`-XX:MaxRAMPercentage=35` in the build: 1.4 GB on a
4 GB Pi, 2.8 GB with 8 GB). To change that for one machine, set `AMIE_JVM_ARGS` where the app is built or run, as
in `AMIE_JVM_ARGS="-Xmx2g" ./gradlew :desktopApp:run`; a DEB or MSI built with the variable set carries the flags in
its launcher.

- An 8 GB Mac: the defaults, or `AMIE_JVM_ARGS="-Xmx2g"` when the machine swaps with the app open. That leaves the
  encoder, the system and a browser room.
- A 4 GB Raspberry Pi: `AMIE_JVM_ARGS="-Xmx1g -XX:+UseSerialGC -Damie.ort.threads=3"`. One gigabyte of heap is enough for
  the page, scripts and a build of some thousand rows; the serial collector keeps the JVM's own threads off the four
  cores; three ONNX threads leave one core for the window. An 8 GB Pi takes `-Xmx2g` and the default threads.
- `-Damie.timing=true` prints, on standard output, when the window was composed and when the Models page had its first
  read, counted from the JVM's start, so a slow start can be placed.
- `-Damie.ort.threads=N` sets how many threads each ONNX session gets (one per core by default), and
  `-Damie.ort.default=true` opens sessions with ONNX Runtime's own options, to compare.

**What the app does on its own.** The threads of an ONNX session sleep when the session is idle instead of spinning
for more work, so an encoder, a few heads and a group open at once don't take the cores between requests (an idle
encoder went from 8 ms to 2 ms of CPU in two seconds on a Mac). A build runs the rows through the encoder 32 at a time, each batch rows of the same number of tokens, which gives the very vectors one row at a time gives about five times quicker (2 ms a row instead of 11 on a Mac). It keeps only each row's sentence vector (3 KB), not its whole hidden
state (about 200 KB), so ten thousand rows take 30 MB of heap where they took 2 GB. The Models page reads the models
again every 1.5 seconds and the status strip every 2, and when a read takes long on a slow disk the next one waits
three times as long; a model's metrics history is decoded once and kept until its file changes; the process's memory is
read from `/proc` on Linux and from one `ps` a second at most elsewhere. The plots of the models are drawn on a thread of
their own, at the lowest priority, and shown as they are ready, where the page's first read used to draw every one before
showing anything (1.3 of its 1.9 seconds with twelve models on a Mac); the plots that follow a serving model's log are
drawn again at most every ten seconds instead of after every answer. Background templates run at most eight at a
time on a heap of 512 MB or more and fewer on a smaller one (one per 64 MB), each holding a Kotlin script engine of its
own, measured at 32 to 49 MB.

**When memory is short.** The status strip at the foot of the window turns red when the process holds over 85% of the RAM or the
heap is over 90% full: close the sessions and stop the listeners you don't need (a listener keeps its encoder; **Terminate
all** closes sessions and templates but not listeners, which load the encoder again on their next request). An encoder
that would not load leaves a line in the console starting `encoder: couldn't load`, with what the process held and
what the machine had free. An out of memory error nobody caught leaves a line starting `Out of memory on thread`, with
the heap limit it ran into; the app goes on, and what that thread was doing (a build, a page's read) is lost and can be
started again. A template that can't start because the cap is reached says so with the heap it was counted against.
