# Raspberry Pi 5

How the app was measured for a Raspberry Pi 5, what that measurement can and cannot say, what it changed, and the
one command that measures a real Pi 5.

## What was measured, and what that is not

The app has a headless benchmark: `--bench` starts it with no window, measures the parts a person waits for, prints one
`bench:` line for each and writes the same as JSON next to the AMP folder. It runs on a scratch AMP folder
(`AMIE_AMP_DIR`), never on yours.

| Part | What it is |
| --- | --- |
| jvm start to main | The JVM's start up to the app's `main` |
| onnx runtime loaded | ONNX Runtime's native library, loaded by the first session |
| encoder loaded, first predict | The bge encoder opened and shared, then the first answer of the dummy model through it |
| 100 predicts | The median and the 95th percentile of a hundred answers, with the threads the app was given and with one |
| 1,000 rows embedded | What a build does with its rows, in batches of rows of one token count |
| readSnapshot with 20 models | The Models page's read of twenty models with ten runs each, the first time and again; the plots it leaves to a thread of its own are timed on a line of their own |
| script engine, first compile and run | The Kotlin compiler a first Run waits for, with a small script compiled and run |
| 8 background templates | Eight templates started at once, each with an engine of its own, and how long until all ended |
| compiled bundle | A script exported compiled to classes (the export), then `java -jar run.jar` in a JVM of its own (the run) |
| peak resident, peak heap | The most the process held, sampled four times a second; heap against its limit |

**What the numbers on this page are.** They were taken on an Apple M5 Mac with 16 GB and a warm file cache, with the JVM
held to a Pi 5's budget as far as a JVM allows: `-XX:ActiveProcessorCount=4` (the JVM sees four cores, and sizes its
thread pools, its JIT threads and the app's ONNX threads by four) and `-Xmx2g` or `-Xmx4g`, which are the heaps the 4 GB
and the 8 GB model get. **They are not a Pi 5's numbers.** A Pi 5's four Cortex-A76 cores at 2.4 GHz are slower than this Mac's
cores for this kind of work (by how much, this page cannot say), so every time here is a floor for the Pi, not a forecast of it. The
figures that are about the app's shape (which part costs most, how much memory it holds, how a change moved a part)
carry over; the absolute milliseconds do not.

What cannot be imitated on this Mac:

- **Slower cores.** The Mac's cores are faster, and the cap above limits how many the JVM uses, not how quick they are.
  The encoder's 14 ms answer and 2 ms per row will be longer on a Pi.
- **The SD card.** Here the encoder (415 MB) and the libraries came from a warm cache on an NVMe disk. A Pi reads them
  from an SD card or an SSD, cold on the first start, and the first load of the encoder will be seconds there.
  Everything below that is "loaded" is the warm figure.
- **Memory pressure and swap.** The Mac has 16 GB, so nothing was swapped. The peak resident of 1.7 to 2.0 GB is the
  figure to hold against a 4 GB Pi's RAM, with the system and the window on top of it.
- **No GPU, and the window.** The benchmark has no window. Compose on a Pi's graphics stack (software rendering or
  the Mesa driver, Skiko's arm64 Linux build) is not exercised, so how the window feels is not measured here.
- **Thermals and the governor.** A Pi 5 without a cooler throttles under the load of a build; the script below prints
  `vcgencmd get_throttled` and the governor so a throttled run can be told.

## Results

Each run was a fresh JVM, one at a time. The first column of figures is the app as it was before the changes below (the
two switches that turn them off were set), the second is after them, both with `-XX:ActiveProcessorCount=4 -Xmx2g`;
the third is `-Xmx4g`. "After" is a second start on the same AMP folder, which is what a Pi does every time but its first.

| Part | Before, 2 GB | After, 2 GB | After, 4 GB |
| --- | ---: | ---: | ---: |
| jvm start to main | 151 ms | 165 ms | 165 ms |
| onnx runtime loaded | 1,106 ms | 36 ms | 897 ms (first start) |
| encoder loaded | 4,924 ms | 775 ms | 798 ms |
| first predict | 33 ms | 35 ms | 29 ms |
| 100 predicts, 4 threads (median, p95) | 14.2, 15.2 ms | 14.1, 17.9 ms | 14.1, 15.2 ms |
| 100 predicts, 1 thread (median, p95) | 47.4, 47.7 ms | 47.4, 47.6 ms | 47.3, 47.7 ms |
| 1,000 rows embedded | 12,517 ms | 12,675 ms | 12,320 ms |
| readSnapshot, 20 models, first | 259 ms | 242 ms | 259 ms |
| readSnapshot, again | 4 ms | 4 ms | 4 ms |
| plots drawn in the background | 1,761 ms | 1,761 ms | 1,768 ms |
| script engine, first compile and run | 2,175 ms | 2,095 ms (365 ms warmed, see below) | 2,094 ms |
| a second engine, compile and run | 222 ms | 242 ms | 225 ms |
| 8 background templates, started, finished | 36 ms, 1,842 ms | 34 ms, 1,596 ms | 33 ms, 1,570 ms |
| compiled bundle: export (20 MB) | 3,304 ms | 3,300 ms | 3,485 ms |
| compiled bundle: `java -jar run.jar` | 220 ms | 216 ms | 224 ms |
| peak resident | 1.74 GB | 1.99 GB | 2.03 GB |
| peak heap | 628 MB of 2 GB | 805 MB of 2 GB | 602 MB of 4 GB |
| total | 35.3 s | 29.9 s | 30.6 s |

A run with `-Xmx1g -XX:+UseSerialGC -Damie.ort.threads=3` (what the troubleshooting page suggests for a 4 GB Pi) held
455 MB of its 990 MB heap at the peak and 1.67 GB resident; its answers took 18.1 ms at the median and its rows 14.1 ms
each, against 14 and 12.7 ms with four threads. Eight templates still fit in that heap.

Where the time goes, from these runs: **the encoder's compute is what costs most.** A build spends 12.4 ms of every
row in ONNX Runtime (a hundred answers took 1.4 s with four threads and 4.7 s with one), so a thousand rows take 12 of
the benchmark's 30 seconds. After it come the first script compile (2.1 s) and the export of a compiled bundle (3.3 s,
which happens once per export, not per run). The Models page's read, the answers and the bundle's run are small.

## What changed

Each of these was measured before and after on the runs above. The switch in the last column turns it off, which is how
the "before" column was taken.

| Change | Before | After | Switch off |
| --- | ---: | ---: | --- |
| **No call home from DJL's tokenizer.** The tokenizer library, on the first tokenizer, asks the EC2 metadata address who it runs on and then reports to an S3 bucket. Off AWS the first call waits for its connection to time out, which held the encoder's load. The app now sets DJL's own switch (`OPT_OUT_TRACKING`) before any tokenizer is made, unless the machine set it. | encoder loaded 4,924 ms | 775 to 823 ms | `-Damie.djl.telemetry=true` |
| **ONNX Runtime's native library is unpacked once** into `AMP/cache/ort/`, not into the temp folder on every start (13 MB on a Pi, 26 MB on this Mac, with the JNI library beside it). A Pi on an SD card wrote and read those megabytes before its first session at every start. | onnx runtime loaded 1,106 ms | 36 ms from the second start (about 920 ms on the first) | `-Damie.ort.cache=false` |
| **The script engine is warmed in the background** four seconds after the window shows, at the lowest thread priority, so the first Run finds the compiler loaded. | first compile 2,095 ms | 365 ms (measured with `--bench --prewarm`; it cost the answers running beside it about 1.5 ms at the median) | `-Damie.prewarm=false` |

The first of these is also a privacy change: the app no longer sends anything to Amazon's telemetry bucket through
DJL. Setting `OPT_OUT_TRACKING=false` in the environment or as a property keeps DJL's behaviour.

Also in this branch, from the earlier slow-hardware work: ONNX threads that sleep when idle, plots drawn off the page's
read, one-vector-per-row builds and the 35% heap limit (see [Troubleshooting](troubleshooting.md#slow-hardware)).

## What was left alone

Each of these needs a real Pi to judge, or changes results:

- **Batching the rows of a build** (several texts per ONNX run). It may raise throughput on four cores and may not on a
  Pi's memory bandwidth; the vectors differ in the last digits. Try it on the Pi.
- **An int8 or fp16 encoder.** Likely the biggest win on ARM, and it changes the vectors every model is trained on.
- **Other ONNX session options** (arena off, a saved optimized graph, ORT's defaults) were measured against the app's
  options on this Mac and made no difference in the answer time (13.1 to 13.6 ms); the load time of a saved graph is a
  cold-disk question this Mac cannot answer.
- **JIT and class loading flags** (`-XX:TieredStopAtLevel=1`, class data sharing for the script compiler's 2 seconds,
  `-XX:CICompilerCount`): their value depends on the Pi's cores.
- **The thread count of the encoder** (`-Damie.ort.threads`): three threads were slower here (18.1 ms against 14.1 ms, with a 1 GB serial-GC heap beside it); on a Pi, with the
  window needing a core, it may be better. The benchmark prints both the app's count and one thread.
- **The installed package's layout.** The Pi command below uses the DEB's jars under `/opt/amie-pilot/lib/app`; that
  layout was not checked on a Pi, and neither was the compiled bundle step with the jars loaded that way. If that step
  fails the benchmark says so on its line and goes on.

## Running it on a Raspberry Pi 5

On a 64-bit Raspberry Pi OS, with the arm64 DEB of the app installed (see [Getting started](getting-started.md)), or in a
checkout of this repository:

```bash
./scripts/pi-bench.sh
```

That one command installs a JDK 21 if the Pi has none (`apt`, else Temurin 21 from adoptium.net into `~/amie-bench/jdk`),
picks `-Xmx2g` on a Pi with under 6 GB and `-Xmx4g` otherwise, and runs
`java -Xmx2g -cp '/opt/amie-pilot/lib/app/*' org.example.project.MainKt --bench` on a scratch AMP folder in
`~/amie-bench/amp`. Without the DEB it builds the classes from the checkout with Gradle first and stops Gradle's daemon
so it is not in the memory being measured. It prints the Pi's model, cores, RAM, governor and throttling state, then
the `bench:` lines, and keeps them in `~/amie-bench/result-<time>.txt` with the JSON next to the AMP folder.

To run it by hand, with the JDK installed (`sudo apt install openjdk-21-jdk-headless`, or Temurin 21):

```bash
AMIE_AMP_DIR=$HOME/amie-bench/amp java -Xmx2g -Dcompose.application.resources.dir=/opt/amie-pilot/lib/app/resources \
  -cp '/opt/amie-pilot/lib/app/*' org.example.project.MainKt --bench
```

Options after `--bench`: `--stand-in` (a small head stands in for the encoder), `--no-bundle` (skip the export and
its run), `--rows N`, `--predicts N`, `--prewarm`. With `AMIE_JVM_ARGS="-Xmx1g -XX:+UseSerialGC -Damie.ort.threads=3"` the
script runs the flags of a 4 GB Pi from the troubleshooting page instead. Run it twice: the first run unpacks ONNX
Runtime's library and reads the encoder cold, the second is what every start after it costs. Cool the Pi first, and
send back the `throttled` line with the numbers.
