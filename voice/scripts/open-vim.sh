#!/bin/bash
# Opens the first file with $1 in its name in vim, in a new Terminal window
file="$("$(dirname "$0")/find-file.sh" "$1" | head -1)"
[ -n "$file" ] || { echo "no file with $1 in its name" >&2; exit 1; }
quoted="$(printf %q "$file")"
osascript -e "tell application \"Terminal\" to do script \"vim ${quoted//\"/\\\"}\"" -e 'tell application "Terminal" to activate' > /dev/null
echo "$file"
