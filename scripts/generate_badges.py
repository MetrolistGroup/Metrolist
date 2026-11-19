#!/usr/bin/env python3
"""Generate the README download badges in assets/badges/platform/.

Text is converted to outlines so the badges render identically without web fonts.
Needs fontTools (`pip install fonttools`); the pinned IBM Plex fonts are downloaded into build/.
"""

import hashlib
import re
import urllib.request
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parent.parent
ICONS = ROOT / "assets/badges/icons"
OUT = ROOT / "assets/badges/platform"
FONT_DIR = ROOT / "build/badge-fonts"
FONTS_COMMIT = "7af0d90b4d532ffa4b041937b560c6df4e7b1e95"
FONTS = {
    "sans": ("ofl/ibmplexsans/IBMPlexSans%5Bwdth,wght%5D.ttf", "3b031aa4216174205bd8471f88a49b91f093169e9e87bd5262242bc5967fe2e3"),
    "mono": ("ofl/ibmplexmono/IBMPlexMono-Medium.ttf", "a9b4c49bb299e05b5f6c481e7fb5e78943d2793249a0c8874ab574a2d1ea6755"),
}

W, H = 264, 56
BG, BORDER, INK, MUTED = "#121212", "#3A3A3A", "#F5F5F5", "#A3A3A3"
ANDROID_CREDIT = "Android robot artwork by Google, used under Creative Commons Attribution 3.0."

# file, icon, recolor icon to INK, caption, title, tag, accessible title, extra credit
BADGES = [
    ("android-stable", "android", True, "Stable release", "Android", "APK", "Download the stable Android APK", ANDROID_CREDIT),
    ("android-nightly", "android", True, "Nightly build", "Android", "APK", "Download the nightly Android APK", ANDROID_CREDIT),
    ("ios-ipa", "apple", True, "Sideload", "iOS & iPadOS", "IPA", "Download the iOS IPA", ""),
    ("windows-installer", "windows", True, "Installer", "Windows x64", "EXE", "Download the Windows x64 installer", ""),
    ("windows-portable", "windows", True, "Portable", "Windows x64", "ZIP", "Download the portable Windows x64 ZIP", ""),
    ("windows-arm64", "windows", True, "Portable", "Windows ARM64", "ZIP", "Download the portable Windows ARM64 ZIP", ""),
    ("macos-apple-silicon", "apple", True, "Apple silicon", "macOS", "DMG", "Download the macOS DMG for Apple silicon", ""),
    ("macos-intel", "intel", True, "Intel", "macOS", "DMG", "Download the macOS DMG for Intel", ""),
    ("linux-appimage", "appimage", True, "x86_64 and ARM64", "Linux", "AppImage", "Download the Linux AppImage", ""),
    ("store-obtainium", "obtainium", False, "Add to", "Obtainium", "", "Add Metrolist to Obtainium", ""),
    ("store-izzyondroid", "izzyondroid", False, "F-Droid repo", "IzzyOnDroid", "", "Get Metrolist from IzzyOnDroid",
     "IzzyOnDroid logo by IzzySoft and Wolfshappen, used unaltered."),
    ("store-openapk", "openapk", False, "Get it on", "OpenAPK", "", "Get Metrolist on OpenAPK", ""),
]


def font(key):
    path = FONT_DIR / Path(FONTS[key][0]).name.replace("%5B", "[").replace("%5D", "]")
    if not path.exists():
        FONT_DIR.mkdir(parents=True, exist_ok=True)
        url = f"https://raw.githubusercontent.com/google/fonts/{FONTS_COMMIT}/{FONTS[key][0]}"
        path.write_bytes(urllib.request.urlopen(url).read())
    if hashlib.sha256(path.read_bytes()).hexdigest() != FONTS[key][1]:
        path.unlink()
        raise SystemExit(f"Checksum mismatch for {path.name}; run again to re-download")
    f = TTFont(path)
    if "fvar" in f:
        f = instantiateVariableFont(f, {"wght": 600, "wdth": 100})
    return f


def text_path(f, text, size, x, baseline, spacing=0.0, anchor="start"):
    """Outline `text` as one SVG path. shortcut: ignores kerning, fine for short labels."""
    scale = size / f["head"].unitsPerEm
    cmap, glyphs, hmtx = f.getBestCmap(), f.getGlyphSet(), f["hmtx"]
    names = [cmap[ord(c)] for c in text]
    width = sum(hmtx[n][0] * scale for n in names) + spacing * (len(names) - 1)
    if anchor == "end":
        x -= width
    pen, cursor = SVGPathPen(glyphs), x
    for n in names:
        glyphs[n].draw(TransformPen(pen, (scale, 0, 0, -scale, cursor, baseline)))
        cursor += hmtx[n][0] * scale + spacing
    return pen.getCommands(), x, width


def icon(name, mono, x, y, size):
    s = (ICONS / f"{name}.svg").read_text()
    open_tag = re.match(r"<svg[^>]*>", s).group(0)
    inner = s[len(open_tag):s.rindex("</svg>")]
    attrs = re.sub(r'\s*xmlns(:\w+)?="[^"]*"', "", open_tag[4:-1])
    if mono:
        inner = re.sub(r'fill="#[0-9A-Fa-f]{3,6}"', f'fill="{INK}"', inner)
        attrs = re.sub(r'fill="#[0-9A-Fa-f]{3,6}"', f'fill="{INK}"', attrs)
    return f'<svg{attrs} x="{x}" y="{y}" width="{size}" height="{size}" preserveAspectRatio="xMidYMid meet">{inner}</svg>'


def badge(sans, mono, spec):
    _, icon_name, recolor, caption, title, tag, label, credit = spec
    parts = [
        f'<rect width="{W}" height="{H}" fill="{BG}"/>',
        f'<rect x=".5" y=".5" width="{W - 1}" height="{H - 1}" fill="none" stroke="{BORDER}"/>',
        icon(icon_name, recolor, 16, 14, 28),
    ]
    d, _, _ = text_path(mono, caption.upper(), 9.5, 56, 23, spacing=0.6)
    parts.append(f'<path d="{d}" fill="{MUTED}"/>')
    d, _, title_w = text_path(sans, title, 18, 56, 42)
    parts.append(f'<path d="{d}" fill="{INK}"/>')
    if tag:
        d, tag_x, _ = text_path(mono, tag, 12, W - 16, 32.5, anchor="end")
        if 56 + title_w > tag_x - 25:
            raise SystemExit(f"{spec[0]}: title overlaps tag")
        parts.append(f'<path d="M{tag_x - 12:.1f} 16V40" stroke="{BORDER}"/>')
        parts.append(f'<path d="{d}" fill="{INK}"/>')
    desc = f"{caption} / {title}{' / ' + tag if tag else ''}. Metrolist download badge. {credit}".strip()
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{W}" height="{H}" '
        f'viewBox="0 0 {W} {H}" role="img" aria-labelledby="t d"><title id="t">{label}</title>'
        f'<desc id="d">{desc.replace("&", "&amp;")}</desc><g aria-hidden="true">{"".join(parts)}</g></svg>\n'
    )


def main():
    sans, mono = font("sans"), font("mono")
    for spec in BADGES:
        (OUT / f"{spec[0]}.svg").write_text(badge(sans, mono, spec))
    print(f"Wrote {len(BADGES)} badges to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
