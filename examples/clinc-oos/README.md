# Out-of-scope detection on CLINC150

An amie project from data to a served model: a classifier for 150 intents that also says "out of scope" for requests
that match none of them, measured on the CLINC150 benchmark. Six templates run in order, then a results table.

| Model, on the CLINC150 test split | In-scope accuracy | Out-of-scope recall |
|---|---|---|
| 150 intents, no threshold | 94.7% | 0.0% |
| 150 intents with a threshold | 89.1% | 77.4% |
| 151 intents (out of scope taught) with a threshold | 88.1% | 84.9% |

## Run it

You need amie on the PATH (`amie/install.sh`, or `./amie` from the repository root with `AMIE=./amie`) and the bge
encoder in `AMP/models/bert/bge/`, which `scripts/setup.sh` downloads.

```bash
examples/clinc-oos/setup.sh
```

```bash
amie run clinc-oos
```

`setup.sh` saves the six templates in your AMP and makes the project `clinc-oos` that runs them; arguments go to amie,
so `examples/clinc-oos/setup.sh --amp ~/scratch/amp` sets it up in another AMP folder (then
`amie --amp ~/scratch/amp run clinc-oos`). The run takes about ten minutes on a laptop. The first step downloads
CLINC150 (2.5 MB) into the AMP folder once.

| Step | Template | What it does |
|---|---|---|
| 1 | [`clinc-data`](templates/clinc-data.kts) | Downloads CLINC150 once, shows the training file as pandas' `info()`, attaches the validation file |
| 2 | [`clinc-train`](templates/clinc-train.kts) | Trains two heads on the frozen encoder, keeps the better by macro-F1 on validation, calibrates it |
| 3 | [`clinc-threshold`](templates/clinc-threshold.kts) | Picks the out-of-scope threshold on validation, then scores the test split |
| 4 | [`clinc-errors`](templates/clinc-errors.kts) | The surest mistakes and the intent pairs confused most, on validation |
| 5 | [`clinc-oos-class`](templates/clinc-oos-class.kts) | Teaches out of scope as a 151st intent, kept only if validation says it is better |
| 6 | [`clinc-serve`](templates/clinc-serve.kts) | Serves the model over HTTP with the threshold and asks it three questions |
| | [`main.kts`](main.kts) | The results table |

The results are written to `AMP/clinc-oos/`, and the model is `clinc`, on the Models page and in
`amie model list`. To ask it yourself:

```bash
amie -c 'log(sessionMod("clinc").predict("set an alarm for 7"))'
```

## The question

A virtual assistant trained on a fixed set of intents still hears requests that match none of them. A plain classifier
always picks one of its intents. The question here is how well a small model can do both: answer the 150 intents it
knows, and say "out of scope" for everything else.

## Data

CLINC150 (Larson et al., 2019, "An Evaluation Dataset for Intent Classification and Out-of-Scope Prediction"), from
github.com/clinc/oos-eval, licensed CC BY 3.0.

| Split | In-scope queries | Out-of-scope queries |
|---|---|---|
| Train | 15,000 (100 for each of 150 intents) | 100 |
| Validation | 3,000 | 100 |
| Test | 4,500 | 1,000 |

The validation split chose the model and the threshold. The test split was used only for the final numbers.

amie's leakage check found that 242 of the 2,997 validation rows repeat a training row: 21 have the same text and 221
are nearly the same to the encoder. Validation scores are therefore a little optimistic, which is one more reason to
report test.

## Method

1. **Encoder.** BGE, a BERT-base-sized sentence encoder, frozen. Each query becomes one mean-pooled vector.
2. **Head.** Two candidates trained on those vectors, with early stopping on a tenth of the training rows. The better
   macro-F1 on validation was kept.

   | Head | Parameters | Validation accuracy | Validation macro-F1 |
   |---|---|---|---|
   | Linear | 115,350 | 0.936 | 0.935 |
   | One hidden layer of 256, dropout 0.2 | 235,414 | 0.956 | 0.956 |

3. **Calibration.** A temperature fitted on validation (1.27). The expected calibration error was 0.013 before and
   after, so the scores were already honest; on held-out halves it went 0.015 to 0.016, so it doesn't help this model.
4. **Threshold.** When the best score is under the threshold, the answer is "out of scope". The threshold maximizes
   the mean of in-scope accuracy and out-of-scope recall on validation, where in-scope counts as right only when the
   intent is right and the score clears the threshold.
5. **Out of scope as a 151st intent.** The paper's other approach: fine-tune with the 100 out-of-scope training
   queries as a new intent, recalibrate, and pick the threshold again. This was kept only if validation said it was
   better. It was: 0.907 against 0.877.

## Results on test

| Model | Threshold | In-scope accuracy | Out-of-scope recall |
|---|---|---|---|
| 150 intents, no threshold | none | 94.7% | 0.0% |
| 150 intents with a threshold | 0.78 | 89.1% | 77.4% |
| 151 intents (out of scope taught) with a threshold | 0.72 | 88.1% | 84.9% |

Teaching out of scope as an intent raised out-of-scope recall by 7.5 points for 1 point of in-scope accuracy.

The threshold is a trade-off, shown here for the 150-intent model on test:

| Threshold | In-scope accuracy | Out-of-scope recall |
|---|---|---|
| 0.3 | 94.6% | 18.3% |
| 0.5 | 93.6% | 50.5% |
| 0.7 | 90.8% | 70.8% |
| 0.8 | 88.5% | 79.2% |
| 0.9 | 83.4% | 87.7% |

Where to sit on that curve is a product decision: what a wrong action costs against what "I didn't get that" costs.

## Error analysis

133 of the 3,000 validation queries are answered wrongly. The intents confused most often are close in meaning:

| Right intent | Answered as | Times |
|---|---|---|
| change_user_name | change_ai_name | 7 |
| shopping_list_update | order | 5 |
| restaurant_reservation | accept_reservations | 4 |
| change_accent | pin_change | 3 |
| payday | income | 2 |
| transactions | spending_history | 2 |

Most are pairs a person would also hesitate over. They point to the encoder, not the head: a frozen sentence encoder
puts "change your name" and "change my name" close together.

Out-of-scope queries that get through are usually near an intent: "how much has the dow changed today" scores highest
for `date`, and "what is the best way to learn guitar" for `play_music`. Both are still caught by the threshold.

## What the project found in amie

Validation and test disagreed at first: 95.6% against 89.4%. The cause was amie's `sourceFile()`, which split CSV
fields at every comma, even inside quotes, so every query containing a comma was read with a broken label. It now
reads files exactly as `source()` does, and test in-scope accuracy is 94.7%, in line with validation. The project
also added `quietly { }` to amie, which leaves out the console line each model answer writes, so a script can score
thousands of queries.

## Limits and next steps

- The threshold is chosen on only 100 out-of-scope validation queries, so it is noisy.
- The encoder is frozen. Fine-tuning it end to end is the clearest way to separate the confused pairs.
- One global threshold treats every intent the same. A threshold per intent, or a score on the distance to the
  nearest training example, may catch more out-of-scope queries.
