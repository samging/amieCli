#!/bin/bash
# Opens the first file with $1 in its name in the default text editor
file="$("$(dirname "$0")/find-file.sh" "$1" | head -1)"
[ -n "$file" ] || { echo "no file with $1 in its name" >&2; exit 1; }
open -t "$file"
echo "$file"
