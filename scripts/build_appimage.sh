#!/usr/bin/env bash

set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"
gradle="${GRADLE:-$root_dir/gradlew}"
app_image="$root_dir/dist/Metrolist.AppImage"
tools_dir="$root_dir/tools"
appimagetool="$tools_dir/appimagetool.AppImage"
appimagetool_version="1.9.1"
case "$(uname -m)" in
    x86_64|amd64)
        appimagetool_asset="appimagetool-x86_64.AppImage"
        appimagetool_sha256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
        ;;
    aarch64|arm64)
        appimagetool_asset="appimagetool-aarch64.AppImage"
        appimagetool_sha256="f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158"
        ;;
    *)
        echo "Unsupported Linux architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

if [[ "${1:-}" != "--native-appdir" ]]; then
    rm -f "$app_image"
fi

if [[ -f "$appimagetool" ]] && ! printf '%s  %s\n' "$appimagetool_sha256" "$appimagetool" | sha256sum --check --status; then
    rm -f "$appimagetool"
fi

if [[ ! -f "$appimagetool" ]]; then
    echo "Downloading appimagetool..."
    mkdir -p "$tools_dir"
    curl --fail --location --silent --show-error \
        "https://github.com/AppImage/appimagetool/releases/download/$appimagetool_version/$appimagetool_asset" \
        --output "$appimagetool.tmp"
    printf '%s  %s\n' "$appimagetool_sha256" "$appimagetool.tmp" | sha256sum --check --status
    mv "$appimagetool.tmp" "$appimagetool"
    chmod +x "$appimagetool"
    echo "appimagetool downloaded successfully"
fi

printf '%s  %s\n' "$appimagetool_sha256" "$appimagetool" | sha256sum --check --status

chmod +x "$appimagetool"
if [[ "${1:-}" == "--native-appdir" ]]; then
    [[ "$#" == 3 ]] || { echo "Usage: --native-appdir APPDIR OUTPUT" >&2; exit 1; }
    test -x "$2/AppRun"
    env -u SOURCE_DATE_EPOCH "$appimagetool" --appimage-extract-and-run "$2" "$3"
    test -s "$3"
    chmod +x "$3"
    exit 0
fi

"$gradle" :desktopApp:createAppImageLocal --no-daemon --no-configuration-cache "$@"

test -s "$app_image" || { echo "AppImage build produced no output: $app_image" >&2; exit 1; }
chmod +x "$app_image"
