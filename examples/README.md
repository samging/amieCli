# Examples

Whole amie projects to learn from, written on the amie API: models, metrics, packages, templates and serving. Each
folder has a README with what it does and its results, and a `setup.sh` that adds it to the AMP folder. Arguments to `setup.sh` go to amie, such as `--amp ~/scratch/amp` for another AMP.

| Example | What it shows | Set up, then run |
|---|---|---|
| [clinc-oos](clinc-oos) | Out-of-scope detection on CLINC150: six templates from the download to a served model, three of them called from `main.kts` like functions, and a results table. 94.7% in-scope accuracy without a threshold; 88.1% in scope with 84.9% out-of-scope recall with one | `clinc-oos/setup.sh`, then `amie run clinc-oos` |
| [google-assistant](google-assistant) | One template: `modelBuilder` with 5 folds, all six metric reports, then each request answered by google-calendar, google-tasks, google-drive, gmail-reader, google-translate or the browser; it takes typed requests and can be called from other scripts with `template()` | `google-assistant/setup.sh`, then `amie template run google-assistant` |

Both need the bge encoder in `AMP/models/bert/bge`; `scripts/setup.sh` downloads it.

To change one, edit its template: `amie template edit clinc-threshold`, `amie template edit google-assistant`.
