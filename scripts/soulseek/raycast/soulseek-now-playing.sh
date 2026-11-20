#!/bin/bash

# @raycast.schemaVersion 1
# @raycast.title Download Now Playing from Soulseek
# @raycast.mode compact
# @raycast.packageName Metrolist
# @raycast.icon 🎵
# @raycast.argument1 { "type": "dropdown", "placeholder": "Quality", "data": [{ "title": "Highest (lossless first)", "value": "highest" }, { "title": "High (320 kbps first)", "value": "high" }] }
# @raycast.description Finds the song in macOS Now Playing on Soulseek and downloads the best match.

export PATH="$HOME/.local/bin:/opt/homebrew/bin:$PATH"
if path=$(metrolist-slsk --now-playing --quality "${1:-highest}" 2>/tmp/metrolist-slsk.log); then
  echo "Saved $(basename "$path")"
else
  tail -n 1 /tmp/metrolist-slsk.log
  exit 1
fi
