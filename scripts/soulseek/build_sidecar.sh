#!/usr/bin/env bash
# Freezes metrolist-slsk into one executable under desktopApp/src/desktopMain/resources/<os>/,
# where desktop packaging bundles it with the app. Needs uv.
# SOULSEEK_PYTHON selects the interpreter, and so the binary's architecture
# (for example cpython-3.13-macos-x86_64-none for an x64 build on Apple silicon).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux) os=linux ;;
  MINGW* | MSYS* | CYGWIN*) os=windows ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
out="$root/desktopApp/src/desktopMain/resources/$os"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

uv run --isolated --no-project --python "${SOULSEEK_PYTHON:-3.13}" \
  --with "pyinstaller==6.22.3" --with "$here" -- \
  pyinstaller --onefile --clean --noconfirm --log-level WARN \
  --name metrolist-slsk \
  --collect-submodules keyring.backends \
  --distpath "$out" --workpath "$work/build" --specpath "$work" \
  "$here/metrolist_slsk.py"

exe="$out/metrolist-slsk"
[[ "$os" == windows ]] && exe="$exe.exe"
"$exe" --help >/dev/null
echo "Built $exe"
