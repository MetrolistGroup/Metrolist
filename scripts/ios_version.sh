#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_version="$(awk -F= '$1 == "appVersion" { print $2; exit }' "$root_dir/gradle.properties")"
if [[ ! "$app_version" =~ ^([0-9]+\.[0-9]+\.[0-9]+)(-[A-Za-z0-9][A-Za-z0-9._-]*)?$ ]]; then
  echo "Error: appVersion must use MAJOR.MINOR.PATCH with an optional alphanumeric suffix: $app_version" >&2
  exit 1
fi
version="${BASH_REMATCH[1]}"
if [[ $# -eq 0 ]]; then
  printf '%s\n' "$version"
else
  printf '#define METROLIST_VERSION %s\n' "$version" > "$1"
fi
