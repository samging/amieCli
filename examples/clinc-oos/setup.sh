#!/usr/bin/env bash
# Adds the clinc-oos example to the AMP folder: its six templates and the project that runs them in order.
# Arguments go to amie, such as --amp ~/scratch/amp for another AMP. Then: amie run clinc-oos
set -euo pipefail
# AMIE names another amie, such as AMIE=./amie from the repository root; a relative path is resolved before the cd
AMIE="${AMIE:-amie}"
if [[ "$AMIE" == */* ]]; then AMIE="$(cd "$(dirname "$AMIE")" && pwd)/$(basename "$AMIE")"; fi
cd "$(dirname "$0")"

save() { "$AMIE" "$@" >/dev/null; }
save "$@" template save clinc-data templates/clinc-data.kts --description "CLINC150 downloaded once, the training file as pandas' info(), its validation file related"
save "$@" template save clinc-train templates/clinc-train.kts --description "A head on the frozen encoder, linear or one hidden layer, picked by macro F1 on validation, then calibrated"
save "$@" template save clinc-threshold templates/clinc-threshold.kts --description "The out-of-scope threshold chosen on validation, then in-scope accuracy and out-of-scope recall on test"
save "$@" template save clinc-errors templates/clinc-errors.kts --description "The surest validation mistakes and the intent pairs mixed up most"
save "$@" template save clinc-oos-class templates/clinc-oos-class.kts --description "Out of scope as a 151st intent by fine-tuning, kept only when validation says it is better"
save "$@" template save clinc-serve templates/clinc-serve.kts --description "The model over HTTP, answering oos under the threshold, asked three times"

if ! "$AMIE" "$@" project show clinc-oos >/dev/null 2>&1; then
    "$AMIE" "$@" new clinc-oos clinc-data clinc-train clinc-threshold clinc-errors clinc-oos-class clinc-serve >/dev/null
fi
# The project's main.kts: the results table, printed after the six templates
"$AMIE" "$@" -c "java.io.File(org.example.project.getDirectory().toString(), \"projects/clinc-oos/main.kts\").writeText(java.io.File(\"$PWD/main.kts\").readText())"

"$AMIE" "$@" project show clinc-oos
echo "amie run clinc-oos runs it"
