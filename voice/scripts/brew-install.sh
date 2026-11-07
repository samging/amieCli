#!/bin/bash
# Installs the Homebrew formula $1; only a name, so nothing heard wrongly can become a command
[[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9@._+-]*$ ]] || { echo "'$1' is no formula name" >&2; exit 1; }
HOMEBREW_NO_AUTO_UPDATE=1 brew install "$1"
