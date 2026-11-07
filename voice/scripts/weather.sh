#!/bin/bash
# The weather now, from wttr.in: $1 is a place, or nothing for where this machine is.
place="${1// /+}"
curl -s -m 10 --get --data-urlencode 'format=%l: %C, %t, feels like %f, wind %w, humidity %h' "https://wttr.in/$place"
