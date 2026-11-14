#!/usr/bin/env python3
"""Collect AGP's universal APK output under the name installed updaters recognize."""

import argparse
import json
import re
import shutil
from pathlib import Path

def collect(apk_dir: Path, destination: Path, version: str, variant: str) -> None:
    if variant not in {"debug", "release"} or not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9][A-Za-z0-9._-]*)?", version):
        raise ValueError("Invalid Android variant or version")
    metadata = json.loads((apk_dir / "output-metadata.json").read_text())
    if metadata["variantName"] != variant:
        raise ValueError("APK variant does not match the requested build")
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0]["filters"]:
        raise ValueError("Expected exactly one universal APK output")
    element = elements[0]
    if element["versionName"] != version:
        raise ValueError("APK version does not match the requested build")
    source = (apk_dir / element["outputFile"]).resolve()
    if source.parent != apk_dir.resolve() or source.suffix != ".apk" or not source.is_file():
        raise ValueError("Invalid or missing APK output")
    destination.mkdir(parents=True, exist_ok=True)
    name = f"metrolist-android-{version}-debug.apk" if variant == "debug" else "Metrolist.apk"
    shutil.copyfile(source, destination / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk_dir", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("version")
    parser.add_argument("variant", choices=("debug", "release"))
    args = parser.parse_args()
    collect(args.apk_dir, args.destination, args.version, args.variant)
