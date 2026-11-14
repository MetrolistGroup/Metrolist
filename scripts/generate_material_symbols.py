#!/usr/bin/env python3
"""Refresh shared icon paths from a pinned Google Material Symbols SVG revision.

Run `python3 scripts/generate_material_symbols.py regen` to refresh existing
entries, or `verify` to check them without writing. Both include mirrored icons
and song-status badges. Use `--revision <commit-sha>` to check a newer revision,
then update UPSTREAM_REVISION and record that SHA in the refresh commit.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = ROOT / "shared/src/commonMain/kotlin/com/metrolist/shared/ui/icons"
UPSTREAM_REVISION = "10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7"
ICON_FILES = (
    "MetrolistIconsOutlined.kt",
    "MetrolistIconsFilled.kt",
    "MetrolistIconsRounded.kt",
    "MetrolistIcons.kt",
    "SongStatusIcons.kt",
)
ALIASES = {
    "Clear": "close",
    "FavoriteBorder": "favorite",
    "Movie": "smart_display",
    "NewReleases": "release_alert",
    "NotificationsNone": "notifications",
    "PeopleOutline": "group",
    "PictureInPicture": "picture_in_picture_alt",
    "Explicit": "explicit",
    "Saved": "library_music",
    "Liked": "favorite",
    "Downloaded": "download_for_offline",
}
# These Rounded entries already use solid glyphs, despite the family name.
ROUNDED_FILLED = {"Favorite", "Lock", "LockOpen", "Mic", "Pause", "PlayArrow", "SkipNext", "SkipPrevious"}
ENTRY_RE = re.compile(r'name = "([^"]+)",\s*pathData = "([^"]+)"')
TASKBAR_ICONS = {"previous": "skip_previous", "play": "play_arrow", "pause": "pause", "next": "skip_next"}


def symbol_for(name: str, fill_override: int | None = None) -> tuple[str, str, int]:
    style, prop = name.split(".")[-2:]
    glyph = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", prop)
    glyph = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "_", glyph)
    glyph = re.sub(r"(?<=[a-z])(?=[0-9])", "_", glyph).lower()
    glyph = ALIASES.get(prop, glyph)
    family = "rounded" if style == "Rounded" else "outlined"
    fill = int(style == "Filled" or (style == "Rounded" and prop in ROUNDED_FILLED))
    if style == "SongStatus":
        fill = int(prop != "Explicit")
    if prop == "FavoriteBorder":
        fill = 0
    return glyph, family, fill if fill_override is None else fill_override


def svg_path(svg: bytes) -> str:
    root = ET.fromstring(svg)
    paths = root.findall("{http://www.w3.org/2000/svg}path")
    if not paths or len(paths) != len(root) or any("transform" in node.attrib for node in (root, *paths)):
        raise ValueError("Expected SVG paths without transforms")
    data = " ".join(path.attrib["d"] for path in paths)
    if root.get("viewBox") == "0 -960 960 960":
        return data
    # Google's auto_awesome and push_pin SVGs still use a 24-unit grid.
    if root.get("viewBox") in (None, "0 0 24 24") and root.get("width") == root.get("height") == "24":
        from fontTools.pens.svgPathPen import SVGPathPen
        from fontTools.pens.transformPen import TransformPen
        from fontTools.svgLib.path import parse_path

        pen = SVGPathPen(None, ntos=lambda value: f"{value:.6f}".rstrip("0").rstrip("."))
        parse_path(data, TransformPen(pen, (40, 0, 0, 40, 0, -960)))
        return pen.getCommands()
    raise ValueError(f"Unexpected SVG viewport: {root.attrib}")


@lru_cache(maxsize=None)
def fetch_path(revision: str, glyph: str, family: str, fill: int) -> str:
    filename = f"{glyph}{'_fill1' if fill else ''}_24px.svg"
    relative = f"symbols/web/{glyph}/materialsymbols{family}/{filename}"
    cache = Path(tempfile.gettempdir()) / "metrolist-material-symbols" / revision / relative
    if cache.exists():
        return svg_path(cache.read_bytes())
    request = urllib.request.Request(
        f"https://raw.githubusercontent.com/google/material-design-icons/{revision}/{relative}",
        headers={"User-Agent": "metrolist-icon-generator"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        svg = response.read()
    data = svg_path(svg)
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_bytes(svg)
    return data


def refresh(revision: str, verify: bool, styles: list[str] | None = None, fill: int | None = None) -> int:
    sources = {ICON_DIR / filename: (ICON_DIR / filename).read_text() for filename in ICON_FILES}
    names = [match[0] for source in sources.values() for match in ENTRY_RE.findall(source)]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate icon names")
    names = {name for name in names if styles is None or name.split(".")[-2].lower() in styles}
    symbols = sorted({symbol_for(name, fill) for name in names})
    with ThreadPoolExecutor(max_workers=8) as pool:
        paths = dict(zip(symbols, pool.map(lambda symbol: fetch_path(revision, *symbol), symbols)))
    changed = 0
    for path, source in sources.items():
        def replace(match: re.Match[str]) -> str:
            nonlocal changed
            name, old = match.groups()
            if name not in names:
                return match[0]
            new = paths[symbol_for(name, fill)]
            if old != new:
                changed += 1
                if verify:
                    print(f"Mismatch: {name}")
            start, end = match.span(2)
            return match[0][:start - match.start()] + new + match[0][end - match.start():]

        updated = ENTRY_RE.sub(replace, source)
        if not verify and source != updated:
            path.write_text(updated)
    print(f"{len(names)} shared icons checked; {changed} {'mismatched' if verify else 'paths refreshed'} at {revision}")
    return int(verify and changed != 0)


def add_icons(revision: str, style: str, names: list[str], fill: int | None = None) -> None:
    path = ICON_DIR / f"MetrolistIcons{style.capitalize()}.kt"
    source = path.read_text()
    entries = {}
    for prop in names:
        name = f"{style.capitalize()}.{prop}"
        entries[name] = fetch_path(revision, *symbol_for(name, fill))
    source = ENTRY_RE.sub(
        lambda match: match[0].replace(match[2], entries.get(match[1], match[2])), source,
    )
    existing = {name for name, _ in ENTRY_RE.findall(source)}
    for name, data in entries.items():
        if name not in existing:
            entry = (
                f"    val {name.split('.')[-1]}: ImageVector by lazy {{\n"
                "        materialSymbol(\n"
                f'            name = "{name}",\n'
                f'            pathData = "{data}",\n'
                "        )\n    }\n"
            )
            source = source.rstrip().removesuffix("}").rstrip() + "\n\n" + entry + "}\n"
    path.write_text(source)


def refresh_taskbar(revision: str) -> None:
    from PIL import Image

    with tempfile.TemporaryDirectory(prefix="metrolist-taskbar-") as directory:
        for filename, glyph in TASKBAR_ICONS.items():
            solid = fetch_path(revision, glyph, "outlined", 1)
            outline = fetch_path(revision, glyph, "outlined", 0)
            svg = (
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960">'
                f'<path fill="white" d="{solid}"/><path d="{outline}"/></svg>'
            )
            png = Path(directory) / f"{filename}.png"
            subprocess.run(["rsvg-convert", "-w", "75", "-h", "75", "-o", str(png)], input=svg.encode(), check=True)
            with Image.open(png) as image:
                image.save(ROOT / f"desktopApp/src/jvmMain/resources/{filename}.ico", sizes=[(75, 75)])
    print("4 Windows taskbar glyphs refreshed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("regen", "verify", "add", "taskbar"))
    parser.add_argument("names", nargs="*")
    parser.add_argument("--style", choices=("filled", "outlined", "rounded"), nargs="+")
    parser.add_argument("--fill", type=int, choices=(0, 1))
    parser.add_argument("--revision", default=UPSTREAM_REVISION)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
        parser.error("--revision must be a full commit SHA")
    if args.command == "add":
        if not args.names or any(not re.fullmatch(r"[A-Z]\w*", name) for name in args.names):
            parser.error("add needs Kotlin icon property names")
        styles = args.style or ["filled"]
        if len(styles) != 1:
            parser.error("add needs one --style")
        add_icons(args.revision, styles[0], args.names, args.fill)
        return 0
    if args.command == "taskbar":
        refresh_taskbar(args.revision)
        return 0
    return refresh(args.revision, verify=args.command == "verify", styles=args.style, fill=args.fill)


if __name__ == "__main__":
    raise SystemExit(main())
