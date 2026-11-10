#!/usr/bin/env bash
# Adds the google-assistant example to the AMP folder: the package jars it asks that aren't there yet, and its template.
# Arguments go to amie, such as --amp ~/scratch/amp for another AMP. Then: amie template run google-assistant
set -euo pipefail
# AMIE names another amie, such as AMIE=./amie from the repository root; a relative path is resolved before the cd
AMIE="${AMIE:-amie}"
if [[ "$AMIE" == */* ]]; then AMIE="$(cd "$(dirname "$AMIE")" && pwd)/$(basename "$AMIE")"; fi
cd "$(dirname "$0")"

have="$("$AMIE" "$@" package list 2>/dev/null || true)"
for jar in google-calendar.jar google-tasks.jar google-drive.jar gmail-reader.jar google-translate.jar browser.jar; do
    if ! grep -q "$jar" <<<"$have"; then "$AMIE" "$@" package add "$jar"; fi
done

"$AMIE" "$@" template save google-assistant google-assistant.kts --description "A model that routes requests to Google packages and the browser, its metrics, then each request answered" >/dev/null
echo "amie template run google-assistant runs it; amie template edit google-assistant changes it"
