#!/usr/bin/env bash

set -euo pipefail

VERSION="${1:-}"
CHANGELOG_FILE="${2:-docs/changelog.md}"

if [ -z "$VERSION" ]; then
    echo "Error: version argument required" >&2
    echo "Usage: $0 <version> [changelog_file]" >&2
    echo "Example: $0 14.0.0" >&2
    exit 1
fi

if [ ! -f "$CHANGELOG_FILE" ]; then
    echo "Error: changelog file not found: '$CHANGELOG_FILE'" >&2
    exit 1
fi

VERSION="${VERSION#v}"

if ! grep -Fxq -- "---v${VERSION}" "$CHANGELOG_FILE"; then
    echo "Error: version '$VERSION' not found in '$CHANGELOG_FILE'" >&2
    exit 1
fi

ENTRY="$(awk -v ver="$VERSION" '
    /^---v/ {
        if (found) exit
        if ($0 == "---v" ver) { found=1; next }
        next
    }
    found { print }
' "$CHANGELOG_FILE")"

if ! grep -q '[^[:space:]]' <<< "$ENTRY"; then
    echo "Error: version '$VERSION' has an empty changelog entry" >&2
    exit 1
fi

if grep -qiE '(^|[^[:alnum:]])TODO([^[:alnum:]]|$)' <<< "$ENTRY"; then
    echo "Error: version '$VERSION' still contains a TODO marker" >&2
    exit 1
fi

printf '%s\n' "$ENTRY"
