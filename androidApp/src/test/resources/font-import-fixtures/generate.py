from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen


def rectangle(width):
    pen = TTGlyphPen(None)
    if width:
        pen.moveTo((50, 0))
        pen.lineTo((width, 0))
        pen.lineTo((width, 700))
        pen.lineTo((50, 700))
        pen.closePath()
    return pen.glyph()


for name, width in (("first", 350), ("second", 550)):
    font = FontBuilder(1000, isTTF=True)
    order = [".notdef", "space", "A"]
    font.setupGlyphOrder(order)
    font.setupCharacterMap({32: "space", 65: "A"})
    font.setupGlyf({".notdef": rectangle(500), "space": rectangle(0), "A": rectangle(width)})
    font.setupHorizontalMetrics({glyph: (600, 0) for glyph in order})
    font.setupHorizontalHeader(ascent=800, descent=-200)
    font.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    font.setupNameTable({
        "familyName": f"Metrolist Test {name}",
        "styleName": "Regular",
        "uniqueFontIdentifier": f"Metrolist-Test-{name}",
        "fullName": f"Metrolist Test {name} Regular",
        "psName": f"Metrolist-Test-{name}-Regular",
    })
    font.setupPost()
    font.setupMaxp()
    font.font["head"].created = font.font["head"].modified = 3800000000
    font.font.recalcTimestamp = False
    font.save(Path(__file__).with_name(f"{name}.ttf"))
