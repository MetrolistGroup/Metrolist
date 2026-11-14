#!/usr/bin/env bash
set -euo pipefail

recipe_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

mkdir -p "$test_dir/project/aur" "$test_dir/sources/metrolist-kmp" "$test_dir/package"
cp "$recipe_dir/PKGBUILD" "$test_dir/project/aur/"
touch "$test_dir/project/settings.gradle.kts" "$test_dir/project/gradlew"
chmod +x "$test_dir/project/gradlew"

cat > "$test_dir/gradle runner" <<'GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$PWD" "$@" > "$RECIPE_BUILD_LOG"
exit "${RECIPE_BUILD_EXIT:-0}"
GRADLE
chmod +x "$test_dir/gradle runner"
export GRADLE="$test_dir/gradle runner"
export RECIPE_BUILD_LOG="$test_dir/build.log"

startdir="$test_dir/project/aur"
srcdir="$test_dir/sources"
pkgdir="$test_dir/package"

# shellcheck source=aur/PKGBUILD
source "$test_dir/project/aur/PKGBUILD"

[[ "$(_source_dir)" == "$test_dir/project/aur/.." ]]
[[ "${source[*]}" == 'metrolist.desktop' ]]
build
printf '%s\n' "$test_dir/project" --no-daemon :desktopApp:createReleaseDistributable > "$test_dir/expected.log"
cmp "$test_dir/expected.log" "$RECIPE_BUILD_LOG"
if RECIPE_BUILD_EXIT=7 build; then
  printf 'The recipe swallowed a failed Gradle build\n' >&2
  exit 1
fi

appdir="$test_dir/project/desktopApp/build/compose/binaries/main-release/app/Metrolist"
mkdir -p "$appdir/bin" "$test_dir/project/desktopApp/src/desktopMain/resources"
printf '#!/bin/sh\nexit 0\n' > "$appdir/bin/Metrolist"
chmod +x "$appdir/bin/Metrolist"
printf 'Test license\n' > "$test_dir/project/LICENSE"
printf 'Test icon\n' > "$test_dir/project/desktopApp/src/desktopMain/resources/logo.png"
cp "$recipe_dir/metrolist.desktop" "$srcdir/"
package
[[ -x "$pkgdir/opt/metrolist/bin/Metrolist" ]]
[[ "$(readlink "$pkgdir/usr/bin/metrolist")" == /opt/metrolist/bin/Metrolist ]]
cmp "$test_dir/project/LICENSE" "$pkgdir/usr/share/licenses/metrolist-git/LICENSE"
cmp "$srcdir/metrolist.desktop" "$pkgdir/usr/share/applications/metrolist.desktop"
cmp "$test_dir/project/desktopApp/src/desktopMain/resources/logo.png" "$pkgdir/usr/share/icons/hicolor/512x512/apps/metrolist.png"

rm "$test_dir/project/settings.gradle.kts"
# shellcheck source=aur/PKGBUILD
source "$test_dir/project/aur/PKGBUILD"
[[ "$(_source_dir)" == "$srcdir/metrolist-kmp" ]]
[[ "${source[0]}" == metrolist-kmp::git+https://github.com/MetrolistGroup/Metrolist.git#branch=main ]]
printf 'Arch recipe checks passed\n'
