# Models and metrics

A model is an intent classifier: a small dense network trained on BERT embeddings of your texts. Everything about
a model lives in `AMP/models/intents/<name>/`, so building, inspecting and serving one needs no account; only publishing
does. A fresh checkout starts with a fixed **dummy** model (labels `greeting`, `lights`, `weather`) that is written
when `AMP/models/intents` is empty, so `initMod("dummy")` and the Models page work before any training.

## Building one

From the Models page: **New model**, pick the csv (or **Sample data**, `AMP/sample-intents.csv`), Build. From
a script:

```kotlin
val train = source("intents.csv")        // columns: text,label (header names like text/label/intent are detected)
relate(train, "intents-test.csv")        // optional: validate on this file instead of a 20% split
val model = modelBuilder("intents", train)
model.modelStatus()                      // the validation table
model.modelChart()                       // bars, history and confusion matrix
```

`modelBuilder` runs the **preprocessor** over the rows, embeds every text once, trains **every candidate head**
on the same rows, keeps the best one by macro F1 on the validation rows, and exports it:

| File | Content |
|---|---|
| `model.onnx` | The trained layers, written as ONNX (opset 13) |
| `module.json` | Encoder path, input and output sizes, pooling, the label list, the data file it was trained on |
| `metrics.json` | **One entry per build**: accuracy and macro F1 (with how far they hold, [below](#how-far-the-scores-hold)), per-label precision / recall / F1 / support, the confusion matrix, warnings, what it was validated on |
| `serving.json` | Host, port, path, methods and whether it listens |
| `incoming.jsonl` | Every request the served model received |
| `versions/v<N>/` | The heads it had before, with their `module.json` and scores: the ten newest, for a [rollback](#versions-and-rollback) |

Accepted data shapes: a `DataSource` from `source(...)`, `List<Pair<text, label>>`, `Map<label, List<text>>`, or
rows of `[text, …, label]`.

### Before training: the preprocessor

Nothing is embedded before the rows are cleaned and measured. The preprocessor writes what it did to the console
(the **info** lines that begin with `modelBuilder:`), keeps the same lines with the model (`metrics.json`,
shown under **About → Training** on the Models page), and stops the build when the set cannot give a usable
classifier. What it does, in order:

1. **Cleans every row.** Control characters go, runs of whitespace become one space, text and label are trimmed.
2. **Unifies label spellings.** `Lights` and `lights` are one label; the more frequent spelling wins and the console
   says `label 'Lights' read as 'lights'`.
3. **Drops rows that cannot be learned from**, each with its reason: an empty text, an empty label, a text with no
   letters or digits (`???`), a single character.
4. **Drops repeats and conflicts.** The same text twice under one label keeps one copy (`repeated row`). The same
   text under two labels keeps the label with the majority (`same text under another label, kept as 'lights'`);
   with no majority both rows go, because neither label can be right.
5. **Measures what is left**: rows per label, distinct texts per label, words per text and the spread of lengths.

A build stops, with the sentence below in red and the model not written, when:

| The console says | Why training would be pointless | What to do |
|---|---|---|
| `no labeled rows at all` | Nothing came out of the data shape | Return rows of `[text, label]`, pairs, or a map; check the csv's header names |
| `almost every row has its own label (… labels for … rows): the text and label columns are probably swapped` | A classifier learns a few labels from many texts, not the reverse | Swap the columns, or name them `text` and `label` |
| `label '…' is … characters long, which looks like a text` | Same as above, seen from the label's side | Same |
| `a classifier needs at least 2 labels` | With one label there is nothing to decide | Add rows of another label |
| `label 'x' has 3 rows after cleaning, at least 5 are needed` | Too few to train on **and** hold some out to validate | Add rows of that label, or drop the label |
| `label 'x' has only 1 distinct text; the rest differ only in case or punctuation` | The encoder sees one sentence; the head would memorise it, not the label | Write different sentences for that label |
| `nothing is left after cleaning` | Every row was empty, repeated or in conflict | Look at the dropped lines above it |
| `embeddings are nearly identical` (after embedding) | The encoder cannot tell the texts apart, so no head can tell the labels apart | Use texts that differ in more than a word |

Softer problems become **warnings**, stored with the metrics and printed, and training goes on: labels
imbalanced more than 10×, texts of under two words on average, texts longer than 1000 characters (the encoder
reads the first 512 tokens), many texts that differ only in case or punctuation, every text of the same length.

`buildOptions(preprocess = false)` skips the cleaning; only the hard checks of the old builder run then.

For a model that keeps no texts (`buildOptions(keepTexts = false)`) the lines count the dropped rows without
quoting them, and a label longer than 60 characters, which is a text when the columns are swapped, is named by its
length alone (`a label is 87 characters long, which looks like a text`); with the columns swapped no line names a
label at all, for any model.

The dropped rows are written to `AMP/models/intents/<name>/dropped.csv` (`text,label,reason`), so they can be fixed in the
source file. A related test file is cleaned the same way, and a test row whose text also appears in the training
rows is left out of the validation (the console says how many), because it would be scored on what was memorised.

**Check before building.** `checkData(source("intents.csv"))` in a script runs the preprocessor alone and prints
the same report, ending with whether a build could start; on the Models page the **New model** form has
**Check data** next to Build, which shows the report under the fields.

### After validating: what the scores say

Once the chosen head is validated, a few more lines are added to the warnings (printed, and kept with the build):

| The console says | What it means |
|---|---|
| `the validation has only 7 rows; its scores are rough` | Each row moves accuracy by a seventh; add rows or a test file before trusting a number |
| `labels with fewer than 2 validation rows: weather` | That label's precision and recall come from one row |
| `the head fits the training rows much better than the validation rows (0.99 vs 0.80): it memorises` | Overfitting: more varied rows, or a smaller head (`architecture(hidden = emptyList())`), would generalise better |
| `accuracy 0.62 is barely above always answering 'lights' (0.58)` | The head learned little beyond the majority label; the texts may not separate the labels |
| `label 'weather' is weak (F1 0.40); it is mostly taken for 'lights' (4 of 6 rows)` | The confusion matrix's worst row in a sentence; those two labels need more distinct examples |
| `3 of 48 validation rows repeat a training row (2 the same text, ignoring case and punctuation; 1 nearly the same to the encoder): scores on rows it learned from flatter it` | Those rows test what it remembers, not what it learned: [rows it learned from](#rows-it-learned-from) |

Two more options help with awkward data: `buildOptions(balanceClasses = true)` weights the loss so every label
counts the same, which lifts a rare label the head would otherwise learn last; `buildOptions(seed = 7)` changes the
split, the initial weights and the shuffles (the same data and seed always give the same model, so a comparison
between two runs is a comparison of the data or the options, not of luck).

### Which head: the architecture search

On top of the encoder's 768 numbers per text sits a small head. By default four are trained on the same
training rows and scored on the same validation rows, and the best macro F1 wins (ties: the better accuracy,
then the smaller head):

| Candidate | Head |
|---|---|
| `linear` | `dense[768 -> labels] softmax` |
| `dense[16]` | one hidden layer of 16 relu units |
| `dense[32]` | one of 32 |
| `dense[64, 16]` | two hidden layers |

As each head finishes the console shows its accuracy, macro F1, train accuracy, loss and parameter count, then
its F1, precision and recall per label, then what the diagnostics make of it (memorising, barely above the
majority label, a weak label and what it is taken for), and the Models page lists it under the build line at once.
Once every head is in, one line ranks them (`compared 4 heads by macro F1: dense[16] 0.950 · dense[32] 0.912 …`)
and `chosen: dense[16], the best macro F1 on the validation rows (next: dense[32] 0.912)` says which is kept.
The chosen head is then validated once more on every validation row (the Validating phase), so what is stored,
charted and exported is that head's own run. The comparison is kept with the build in `metrics.json`
(`candidates`, with `perLabelF1` and `insights` per head; **Heads compared** on the Metrics tab) and in one line
of the manifest (`search`, shown as **heads tried** under About → Training). Each candidate costs its epochs; on a
few hundred rows the whole search takes seconds.

### Training again

`modelBuilder("intents", train)` with the name of a model that exists trains it again in place, which is how a
model follows its data: add or fix rows in the csv and build under the same name. Nothing that runs on the old
head is cut off: the session open on it and its listener each open the new head beside the old one, swap it in
once their answers in flight are done, and close the old one after, under the same session id and on the same
port (a script holding the session, a swarm over it or a client of the listener sees the old answers up to the
swap and the new ones from it; `sessions.log` has a `swap` line, `access.log` too). The icon, the guide and the
description (unless the build gives one) are kept, and `metrics.json` keeps every earlier run, so the runs show
whether the new rows helped. Only a model that stops keeping texts closes its session instead, so no log goes on
keeping them. A fine-tune and a rollback swap the same way. The Models page's **Retrain** does the same. The head it replaces is kept under
`versions/` ([Versions and rollback](#versions-and-rollback)); to add a label or a few corrections without
training from scratch, [fine-tune](#fine-tuning-teaching-a-model-more) instead.

### Your own head

```kotlin
val options = buildOptions(
    candidates = listOf(
        architecture(hidden = listOf(32, 16), epochs = 200, learningRate = 0.005),
        architecture(hidden = emptyList(), epochs = 100),              // a linear head
        architecture(hidden = listOf(64), activation = "Tanh"),
    ),
    pickBy = "accuracy",            // or "macroF1" (the default)
    validationFraction = 0.25,       // how much is held out when there is no related test file
)
val built = modelBuilder("intents", train, options)
```

`architecture(...)` takes the hidden layer widths (an empty list is a linear head), `epochs`, `batchSize`,
`learningRate` and the hidden activation (`Relu` or `Tanh`); a wrong value is refused when the line runs, not
after the embedding. One candidate means no comparison, just that head. The Models page's **New model** uses the
defaults.

### Against memorising: regularisation, schedules, early stopping

`architecture(...)` takes more than the layers. Every setting below is off unless it is given, so a head that
leaves them out trains exactly as it always has:

| Setting | What it does | When it helps |
|---|---|---|
| `weightDecay = 0.01` | AdamW's decoupled weight decay: every step pulls the weights (not the biases) a little towards zero | The head memorises: its train accuracy is far above the validation's |
| `dropout = 0.2` | Switches off that share of the hidden units for each row while training; a linear head has none | A wide head (`dense[128]`) on a few hundred rows |
| `labelSmoothing = 0.1` | Spreads that share of each target over every label, so the head is less sure of rows like its training rows | Scores near 1.0 on everything, noisy labels |
| `schedule = "cosine"` | The rate eases from its value down to a hundredth of it over the epochs (`"linear"` too; `"constant"` is the default) | Long fits, a high starting rate |
| `warmupEpochs = 5` | The rate climbs to its value over the first epochs | A high rate, a deep head |
| `patience = 10` | Early stopping: a tenth of the training rows is put aside, the fit stops once their loss hasn't improved for 10 epochs, and the best epoch's weights are kept | You don't know how many epochs are enough |
| `name = "wide"` | Your name for the head in the console and the charts | A search with many similar heads |

A head with settings of its own is named after them, `dense[32] · lr 0.003 · dropout 0.2 · early stop 10`, so the
ranking in the console says which variant won. Early stopping prints `stopped early at epoch 17 of 150, keeping
epoch 7, the lowest loss on the tenth of the training rows put aside`.

### Cross-validation and searching heads

On a small file one 20% split is luck: three validation rows of a label move its F1 by a third. With
`buildOptions(folds = 5)` every candidate is also trained five times on four fifths of the training rows and
scored on the fifth it did not see; the heads are compared by the mean of those five scores (with its spread:
`over 5 folds of the training rows, accuracy 0.872 · macro F1 0.875 ± 0.069`), and the chosen one is then trained
on all the training rows and validated as before. It costs folds + 1 fits per head.

`searchSpace(...)` makes the candidates from every combination of the values it is given:

```kotlin
val options = buildOptions(
    candidates = searchSpace(
        hidden = listOf(emptyList(), listOf(32), listOf(64, 16)),
        learningRates = listOf(0.01, 0.003),
        dropouts = listOf(0.0, 0.2),
        patience = 10,
    ),
    folds = 5,
)
```

That is 2 linear heads (dropout means nothing to them) and 8 others. `trials = 12` draws 12 of a bigger grid at
random (`seed` picks which); a search of more than 64 heads is refused. Without arguments it tries six layer
shapes, from `linear` to `dense[128, 32]`, at two rates.

**Rows that look mislabeled.** In cross-validation every training row is scored by a head that never saw it. A row
is a *suspect* when that head is at least 60% sure of another label and gives the row's own label less than the
rows of that label usually get. After the build the console names three (`5 training rows look mislabeled … "lights
there number 103" is 'weather', they say 'lights' 0.955`), and `suspects("intents")` lists them all, the widest
disagreement first, with their texts only when the model keeps texts. A suspect is a wrong label or a hard row: fix
it in the csv, or keep it.

### Scores you can trust: calibration

A softmax head trained to fit is usually surer than it is right: a score of 0.95 may be right 80% of the time.
Every run measures this. The **expected calibration error** (ECE) is the gap between how sure the answers are and
how often they are right, averaged over ten bins of the top score. Every score gate from 0 to 0.95 is measured too:
how many rows it keeps and how many of those are right. `calibrationChart("intents")` prints both, and the console
names a gate worth using (`a gate at 0.95 keeps 20% of the rows and 100% of what it keeps is right`).

`buildOptions(calibrate = true)` fits one **temperature** on the validation rows (the scores become the softmax of
the logits divided by it) and folds it into the last layer. The exported model, `initMod` and the HTTP server then
give the calibrated scores without knowing about it. The answers stay the same: dividing every logit by the same
positive number keeps their order. A model that is built already is calibrated on rows it has not seen:

```kotlin
calibrate("intents", source("intents-holdout.csv"))   // at least 10 rows; a new version, the same answers
```

This fits the temperature on those rows, reports the error before and after on two halves of them, and writes the
calibrated head as the next version. Nothing is written when the scores were right already.

### Mistakes

The validation rows a run got wrong are kept with it, the surest mistakes first (`buildOptions(keepMistakes = 25)`;
0 keeps none). `mistakes("intents")` prints each row's label, the answer, both scores and the text. A model that
doesn't keep texts keeps the labels and scores without the texts.

### The embedding cache

Embedding is most of a build's time. Every text's vector is kept in `AMP/cache/embeddings/<encoder>/vectors.bin`
(the SHA-256 of the text, not the text, and its 768 numbers), so building again, a search or a fine-tune embeds only
the texts it hasn't seen (`fineTune: 40 of 52 texts came from the embedding cache`). The store belongs to one
encoder folder and changes when the encoder's model or tokenizer file changes, so an old vector never answers for
a new encoder. `embeddingCache.print()` shows what is kept and `embeddingCache.clear()` frees it; a store that grows
past 512 MB starts over. `buildOptions(cacheEmbeddings = false)` skips it, and a model with `keepTexts = false`
never uses it.

## Fine-tuning: teaching a model more

A new label, or a few rows the model gets wrong, don't need a new build. `fineTune` starts from the head the model
has, adds an output for every label it doesn't know, and trains on the new rows together with the rows it learned
from before:

```kotlin
val tuned = fineTune("intents", source("music.csv"))   // or listOf("play some jazz" to "music", …)
tuned.modelStatus()                                    // after, and the head before on the same rows
versions("intents")                                    // v2 fine-tune of v1, v1 build
rollback("intents", 1)                                 // v1's head again, as v3
```

A client of a served model can do the same over HTTP without the page or a script: `POST <path>/teach` puts a
sentence and its label in the model's queue, `POST <path>/retrain` fine-tunes on the queue in the background while
the model keeps answering, and the new head is swapped in when it is written
([Teaching a model while it listens](serving.md#teaching-a-model-while-it-listens)).

What it does, in order (`jetbrainDLBuilder/FineTune.kt`):

1. **Maps the labels.** A row's label is the model's label when they match in any case (`Lights` is `lights`). A
   label it doesn't know is a new label and needs at least 3 rows, so one can be held out to check it. A label a
   letter or a plural away from a known one is flagged (`'light' is taken as a new label; if it is meant to be
   'lights', spell it so in the file`). The rows are cleaned as a build cleans them, but one label, or one row of a
   label it knows, is enough.
2. **Replays what it learned from.** The rows of its data file, and of the files it was fine-tuned on, are trained
   on again (up to 200 per label) when they are still in `AMP/`, so the labels it knows are not forgotten. When a
   new row's text was learned under another label, the new row wins: that is how a correction is made.
3. **Grows the head.** Each new label's output starts where it will end up (weight imprinting). Its weights point
   from where the labels it has sit to the mean of the new label's rows, and its bias is set so it answers its own
   rows without taking the others'. With nothing to replay (a model downloaded from the website), *where the
   labels it has sit* is the mean of the training features the build kept in `module.json` (`featureMean`), so a
   new label still lands in the right place.
4. **Trains gently.** It runs 40 epochs at a rate of 0.002 (a build runs 150 at 0.01). The loss is weighted so a new
   label of ten rows counts as much as one of two hundred, and every weight it had is pulled back towards where it
   started (L2-SP, `stayNear = 0.01`). The new outputs are free; `freeze = 1` keeps the first layer as it is.
5. **Scores before and after** on the same held-out rows: a share of the new rows and of the replayed rows, or the
   related test file. It prints `on the 2 held-out new rows: accuracy 0.000 before, 1.000 after`, then the earlier
   rows, then all of them. An old label whose F1 falls by more than 0.1 is a warning (`the fine-tune forgets some
   of what it knew: 'weather' F1 0.950 → 0.710; replay more earlier rows (replayPerLabel), raise stayNear, or roll
   back`). Labels with no rows among the checked ones are left out of the macro F1.
6. **Writes the next version.** The head it replaces is kept under `versions/` and the guide's label list gets the
   new labels. `module.json` records `kind: fine-tune`, `parentVersion` and `fineTunedOn`. The run joins
   `metrics.json` with its baseline, so the Metrics tab shows before and after.

`fineTuneOptions(...)`:

| Option | Default | What it does |
|---|---|---|
| `epochs`, `learningRate`, `batchSize` | 40, 0.002, 16 | The fit |
| `stayNear` | 0.01 | How hard the weights it had are pulled back: higher forgets less and learns the new rows less; 0 lets them go anywhere |
| `replay`, `replayPerLabel` | true, 200 | Train on the earlier rows too, at most this many per label |
| `freeze` | 0 | The first n layers keep their weights; at least the last one learns |
| `balanceClasses` | true | Every label weighs the same in the loss |
| `validationFraction` | 0.2 | The share of the new rows, and of the replayed rows, held out to score before and after |
| `patience` | 0 | Early stopping, as in a build |
| `calibrate` | false | Fit a new temperature on the held-out rows afterwards (a calibrated model's temperature is taken out before training) |
| `minNewLabelRows` | 3 | The rows a new label needs |
| `cacheEmbeddings`, `keepMistakes`, `seed`, `preprocess` | as in a build | |

A fine-tune is refused, and the model left as it was, when the model doesn't exist, the encoder doesn't fit its
head, a new label has too few rows, or `freeze` covers every layer. The console says which. One that ends after
the model got another head meanwhile (rolled back, trained again, or destroyed and built anew under its name)
writes nothing, so that head isn't lost and no two heads get one number; run it again on the head it has now. A
calibration does the same.

## Versions and rollback

Every head a model replaces is kept. A build, a fine-tune, a calibration or a rollback copies the head it replaces,
its `module.json` and the run it was scored with into `AMP/models/intents/<name>/versions/v<N>/`; the ten newest are kept.
`versions("intents")` lists them, the current one first:

```
versions 'intents' · 3 kept
v3 (current) · v1 brought back · 2026-10-21 10:12 · 3 labels · accuracy 0.933 · macro F1 0.930
v2 · fine-tune of v1 · 2026-10-21 10:05 · 4 labels · accuracy 0.950 · macro F1 0.948 · fine-tuned v1 on music.csv: 8 new rows, 36 replayed; new label music
v1 · build · 2026-10-21 09:58 · 3 labels · accuracy 0.933 · macro F1 0.930
```

`rollback("intents", 1)` makes v1's head the model again *as a new version* (v3), so a rollback can itself be
undone. The description, icon and guide stay as they are, and v1's scores are appended to `metrics.json`, so the
page shows the scores of the head that answers. On the Models page the model's About tab lists its versions under
**Versions**, each kept one with **Roll back**, and its ⋯ menu rolls back to the newest kept one. **Compare** beside
it scores that head and the current one on the same csv without changing anything: each label's F1 then and now, the
confusion change, and (for a model that keeps texts) the rows they answer differently
([the Models page](models-page.md); `util/VersionCompare.kt`, which writes nothing of the model but
`plots/compare.png`, and keeps the rows' vectors in the embedding cache only when both heads keep texts). Publishing sends only the current head; the versions stay on this machine.

## What to label next

Labeling is the expensive part, so label what the model is least sure about:

```kotlin
textsToLabel("intents", "asked.txt", limit = 50)   // or a csv's text column; without a file, what it was asked over HTTP
textsToLabel("intents", "asked.txt", limit = 50, diverse = true)   // texts unlike each other among its closest calls
```

This ranks the texts by the gap between the model's two best scores, the closest calls first. It leaves out repeats,
near-repeats (cosine above 0.98 to one already picked) and the texts it was trained on (a training file that can't be
read is passed over). The closest calls are often many wordings of one confusion; with `diverse = true` (**Varied
close calls** on the Improve tab) it takes the closest calls, five times as many as it is asked for, and picks among
them one text at a time: first the closest call, then each time the text least like those picked so far (by the
cosine of the encoder's vectors). They are listed closest call first, as always, and a few labels cover more kinds of
text. Without it the output is exactly as before. The result goes to
`AMP/intents-to-label.csv`: an empty `label` column beside the model's `guess`, its `score` and the `runner-up`. Fill
in the labels and run `fineTune("intents", source("intents-to-label.csv"))`; rows left blank are dropped. Asking
again keeps the rows you labeled already at the top of the file and doesn't pick their texts a second time, so no
labeling work is lost. A model that doesn't keep texts writes no such file.

On the Models page this file is the model's queue, on its **Improve** tab: each text is labeled with a click, a
wrong answer on the **Try** line goes into it with the right label (`teach(model, text, label)`; the text the
queue has already is relabeled, and a label the model knows is written in its spelling), the answers of a batch of
pasted sentences it was unsure of go into it as rows to label with their guesses (the page's `queueGuesses` in `LabelQueue.kt`,
not part of the script API; after the rows there, leaving a text it has already as it is), and **Fine-tune on *n* rows** fine-tunes on its labeled
rows. Before any fine-tune starts, the page shows what it would do: rows per
label, new labels, what it would replay, and what blocks it (`planFineTune(model, rows)`, which reads the head
and the earlier files without training; a test checks that its counts are the ones the fine-tune then uses).
After a change to the head made on the page, `regradeQueue(model)` rewrites the queue's `guess`, `score` and
`runner-up` with the head it has now, keeping the labels, so the page can mark the labeled texts it answers right.

A script runs the same loop with `teach`, `queue`, `planFineTune` (on the labeled rows of the queue when given no
data, printed in the strip's words) and `regradeQueue`; the recipe is in the
[cookbook](cookbook.md#14-scores-you-can-trust-cross-validation-calibration-mistakes).

## Validating without training

A run can be added to `metrics.json` without rebuilding: on the Models page, **Validate on file** or **Validate
on sample data** runs the model on every row of a csv and stores accuracy, F1 per label and the confusion
matrix as one more run, with the file name as the validation set (`metrics/ModelValidation.kt`). This is how the
dummy model, which is written rather than trained, gets metrics at all. A validation keeps what a build keeps
beyond the scores: the calibration error and its gates, and the rows it got wrong.

## Reading the metrics

Metrics are plain local files, so anyone using the app can read them, from the console or from the Models page.

### In the console, as tables

```kotlin
validateMetrics("intents")                          // all labels
validateMetrics("intents", listOf("lights", "music")) // some labels
```

```
metrics 'intents' @ 2026-10-14 16:02:11: 240 samples (192 train / 48 validation on split 20%)
accuracy 0.917 (0.854–0.979)  macro F1 0.905 (0.836–0.966)  (ranges: the middle 95% of 1,000 resamples of its rows)
label   precision  recall  f1     support
lights  0.933      0.933   0.933  15
music   0.875      0.933   0.903  15
```

### How far the scores hold

A score on 48 rows is not the score on the next 48. Every run a build, fine-tune, calibration or validation adds
keeps where its accuracy and macro F1 fell when its rows were drawn again 1,000 times (`metrics/ScoreIntervals.kt`):
each draw takes as many rows of each label as the run had, at random and with repeats, and is scored as the run was
(the same F1 rule, so the labels a fine-tune left out of its macro F1 stay out), and the middle 95% of the draws is
the range, `accuracyRange` and `macroF1Range` in `metrics.json`. The draws are seeded, so the same rows give the
same range every time, and they use random numbers of their own, so training never sees them. The narrower the
range, the more the score can be trusted: 0.917 (0.854–0.979) says that another 48 rows like these could score
anywhere in there. A run that got every row right, or every row wrong, has no range, since every draw scores the
same; runs from before the ranges were kept have none either. A rollback's run keeps the ranges its version had.
The Models page gives them in brackets on the Metrics tab's scores line, and the scores chart draws macro F1's as a
bar under its mark.

### Rows it learned from

A row a run is scored on that is also a row the model learned from tests what it remembers, so it flatters the
scores. Every run looks for them (`jetbrainDLBuilder/Leakage.kt`) and says how many it found as a warning of the run,
printed and kept with it; nothing that is trained changes. A row repeats one it learned from when it is

- **the same text**: the same letters and digits, ignoring case, spaces and punctuation (*Turn on the lights!* and
  *turn on the lights*); a text with no letters or digits is like no other;
- **nearly the same to the encoder**: its vector within a cosine of 0.98 of a training row's. The run has the vectors
  already, so this costs no time in the encoder.

| Run | Its rows | Against | Looks for |
|---|---|---|---|
| build | the validation rows (the split, or the test file) | the training rows | both |
| fine-tune | the rows it is scored on (the held-out rows, or the test file) | the rows it trained on, new and replayed, and the files its head learned from before, as a validation looks in them (replay trains on a sample of them only); a held-out replayed row, there to see what it forgot, only among the rows it trained on | both, the same text only in those files; a fine-tune with too few rows to hold any out is scored on its training rows and says so instead |
| validation | the file's rows of labels it knows | the files it learned from: its data and the files fine-tunes taught it, those still in `AMP/` (one that can't be read is passed over) | the same text: its rows go through a session, which gives no vectors |
| calibration | its rows | as a validation | the same text; past a third of the rows, the warning says the temperature may be too low for new texts instead |

A build or fine-tune already leaves out a test file's rows whose text is a training row's in lowercase (the console
says how many); the check finds the rows that differ from one in punctuation or that the encoder can hardly tell
from one. For a model that keeps texts, the build's console gives a few of the pairs, and the run keeps ten in
`metrics.json` (`leakage`), which the Metrics tab shows in the warning's tooltip; a model that keeps no texts keeps
only the counts.

### In the console, as ASCII charts

```kotlin
metricsChart("intents")             // accuracy, macro F1 and the F1 of every label as bars
metricsChart("intents", listOf("lights"))
metricsHistory("intents")           // accuracy and macro F1 across every build, as a line chart
confusionMatrix("intents")          // the last validation's confusion matrix as a shaded grid
```

`metricsChart` draws one bar per row with precision, recall and support beside it:

```
metrics 'intents' · build 3 of 3 · 48 validation samples on split 20%
F1 per label (bar), precision p, recall r, support n
accuracy ███████████████████████████░░░ 0.917
macro F1 ███████████████████████████░░░ 0.905
lights   ████████████████████████████░░ 0.933  p 0.933  r 0.933  n 15
music    ███████████████████████████░░░ 0.903  p 0.875  r 0.933  n 15
weather  ██████████████████████████░░░░ 0.880  p 0.917  r 0.846  n 18
```

`metricsHistory` plots every build on one canvas (● accuracy, ○ macro F1, ◆ where they meet) with the build
numbers under the columns and a dated line per build, so you can see whether a new data file helped.

```
history 'intents' · 3 builds
1.00 ┤            ◆
     ┤      ●
     ┤      ○
0.50 ┤●
     ┤○
0.00 ┤
     └─────────────
      1     2     3
      ● accuracy   ○ macro F1   ◆ both
```

`confusionMatrix` prints truth in rows and prediction in columns; the shade of each cell is its share of the row
and ✓ marks the diagonal.

Four more read what a run keeps beyond its scores:

```kotlin
mistakes("intents")            // the validation rows the last run got wrong, the surest first
suspects("intents")            // training rows whose label looks wrong (a build with folds)
calibrationChart("intents")    // how often each score is right, and what a gate keeps
trainingCurves("intents")      // loss and accuracy by epoch of the chosen head, or of the last fine-tune
```

```
calibration 'intents' · v1 · build · 10 validation rows on split 20%
expected calibration error 0.176 (0.164 before the temperature 1.623 was folded in); 0 means a score of 0.8 is right 80% of the time
how often right per top score (bar), how sure on average (sure), answers in the bin (n)
0.80–0.90 ██████████████████████████████ 1.000  sure 0.896  n 1
0.90–1.00 █████████████████████████░░░░░ 0.833  sure 0.933  n 6
answer only from  keeps  right
0.00              100%  0.900
0.80               70%  0.857
0.95               20%  1.000
a gate at 0.95 keeps 20% of the rows, and 100% of what it keeps is right
```

`trainingCurves` draws the loss and the accuracy by epoch on the training rows (●) and the validation rows (○);
a validation loss that rises while the training loss falls is a head memorising. The other heads of the build get a
line each (`dense[16]: loss 0.990 → 0.055, validation 0.859 → 0.464, lowest validation loss at epoch 8`).

Each of these returns the report, so they chain: `metricsChart("intents").history().confusion()`. A model that
was never built logs an error telling you to run `modelBuilder` first, and a run kept before the analysis was says
so (`the last run of 'intents' kept no mistakes (it was made before they were kept)`).

### On the Models page

The **Models** page (second sidebar icon) draws the same numbers as charts on the model's **Metrics** tab: the
scores per label (**Per label**) and the **Confusion matrix** of a validation run (one run at a time, picked with
the run chips), how the heads of a build (or a fine-tune) learned (**Training by epoch**) and how far a run's scores
can be trusted (**Calibration**), the scores across the runs (**Scores over runs**), and from the sessions how sure the
model is (**Confidence by label**) and how long an answer takes (**Answer time**). Each chart has a short title and
only the figures it alone shows; what a chart means is in the Guide tab. They are read from `metrics.json` and `sessions.log`; the
rows it got wrong and the ones whose label looks wrong are listed under the charts. A heatmap cell's numbers are
white or black, whichever stands out more from the cell, so they read at 4.5:1 on any shade. The ASCII charts stay
in the console. Asking a model a sentence and correcting its answer is the **Try** line under its header; fine-tuning,
calibrating, rolling back and the texts to label are its **Improve** tab (also its ⋯ menu, its About tab's
**Versions** and its Data tab), and every one of them that changes the head offers **Undo** in its notice. The page also builds a model from a csv (**New model**: name, a sentence about
it, data file, optional test file), and every model is a component with **Open session**, **Listen**, the serving
settings behind the gear, and **Publish** (Keycloak sign-in); every open session can be terminated there and its
log (what it was asked and answered) read. Details in [Models page](models-page.md).

## Using a model from a script

```kotlin
val m = initMod("intents")
log(m.predict("turn the lights on"))             // "lights"
log(m.inference("turn the lights on").first())   // "lights:0.97"
m.close()
```

`initMod()` with no name opens the bundled NER model; `importModelOnnx("path.onnx")` opens any ONNX model behind
the same `inference / getStatus / close` interface. Models made elsewhere open the same way: `initMod("<name>")` for a
folder of `AMP/models/imported` (a Hugging Face repo), `importModel("path")` or `initMod("path")` for a file or folder:
ONNX, TorchScript on PyTorch, or a TensorFlow SavedModel, with text in through its `tokenizer.json`, labels from its
`config.json` and a tensor API (`.inputs`, `.outputs`, `.run(floatArrayOf(…))`). TFLite and weights alone
(safetensors, `pytorch_model.bin`, gguf, h5) are refused with the way to ONNX. See
[Models from outside](sandbox-scripting.md#models-from-outside). Every `initMod("name")` is a session the Models page lists
and can terminate; what it was asked and answered is logged per session (behind **Log** on the page and in
`AMP/models/intents/<name>/sessions.log`). ORT sessions are also printed with `sessions.print()`. A blank text has no
answer (a listener answers `400` for one): `inference` gives an empty list, `scores` an empty map and `predict`
fails with "the model answered nothing", and the console says the text was empty.

## The model card

A model's guide (`AMP/models/intents/<name>/guide.md`, its **Guide** tab, published with it) holds a short model card between
two marker lines (`metrics/ModelCard.kt`), written again after every run of the model (a build, fine-tune,
calibration, validation or rollback) and when the guide is saved on the page:

```markdown
<!-- amie:model-card: written again after every run of the model; your own text goes outside these two lines -->
## Model card

- version: v2, fine-tune of v1, 2026-10-21
- data: intents.csv, 45 rows (greeting 15, lights 15, weather 15)
- fine-tuned on: music.csv, 12 rows (music 12)
- earlier versions: v1 (build)
- last run: validation of v2, 2026-10-21, on 45 rows (sample-intents.csv): accuracy 0.933 (0.867–0.978), macro F1 0.931 (0.862–0.977)
- calibration: none; calibration error 0.041 on the last run
- mixes up: “lights” taken for “weather” 2×
- texts: kept; its logs, runs and queue show the texts it is asked and gets wrong
<!-- /amie:model-card -->
```

The ranges are [how far the scores hold](#how-far-the-scores-hold), shown when the resamples didn't all score the
same; a last run whose rows repeat rows it learned from says how many ([rows it learned from](#rows-it-learned-from)).
The rows per label are the ones each version learned from, after cleaning, kept with its head when it was made
(`dataRows` and `fineTunedRows` in `module.json`), so a queue that grew afterwards, an edited csv or a file that isn't
on this machine doesn't change them; for a model from before they were kept the card counts the file as it is now,
and names a file that isn't here without its rows. Rows a script gave without a file read `10 rows a script gave
(music 10)`. The dates are UTC. The card holds no row texts, only counts and label names, so a model that keeps no
texts has one too.

Nothing outside the marker lines is changed: write your own text above or below them. A new guide has the card after
its description; a guide from before (or written by hand) gets it at its end. What is written between the markers is
replaced at the next run, and a guide whose markers don't pair up (one deleted, or a second card pasted in) is left as
it is, with a line in the console. The Guide tab doesn't show the marker lines, and while the guide is being edited
a run that writes the card leaves what is typed as it is; **Save** then writes it with the card brought up to date.
Everything that writes the guide (Save, the card, a fine-tune adding its labels to the list, a rename changing the
title) takes its turn, so none undoes another, and Save never holds the page up while a run writes the card.

## Publishing

Signed in with Keycloak, select a model on the Models page and press **Publish**: the model goes up to your package folder on the Amie backend (`POST /upload`,
`progLanguage=onnx`) as `<name>.onnx`, `<name>.module.json` and `<name>.guide.md`, so two models never overwrite
each other, and the website's dashboard lists them. A guest pressing it is sent to the sign-in page. Requires the backend from
[Amie backend](backend-integration.md), which stores the files in the Amie S3 bucket. Package jars go the other way: a
script opens one the server lists with `initMod("name.jar")` (see [Packages](sandbox-scripting.md#packages)).
