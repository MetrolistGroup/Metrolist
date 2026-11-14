#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir/aur"

tmp_dir="$repo_dir/build/aur-tmp"
mkdir -p "$tmp_dir"
export TMPDIR="$tmp_dir"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=$tmp_dir"

if command -v mise >/dev/null 2>&1; then
  if jdk21="$(mise where java@temurin-21 2>/dev/null)"; then
    :
  else
    jdk21=''
  fi
  if jdk25="$(mise where java@temurin-25 2>/dev/null)"; then
    :
  else
    jdk25=''
  fi
  if [[ -n "$jdk21" && -n "$jdk25" ]]; then
    export GRADLE_OPTS="${GRADLE_OPTS:+$GRADLE_OPTS }-Dorg.gradle.java.installations.paths=$jdk21,$jdk25"
  fi
fi

sudo pacman -S --needed gtk3 webkit2gtk-4.1
makepkg --nodeps --install "$@"
