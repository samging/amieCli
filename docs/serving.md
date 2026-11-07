# Serving a model

Every exported model can answer HTTP requests from inside the app.

1. Open **Models** and select the model.
2. The gear (⚙) at the right of its header opens the serving settings: **Host** (default `127.0.0.1`), **Port**,
   **Path** (default `/models/<name>`), the **Methods** (POST, GET), **Min score** and **Fallback**
   ([below](#answering-only-when-it-is-sure)), and under **Access** who may ask and how much
   ([below](#who-may-ask-and-how-much)). **Save** writes `AMP/models/intents/<name>/serving.json`.
3. **Listen on :port** starts the listener: the header says `listening 127.0.0.1:8765`, and **Stop** ends it.
   While the settings hold a value they can't take (a port, a min score, an origin, a limit), Listen and Save wait,
   and their tooltips say which to fix.

```bash
curl -X POST http://127.0.0.1:8765/models/intents/predict -d '{"text":"turn the lights on"}'
curl 'http://127.0.0.1:8765/models/intents/predict?text=turn+the+lights+on'
```

The POST and GET URLs are listed under the settings with **Copy** and **Copy curl**.

- Every request is appended to `AMP/models/intents/<name>/incoming.jsonl` (for a model that keeps no texts, the text's
  length instead of the text).
- Every request, whatever came of it (answered, 400, 401, 403, 405, 413, 429, 500, a CORS preflight), is also one
  line of the model's access log, `AMP/models/intents/<name>/access.log`, tab-separated: time, request id, client address,
  method, path **without the query** (a GET's text is in it), status, milliseconds, the text's length, the answer's
  bytes and what came of it, in fixed forms: an 8-hex-digit id, `12ms`, `in 17 chars`, `out 96B`, and the outcome
(`lights 0.912`, `abstained`, `refused: …`, `preflight`, the error); status `0` means no answer was sent. It never holds a text, for any
  model. Each answer carries its id as the header `X-Request-Id`, so a client can name its line. Listening and
  stopping are lines of it too (with what the access settings require, and at the stop `stopped after 8 requests
  (1 failed, 2 refused)`). Past 2 MB it
  moves to `access.1.log`, the one before that going. The last twelve show under the settings in the **Requests** fold (closed
  until opened), newest first, errors in red, read again as the file grows. From a script,
  `accessLog("name", last = 20)` prints and returns them.
- The header's status tooltip counts what the listener did since it started: *8 requests, 5 abstained, 1 error,
  2 refused* (refused: turned away by the [access settings](#who-may-ask-and-how-much)).
- Changes to the settings apply after **Stop** and **Listen**; the settings say so while it listens. **Save**
  meanwhile keeps them for that next start, and Stop leaves what was saved as it is.
- Models that were listening when the app closed start listening again on the next launch.
- A new head of the model (a fine-tune, a build under the same name, a rollback, a `/retrain` from a client) never
  stops the listener: the new head is opened beside the one answering, put in its place once the answers in flight
  are done, and the old one is closed after. Every answer says which head gave it (`"version": 3`), the access log
  has a line for the swap, and the header's status tooltip counts the swaps.

## Teaching a model while it listens

A client that sees the model answer wrongly can correct it over the same port, without the Models page, and have
the model retrained on its corrections while it goes on answering. Two routes stand beside `/predict` under the
model's path:

| Request | What it does | Answer |
|---|---|---|
| `POST <path>/teach` with `{"text": "lamp please", "label": "lights"}` | Writes the sentence with its label at the top of the model's queue of texts to label, `AMP/<name>-to-label.csv`, the same file **Teach** on the page and `teach()` in a script write to; a text the queue has already gets the new label instead. A label the model knows is taken in its own spelling (`Lights` is `lights`); any other label is a new one, which a retrain learns once it has three rows or more | `200` `{"model": "intents", "version": 2, "text": "lamp please", "label": "lights", "queue": "intents-to-label.csv", "labeled": 4}` |
| `DELETE <path>/teach` with `{"text": "lamp please"}` or `?text=lamp+please` | Takes the sentence out of the queue, whatever the case it was written in; a sentence that isn't there changes nothing | `200` `{"model": "intents", "text": "lamp please", "removed": true, "label": "lights", "queue": "…", "labeled": 3}` (`"removed": false` and no `label` when it wasn't there) |
| `GET <path>/teach` | The queue as it is: every row with its label (empty while unlabeled), the model's guess, its score and the runner-up | `200` `{"model": "intents", "version": 2, "queue": "…", "labeled": 3, "unlabeled": 5, "rows": [{"text": "…", "label": "lights", "guess": "weather", "score": 0.41, "runnerUp": "lights"}, …]}` |
| `POST <path>/retrain` | Starts a fine-tune on the labeled rows of the queue, in the background, and answers at once. The model keeps answering with the head it has until the new one is written; then the new head is opened, swapped in and the old one closed ([above](#serving-a-model)), the queue is graded again with it, and the head before is kept as a version for a rollback. `?wait=true` or `{"wait": true}` holds the answer until it is over | `202` `{"model": "intents", "status": "running", "version": 2, "labeled": 3, "from": 2, "rows": 3, "startedAt": "…", "phase": "training 12/40 · …"}`; with `wait`, `200` with `"status": "done"`, `"to": 3`, `"detail": "fine-tune v2 → v3 · accuracy 0.900 → 0.967 on …"`, or `500` with `"status": "failed"` and the reason in `detail` |
| `GET <path>/retrain` | How the last retrain went, or `"status": "idle"` with how many labeled rows wait | `200` as above |
| `POST <path>/retrain` while one runs, while the page builds or fine-tunes anything, or with no labeled row in the queue | Refused; nothing changes | `409` `{"error": "nothing to learn: intents-to-label.csv has no labeled rows; POST a text and its label to /models/intents/teach first", "status": "idle", …}` |
| `POST <path>/teach` for a model that keeps no texts, or one renamed or destroyed meanwhile | Refused: no text of another person's lands in a file | `409` `{"error": "…"}` |
| `POST <path>/teach` without a text or a label, `DELETE` without a text | | `400` `{"error": "send {\"text\": \"...\", \"label\": \"...\"}"}` |

```bash
curl -X POST http://127.0.0.1:8765/models/intents/teach -d '{"text": "lamp please", "label": "lights"}'
curl -X DELETE 'http://127.0.0.1:8765/models/intents/teach?text=lamp+please'
curl http://127.0.0.1:8765/models/intents/teach
curl -X POST 'http://127.0.0.1:8765/models/intents/retrain?wait=true'
curl http://127.0.0.1:8765/models/intents/retrain
```

What a retrain does is exactly a [fine-tune](models.md#fine-tuning-teaching-a-model-more) on the queue with the
default options, as **Fine-tune on it** under the queue on the page's Improve tab does: the rows it was trained on are replayed so it
keeps what it knew, a label it doesn't know gets an output of its own, before and after are scored on the same
held-out rows, and the console and the Metrics tab show the run. The rows stay in the queue after it (graded with
the new head, so the Improve tab shows which it answers right now); take out of it what the model shouldn't learn
again. A model that was fine-tuned on its queue names it under *fine-tuned on*, so the next retrain replays it.

Lessons go through the [access settings](#who-may-ask-and-how-much) as every request does: the key, the origins,
the size and the minute all apply. Since a lesson changes the model, a request from another machine is answered
`403 teaching from another machine needs a key` unless a **Key** is set; this machine's own requests teach as they
are. One retrain runs at a time, over every model, and none while the page is building, fine-tuning or calibrating
something (`409 busy fine-tuning weather`); the lessons meanwhile land in the queue and the next retrain takes
them. The access log has a line per lesson (`taught: lights`, `forgot: weather`, `retrain: done v2 → v3`), never
the text.

## Answering only when it is sure

A model answers every text with its best label, however unsure it is. With **Min score** set, a request whose best
score is under it is not answered with that label: the model abstains. It answers the **Fallback** label instead
(such as `unknown`, scored at the min score), or no label when Fallback is empty, marks the answer
`"abstained": true` and moves its own scores to `"ranking"`, so nothing it said is lost:

```json
{"model": "intents", "predictions": [{"label": "unknown", "score": 0.75}], "abstained": true,
 "ranking": [{"label": "lights", "score": 0.41}, {"label": "music", "score": 0.28}, …]}
```

At or over the min score the answer is the model's own with `"abstained": false`. Without a min score (empty, or 0)
it is exactly as before, with no `abstained` key, and `serving.json` holds no `minScore` or `fallbackLabel`.

Under Min score the settings suggest a value from the model's last run: the lowest score at which its answers were
right 95% of the time on that run's rows, answering a tenth of them at least (*suggested 0.75: 95% right, answers
90%*; its tooltip says more, and **Use 0.75** puts it in the field). The scores tried are the gates the run kept,
0.05 apart, which the Metrics tab's chart of how far the scores can be trusted shows. A model right 95% of the time
at any score needs none (*97% right at any score*), and of one that no score makes that right it says so (*no score
keeps 95% right*): fine-tune or calibrate it first. A run of rows like the ones it will be asked suggests best; a
calibrated model's scores mean what they say, so its min score holds better on new texts.

Abstentions are counted in the header's status tooltip, the console line says what it would have answered
(`-> unknown (abstained: lights 0.415 under 0.75)`), and `incoming.jsonl` keeps the model's own label and score with
`"abstained": true`.

## Who may ask and how much

Out of the box a listener answers anyone who can reach its address, which on the default host `127.0.0.1` is this
machine alone. Before serving on another host (`0.0.0.0`, a LAN address) or to a web page, the **Access** fold of
the gear panel sets four limits. Each is off while its field is empty, and then the listener behaves as it did
before there were any:

| Field (`serving.json`) | What it does | Past it |
|---|---|---|
| **Key** (`apiKey`) | A request must send the key as the header `Authorization: Bearer <key>`. **New key** puts a random one in the field (32 characters), **Copy** copies it; the field shows it as dots | `401` `{"error": "send the key as the header Authorization: Bearer <key>"}`, with `WWW-Authenticate: Bearer realm="amie"` |
| **Origins** (`cors`) | The web pages that may call the model from a browser, as the browser names them: `https://app.example.com, http://localhost:3000`, or `*` for any. A request from one of them gets the CORS headers (`Access-Control-Allow-Origin` with its origin, `Vary: Origin`, `Access-Control-Expose-Headers: Retry-After`), on errors too, and its preflight (`OPTIONS`) a `204` with the methods and `OPTIONS`, the `Content-Type` and `Authorization` headers allowed and `Access-Control-Max-Age: 600`. Without Origins an `OPTIONS` gets `405` | a browser's request from a page not on the list: `403` `{"error": "the origin https://elsewhere.example is not allowed"}`. A request without an `Origin` header (curl, a server, another program) isn't a browser's and goes on |
| **Max bytes** (`maxBodyBytes`) | The most bytes a request's body, or a GET's text, may have. A body is read no further than one byte past it, and not at all when its `Content-Length` says it is over | `413` `{"error": "the request is over 4096 bytes"}`, and the model isn't asked |
| **Per minute** (`rateLimitPerMinute`) | How many requests a minute it answers, every client together, counted over the last 60 seconds | `429` `{"error": "over 60 requests a minute; try again in 12 s"}`, with `Retry-After: 12` |

They are checked origin, method, key, per minute, then size: a request without the key uses up none of the
minute, an oversized one does. A request turned away is logged in the console without its text, is never written to `incoming.jsonl`, and
counts as **refused** in the header's tooltip. With a key set, **Copy curl** sends it:

```bash
curl -s -X POST http://127.0.0.1:8765/models/intents/predict -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer 3q2-7wEAAAB9pY1vX0dYb1JnS2hRcFdN' -d '{"text": "turn the lights on"}'
```

The key is kept in `serving.json` as it is, on this machine: Publish never sends it, and neither do the model's
versions. Anyone who can read the AMP folder can read it, so a key guards the port, not the files. A new key, or
none, applies after **Stop** and **Listen**, as every setting does.

## The HTTP contract

| Request | Answer |
|---|---|
| `POST <path>/predict` with `{"text": "…"}` or a plain-text body | `200` `{"model": "intents", "predictions": [{"label": "lights", "score": 0.97}, …]}`, best first |
| `GET <path>/predict?text=…` | the same |
| a model with a **Min score** whose best score reaches it | the same, with `"abstained": false` |
| a model with a **Min score** whose best score is under it | `200` `{"model": "intents", "predictions": [{"label": "unknown", "score": 0.75}], "abstained": true, "ranking": […]}`: the Fallback label scored at the min score, or `[]` without one, and the model's own scores under `ranking` |
| no text (empty body, no `text` key, no `?text=`) | `400` `{"error": "send text as {\"text\": \"...\"}, a plain-text body or ?text="}` |
| a method not in the model's **Methods** list | `405` `{"error": "PUT not allowed, use [POST, GET]"}` |
| with **Access** set: a browser page not in **Origins**, no key, too big, too many | `403`, `401`, `413`, `429` ([above](#who-may-ask-and-how-much)) |
| with **Origins** set: a browser's preflight `OPTIONS` from a page on the list | `204` with the CORS headers |
| the model threw while answering | `500` `{"error": "…"}`, and the page's **errors** count goes up |
| nothing listens on the port | the connection is refused (no HTTP answer at all) |
| `POST`, `DELETE`, `GET <path>/teach` and `POST`, `GET <path>/retrain` | the lessons: [Teaching a model while it listens](#teaching-a-model-while-it-listens); a method not in that route's list is `405` |

Every answer is `application/json`, and one from a model whose module says its version carries it (`"version": 2`,
the head that answered). The path is `/models/<name>/predict` unless **Path** was changed. `/predict`, `/teach`
and `/retrain` are always appended, so a Path of `/intents` serves `/intents/predict`, `/intents/teach` and
`/intents/retrain`.

## From a script

```kotlin
val url = serve("intents", port = 8765)          // null when it could not start; the console says why
log(url ?: "not started")
stopServing("intents")
serve("intents", minScore = 0.75, fallbackLabel = "unknown")   // abstains under 0.75
accessLog("intents", last = 5)                    // its last five access log lines, printed and returned
```

`serve("name")` with no other argument reuses what the page saved in `serving.json`; a port, host, path, methods,
min score or fallback given here are saved there too, and the access settings saved on the page stay as they are. `minScore = 0.0` turns a saved gate off, as emptying Min score
on the page does, and `fallbackLabel = ""` takes a saved fallback away. The full example with the request code and
every error handled is in the [Cookbook](cookbook.md#12-serve-a-model-and-call-it-over-http). Four reasons a start
fails, each one Error line in the console: a min score outside 0 to 1, the model cannot be loaded, the port is
already taken by another program, or the path is already served by another model on that port.
