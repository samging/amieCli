#!/bin/bash
# Closes every Terminal window, and what runs in them
pgrep -x Terminal > /dev/null || exit 0
osascript -e 'tell application "Terminal" to close every window saving no'
