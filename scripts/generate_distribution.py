#!/usr/bin/env python3
"""Render distribution recipes from downloaded, checksum-verified stable assets."""

import argparse
import base64
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "MetrolistGroup/Metrolist"
APP_ID = "com.metrolist.desktop"


def release_assets(version):
    return {
        "WINDOWS": f"metrolist-installer-windows-x64-{version}.exe",
        "MACOS_ARM64": f"metrolist-desktop-macos-arm64-{version}.dmg",
        "MACOS_X64": f"metrolist-desktop-macos-x64-{version}.dmg",
        "LINUX_X64": f"metrolist-desktop-linux-x64-{version}.AppImage",
        "LINUX_ARM64": f"metrolist-desktop-linux-arm64-{version}.AppImage",
    }


def read_checksums(path):
    checksums = {}
    for line in path.read_text().splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]([^/\\]+)", line)
        if not match or match[2] in checksums or match[2] in (".", ".."):
            raise ValueError(f"Invalid or duplicate SHA256SUMS entry: {line!r}")
        checksums[match[2]] = match[1].lower()
    return checksums


def verified_release(assets_dir, metadata):
    tag = metadata["tagName"]
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag):
        raise ValueError("Distribution requires a stable vMAJOR.MINOR.PATCH tag")
    if metadata["isDraft"] or metadata["isPrerelease"]:
        raise ValueError("Distribution requires a published stable release")
    release_url = f"https://github.com/{REPOSITORY}/releases/tag/{tag}"
    if metadata["url"] != release_url:
        raise ValueError(f"Expected release from {REPOSITORY}")
    published = datetime.fromisoformat(metadata["publishedAt"].replace("Z", "+00:00"))
    if published.tzinfo is None:
        raise ValueError("Release publication time must include a timezone")
    if not metadata["body"].strip():
        raise ValueError("Release notes must not be empty")
    checksums = read_checksums(assets_dir / "SHA256SUMS")
    substitutions = {"VERSION": tag[1:]}
    assets = {}
    names = [asset["name"] for asset in metadata["assets"]]
    if len(set(names)) != len(names):
        raise ValueError("Duplicate release asset names")
    for key, name in release_assets(tag[1:]).items():
        if name not in names or name not in checksums:
            raise ValueError(f"Missing release asset or checksum: {name}")
        path = assets_dir / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError(f"Missing or empty asset: {name}")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != checksums[name]:
            raise ValueError(f"Checksum mismatch: {name}")
        url = f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}"
        substitutions[f"{key}_URL"] = url
        substitutions[f"{key}_SHA256"] = digest
        assets[key] = {
            "name": name, "url": url, "sha256": digest,
            "hash": "sha256-" + base64.b64encode(bytes.fromhex(digest)).decode(),
        }
    return substitutions, assets, published.date().isoformat()


def render(path, substitutions):
    def replace(match):
        return substitutions[match[1]]
    return re.sub(r"@([A-Z0-9_]+)@", replace, path.read_text())


def metainfo(metadata, date, screenshots):
    tree = ET.parse(ROOT / f"flatpak/{APP_ID}.metainfo.xml")
    release = tree.find("./releases/release")
    release.set("version", metadata["tagName"][1:])
    release.set("date", date)
    description = ET.SubElement(release, "description")
    for paragraph in metadata["body"].strip().split("\n\n"):
        ET.SubElement(description, "p").text = paragraph.strip()
    if screenshots:
        node = ET.SubElement(tree.getroot(), "screenshots")
        for index, item in enumerate(screenshots):
            url = urlsplit(item["url"])
            if url.scheme != "https" or not url.netloc or url.username or url.password:
                raise ValueError("Screenshots require public HTTPS URLs without credentials")
            if not item["caption"].strip():
                raise ValueError("Screenshots require captions")
            shot = ET.SubElement(node, "screenshot", {"type": "default"} if index == 0 else {})
            ET.SubElement(shot, "caption").text = item["caption"]
            ET.SubElement(shot, "image", {"type": "source"}).text = item["url"]
    ET.indent(tree, space="  ")
    return ET.tostring(tree.getroot(), encoding="unicode", xml_declaration=True) + "\n"


def generate(assets_dir, metadata, output, screenshots):
    substitutions, assets, date = verified_release(assets_dir, metadata)
    appdata = metainfo(metadata, date, screenshots)
    if output.exists():
        raise ValueError(f"Output already exists: {output}. Choose an empty output path.")
    version = substitutions["VERSION"]
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        stage = Path(temporary) / "distribution"
        winget = stage / "winget/manifests/m/MetrolistGroup/Metrolist" / version
        winget.mkdir(parents=True)
        for source in sorted((ROOT / "packaging/winget").glob("*.yaml.in")):
            (winget / source.name.removesuffix(".in")).write_text(render(source, substitutions))
        cask = stage / "homebrew/Casks/metrolist.rb"
        cask.parent.mkdir(parents=True)
        cask.write_text(render(ROOT / "packaging/homebrew/metrolist.rb.in", substitutions))
        flatpak = stage / "flatpak"
        flatpak.mkdir()
        (flatpak / f"{APP_ID}.json").write_text(render(ROOT / f"packaging/flatpak/{APP_ID}.json.in", substitutions))
        for suffix in ("desktop", "png", "svg"):
            shutil.copyfile(ROOT / f"flatpak/{APP_ID}.{suffix}", flatpak / f"{APP_ID}.{suffix}")
        shutil.copyfile(ROOT / f"packaging/flatpak/{APP_ID}", flatpak / APP_ID)
        (flatpak / f"{APP_ID}.metainfo.xml").write_text(appdata)
        (flatpak / "SUBMISSION-NOTES.txt").write_text(
            "Offline release-artifact build, not approved for Flathub.\n"
            "Flathub requires a source build or an explicit exception.\n"
            + ("Desktop screenshots still required.\n" if not screenshots else "")
        )
        release = {
            "version": version, "releaseUrl": metadata["url"], "date": date,
            "linux": {system: assets[key] for system, key in (
                ("x86_64-linux", "LINUX_X64"), ("aarch64-linux", "LINUX_ARM64"),
            )},
        }
        nix = stage / "nix"
        (nix / "packaging/nix").mkdir(parents=True)
        shutil.copyfile(ROOT / "flake.nix", nix / "flake.nix")
        shutil.copyfile(ROOT / "packaging/nix/package.nix", nix / "packaging/nix/package.nix")
        (nix / "packaging/release.json").write_text(json.dumps(release, indent=2) + "\n")
        aur = stage / "aur"
        aur.mkdir()
        (aur / "release.json").write_text(json.dumps(release, indent=2) + "\n")
        shutil.copyfile(ROOT / "packaging/aur/README.md", aur / "README.md")
        (stage / "release.json").write_text(json.dumps(release, indent=2) + "\n")
        stage.rename(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--release", type=Path, required=True, help="gh release view --json metadata")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--screenshots", type=Path, default=ROOT / "packaging/flatpak/screenshots.json")
    args = parser.parse_args()
    try:
        generate(args.assets, json.loads(args.release.read_text()), args.output,
                 json.loads(args.screenshots.read_text()))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"Distribution generation failed: {error}\n")


if __name__ == "__main__":
    main()
