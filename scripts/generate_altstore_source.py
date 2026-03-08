#!/usr/bin/env python3
"""Write an AltStore/SideStore source for a stable release IPA.

The release publishes it as `metrolist-altstore.json`, so the stable source URL is
https://github.com/MetrolistGroup/Metrolist/releases/latest/download/metrolist-altstore.json.
"""

import argparse
import datetime
import json
from pathlib import Path

BUNDLE_ID = "com.metrolist.MetrolistApp"
MIN_OS_VERSION = "17.0"


def source(repository: str, version: str, ipa: Path, date: str) -> dict:
    raw = f"https://raw.githubusercontent.com/{repository}/v{version}"
    return {
        "name": "Metrolist",
        "identifier": "com.metrolist.source",
        "website": f"https://github.com/{repository}",
        "apps": [
            {
                "name": "Metrolist",
                "bundleIdentifier": BUNDLE_ID,
                "developerName": "MetrolistGroup",
                "localizedDescription": "A Material Design YouTube Music client.",
                "iconURL": f"{raw}/fastlane/metadata/android/en-US/images/icon.png",
                "versions": [
                    {
                        "version": version,
                        "date": date,
                        "downloadURL": f"https://github.com/{repository}/releases/download/v{version}/{ipa.name}",
                        "size": ipa.stat().st_size,
                        "minOSVersion": MIN_OS_VERSION,
                    }
                ],
            }
        ],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("version")
    parser.add_argument("ipa", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    args.output.write_text(json.dumps(source(args.repository, args.version, args.ipa, date), indent=2) + "\n")
