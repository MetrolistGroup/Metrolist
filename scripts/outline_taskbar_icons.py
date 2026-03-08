"""Add a dark outline to the Windows thumbnail toolbar's white ICO glyphs.

Run from the repository root with ``python3 scripts/outline_taskbar_icons.py``.
Requires Pillow 13.0.0. The transformation is idempotent, so committed icons can be
processed again without growing the outline.
"""

from pathlib import Path

from PIL import Image, ImageFilter


ICON_DIR = Path(__file__).resolve().parents[1] / "desktopApp/src/jvmMain/resources"


for name in ("previous", "play", "pause", "next"):
    path = ICON_DIR / f"{name}.ico"
    with Image.open(path) as icon:
        glyph = icon.convert("RGBA")

    pixels = glyph.load()
    white_alpha = Image.new("L", glyph.size)
    white_alpha.putdata(
        [
            pixels[x, y][3] if pixels[x, y][:3] == (255, 255, 255) else 0
            for y in range(glyph.height)
            for x in range(glyph.width)
        ]
    )
    if white_alpha.getbbox() is None:
        raise ValueError(f"No white glyph found in {path}")
    outline = Image.new("RGBA", glyph.size, (0, 0, 0, 0))
    outline.putalpha(white_alpha.filter(ImageFilter.MaxFilter(9)))
    outline.paste(glyph, mask=white_alpha.point(lambda alpha: 255 if alpha else 0))
    outline.save(path, format="ICO", sizes=[glyph.size])
