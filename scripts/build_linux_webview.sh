#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:?Pass the native resource output directory}"
case "$(uname -m)" in
    aarch64|arm64) target="aarch64-unknown-linux-gnu"; resource="linux-aarch64" ;;
    x86_64|amd64) target="x86_64-unknown-linux-gnu"; resource="linux-x86-64" ;;
    *) echo "Unsupported Linux WebView architecture" >&2; exit 1 ;;
esac

revision="58314a9dc161a9892a5ecc2b2c0b3a593217c258"
sha256="326c138a70ff66e62183198d625337a90970494f34df6b97281b6af5a1db39cd"
work="$root_dir/desktopApp/build/linux-webview"
mkdir -p "$work"
curl --fail --location --silent --show-error --retry 3 \
    "https://codeload.github.com/NucleusFramework/ComposeNativeWebview/tar.gz/$revision" \
    --output "$work/source.tar.gz"
printf '%s  %s\n' "$sha256" "$work/source.tar.gz" | sha256sum --check --status
rm -rf "$work/source"
mkdir -p "$work/source"
tar -xzf "$work/source.tar.gz" -C "$work/source" --strip-components=1
CARGO_TARGET_DIR="$work/target" cargo +1.99.1 build \
    --manifest-path "$work/source/wrywebview/Cargo.toml" \
    --locked --release --lib --target "$target" --jobs "${CARGO_BUILD_JOBS:-2}"
mkdir -p "$output_dir/$resource"
cp "$work/target/$target/release/libcomposewebview_wry.so" "$output_dir/$resource/"
cp "$work/source/LICENSE" "$output_dir/composewebview-LICENSE"
