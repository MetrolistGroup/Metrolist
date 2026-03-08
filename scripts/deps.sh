#!/bin/bash
set -eu
export LC_ALL=C

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

case "$(uname -s)" in
    Linux*)
        commandline_tools_archive="commandlinetools-linux-16111833_latest.zip"
        ;;
    Darwin*)
        case "$(uname -m)" in
            arm64|aarch64) commandline_tools_archive="commandlinetools-mac_arm64-16111833_latest.zip" ;;
            x86_64|amd64)  commandline_tools_archive="commandlinetools-mac_x86_64-16111833_latest.zip" ;;
            *) printf '%s\n' "Unsupported macOS architecture: $(uname -m)" >&2; exit 1 ;;
        esac
        ;;
    MINGW*|MSYS*|CYGWIN*) printf '%s\n' "On Windows, install Temurin 25 and Android SDK command-line tools manually; deps.sh supports Linux and macOS." >&2; exit 1 ;;
    *)                     printf '%s\n' "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

if ! command -v java &>/dev/null || ! java -version 2>&1 | head -n1 | grep -qE 'version "25[."]'; then
    if [ ! -f "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
        printf '%s\n' "Installing SDKMAN..."
        curl -s "https://get.sdkman.io" | bash
    fi
    export SDKMAN_CANDIDATES_API="${SDKMAN_CANDIDATES_API:-https://api.sdkman.io/2}"
    export SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
    # shellcheck source=/dev/null
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    sdk install java 25.0.5-tem
    sdk use java 25.0.5-tem
    sdk default java 25.0.5-tem
fi

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    printf '%s\n' "Installing Android SDK to $ANDROID_HOME..."
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    TMP="$(mktemp -d)"
    curl -fsSL -o "$TMP/cmdline-tools.zip" \
        "https://dl.google.com/android/repository/$commandline_tools_archive"
    unzip -qo "$TMP/cmdline-tools.zip" -d "$TMP"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$TMP/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$TMP"

    if [ ! -f local.properties ] || ! grep -q '^sdk\.dir=' local.properties; then
        printf '\nsdk.dir=%s\n' "$ANDROID_HOME" >> local.properties
    else
        sed -i.bak "s|^sdk\.dir=.*|sdk.dir=$ANDROID_HOME|" local.properties && rm -f local.properties.bak
    fi
fi

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install \
    "platform-tools" \
    "build-tools;36.0.0" \
    "platforms;android-35" \
    "platforms;android-37"

printf '%s\n' "Dependencies installed."
