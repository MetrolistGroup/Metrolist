#!/usr/bin/env python3
"""
Generate installer icon files (logo.png, logo.ico, logo.icns) from the
raw white-on-transparent logo, composited onto a coloured circle background
that matches the app's default dynamic icon (DefaultThemeColor = #000000).

Usage:
  python3 scripts/gen_icons.py

Inputs  (from desktopApp/src/desktopMain/resources/):
  logo.svg  — rendered via cairosvg when available (best quality)
  logo.png  — fallback; must be the raw white-on-transparent version from git

Outputs (written to the same resources directory):
  logo.png  — 512×512 coloured circle + white M  (Linux .deb / .rpm / AppImage)
  logo.ico  — multi-size ICO                      (Windows .msi)
  logo.icns — macOS icon bundle via iconutil       (macOS .dmg, macOS only)
"""

import os
import sys
import platform
import subprocess
import tempfile
from pathlib import Path
from io import BytesIO

REPO_ROOT = Path(__file__).parent.parent
RESOURCES = REPO_ROOT / "desktopApp" / "src" / "desktopMain" / "resources"

ICON_COLOR = (0x00, 0x00, 0x00, 255)

ICO_SIZES = [16, 32, 48, 64, 128, 256]

# Cache the source logo before generated icons overwrite it.
_original_png_bytes: bytes = (RESOURCES / "logo.png").read_bytes()


def render_svg_to_rgba(size: int) -> "Image.Image":
    """Render logo.svg to a transparent RGBA image at the given size."""
    from PIL import Image

    svg_path = RESOURCES / "logo.svg"

    try:
        import cairosvg  # type: ignore
        png_bytes = cairosvg.svg2png(
            url=str(svg_path),
            output_width=size,
            output_height=size,
        )
        return Image.open(BytesIO(png_bytes)).convert("RGBA")
    except Exception:
        pass

    img = Image.open(BytesIO(_original_png_bytes)).convert("RGBA")
    if img.size != (size, size):
        img = img.resize((size, size), Image.LANCZOS)
    return img


def make_icon(canvas: int) -> "Image.Image":
    """Coloured circle + white logo at 55 % scale, on a transparent canvas."""
    from PIL import Image, ImageDraw

    bg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(bg)
    draw.ellipse([0, 0, canvas - 1, canvas - 1], fill=ICON_COLOR)

    logo_px = int(canvas * 0.55)
    logo = render_svg_to_rgba(logo_px)

    ox = (canvas - logo_px) // 2
    oy = (canvas - logo_px) // 2
    bg.paste(logo, (ox, oy), logo)
    return bg



def write_linux_png():
    img = make_icon(512)
    out = RESOURCES / "logo.png"
    img.save(str(out), "PNG")
    print(f"  logo.png  -> {out}")


def write_windows_ico():
    base = make_icon(256)
    out = RESOURCES / "logo.ico"
    base.save(
        str(out),
        format="ICO",
        sizes=[(s, s) for s in ICO_SIZES],
    )
    print(f"  logo.ico  -> {out}")


def write_macos_icns():
    """Requires macOS + iconutil (ships with Xcode Command Line Tools)."""
    with tempfile.TemporaryDirectory() as td:
        iconset = Path(td) / "icon.iconset"
        iconset.mkdir()

        # macOS standard iconset requires both 1× and 2× variants
        for base_size in [16, 32, 128, 256, 512]:
            make_icon(base_size).save(
                str(iconset / f"icon_{base_size}x{base_size}.png")
            )
            make_icon(base_size * 2).save(
                str(iconset / f"icon_{base_size}x{base_size}@2x.png")
            )

        out = RESOURCES / "logo.icns"
        result = subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(out)],
            capture_output=True,
        )
        if result.returncode != 0:
            print(
                f"  iconutil failed: {result.stderr.decode().strip()}",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"  logo.icns -> {out}")



def main():
    try:
        from PIL import Image  # noqa: F401
    except ImportError as error:
        raise SystemExit("Pillow is required; install the pinned icon-generation dependency first") from error

    os_name = platform.system()
    print(f"Generating installer icons (platform={os_name})...")

    write_linux_png()

    if os_name == "Windows":
        write_windows_ico()
    elif os_name == "Darwin":
        write_macos_icns()

    print("Done.")


if __name__ == "__main__":
    main()
