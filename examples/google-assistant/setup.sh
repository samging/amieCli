#!/usr/bin/env bash
# Saves the google-assistant template into the AMP folder. Its package jars come from GitHub the first time it opens them.
# Arguments go to amie, such as --amp ~/scratch/amp for another AMP. Then: amie template run google-assistant
set -euo pipefail
# AMIE names another amie, such as AMIE=./amie from the repository root; a relative path is resolved before the cd
AMIE="${AMIE:-amie}"
if [[ "$AMIE" == */* ]]; then AMIE="$(cd "$(dirname "$AMIE")" && pwd)/$(basename "$AMIE")"; fi
cd "$(dirname "$0")"

"$AMIE" "$@" template save google-assistant google-assistant.kts --description "A model that routes requests to Google packages and the browser, its metrics, then each request answered" >/dev/null
echo "amie template run google-assistant runs it; amie template edit google-assistant changes it"
