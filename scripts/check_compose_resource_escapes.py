#!/usr/bin/env python3
"""Reject malformed string XML and escapes that Compose renders literally."""
import argparse
import os
import re
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree


RESOURCE_DIRECTORY = "shared/src/commonMain/composeResources"


def resource_errors(content, path):
    try:
        root = ElementTree.fromstring(content)
    except (ElementTree.ParseError, LookupError, ValueError) as error:
        yield f"{path}: invalid XML: {error}"
        return
    for element in root.iter():
        if element.tag not in ("string", "item"):
            continue
        text = "".join(element.itertext())
        # A percent sign written before a placeholder, as in Turkish "%%1$d", is literal text in Compose.
        if "\\'" in text or '\\"' in text or re.search(r"%%(?!\d+\$[ds])", text):
            yield f"{path}: {element.get('name', element.get('quantity'))}: Android-only escape"


def invalid_escapes(resources):
    files = sorted(resources.glob("values*/strings.xml"))
    if not files:
        raise FileNotFoundError(f"No Compose string resources in {resources}")
    for path in files:
        yield from resource_errors(path.read_bytes(), path)


def staged_resource_errors():
    paths = subprocess.check_output([
        "git", "diff", "--cached", "--name-only", "--no-relative", "--diff-filter=ACMRT", "-z",
        "--", f":(top,glob){RESOURCE_DIRECTORY}/values*/strings.xml",
    ])
    for name in paths.split(b"\0"):
        if name:
            path = os.fsdecode(name)
            content = subprocess.check_output(["git", "cat-file", "blob", f":{path}"])
            yield from resource_errors(content, path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="Check only changed string resources in the Git index")
    args = parser.parse_args()
    resources = Path(__file__).resolve().parents[1] / RESOURCE_DIRECTORY
    try:
        errors = list(staged_resource_errors() if args.staged else invalid_escapes(resources))
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"Cannot check Compose resources: {error}", file=sys.stderr)
        sys.exit(1)
    for error in errors:
        print(error, file=sys.stderr)
    sys.exit(bool(errors))
