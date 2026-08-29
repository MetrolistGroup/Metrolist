#!/usr/bin/env bash
# parse_changelog.sh — Extract the changelog entry for a specific version
#
# Reads changelog.md (or a custom file) and outputs the content block
# associated with the given version, as delimited by ---vX.Y.Z separators.
#
# Usage:
#   ./parse_changelog.sh <version> [changelog_file]
#
# Examples:
#   ./parse_changelog.sh 13.2.1
#   ./parse_changelog.sh v13.2.1 changelog.md
#   ./parse_changelog.sh 13.2.1 /path/to/changelog.md
#
# Exit codes:
#   0 — Version found; content written to stdout
#   1 — Error (missing args, file not found, version not found)

set -euo pipefail

VERSION="${1:-}"
CHANGELOG_FILE="${2:-changelog.md}"

if [ -z "$VERSION" ]; then
    echo "Error: version argument required" >&2
    echo "Usage: $0 <version> [changelog_file]" >&2
    echo "Example: $0 13.2.1" >&2
    exit 1
fi

if [ ! -f "$CHANGELOG_FILE" ]; then
    echo "Error: changelog file not found: '$CHANGELOG_FILE'" >&2
    exit 1
fi

VERSION="${VERSION#v}"

if ! grep -q "^---v${VERSION}$" "$CHANGELOG_FILE"; then
    echo "Error: version '$VERSION' not found in '$CHANGELOG_FILE'" >&2
    exit 1
fi

awk -v ver="$VERSION" '
    /^---v/ {
        if (found) exit
        if ($0 == "---v" ver) { found=1; next }
        next
    }
    found { print }
' "$CHANGELOG_FILE"
