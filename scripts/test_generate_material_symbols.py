#!/usr/bin/env python3
"""Check SVG conversion and the app's Material Symbols mappings without network access."""

import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch

import generate_material_symbols as generator
from generate_material_symbols import svg_path, symbol_for


class MaterialSymbolsTest(unittest.TestCase):
    def test_svg_path_keeps_960_unit_coordinates(self):
        svg = b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960"><path d="M80-80h80v-80Z"/></svg>'
        self.assertEqual("M80-80h80v-80Z", svg_path(svg))

    def test_24_unit_svg_matches_the_translated_960_viewport(self):
        svg = b'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><path d="M2 22h2v-2Z"/></svg>'
        self.assertEqual("M80 -80H160V-160L80 -80Z", svg_path(svg))

    def test_unexpected_viewport_or_transform_fails(self):
        for attributes in ('viewBox="0 0 48 48"', 'viewBox="0 -960 960 960" transform="scale(2)"'):
            with self.subTest(attributes=attributes), self.assertRaises(ValueError):
                svg_path(f'<svg xmlns="http://www.w3.org/2000/svg" {attributes}><path d="M0 0Z"/></svg>'.encode())

    def test_aliases_and_existing_fill_variants(self):
        cases = {
            "Outlined.NewReleases": ("release_alert", "outlined", 0),
            "Outlined.PeopleOutline": ("group", "outlined", 0),
            "Outlined.PhoneIphone": ("phone_iphone", "outlined", 0),
            "Filled.BarChart4Bars": ("bar_chart_4_bars", "outlined", 1),
            "Filled.FavoriteBorder": ("favorite", "outlined", 0),
            "Filled.Lyrics": ("lyrics", "outlined", 1),
            "Filled.Movie": ("smart_display", "outlined", 1),
            "Filled.PictureInPicture": ("picture_in_picture_alt", "outlined", 1),
            "Rounded.Favorite": ("favorite", "rounded", 1),
            "Rounded.FavoriteBorder": ("favorite", "rounded", 0),
            "Rounded.Pause": ("pause", "rounded", 1),
            "AutoMirrored.Rounded.QueueMusic": ("queue_music", "rounded", 0),
            "AutoMirrored.Filled.PlaylistPlay": ("playlist_play", "outlined", 1),
            "SongStatus.Explicit": ("explicit", "outlined", 0),
            "SongStatus.Saved": ("library_music", "outlined", 1),
            "SongStatus.Liked": ("favorite", "outlined", 1),
            "SongStatus.Downloaded": ("download_for_offline", "outlined", 1),
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                self.assertEqual(expected, symbol_for(name))

    def test_add_keeps_existing_entries_and_refreshes_without_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "MetrolistIconsFilled.kt"
            original = 'object MetrolistIconsFilled {\n    val Old = "untouched"\n}\n'
            path.write_text(original)
            with patch.object(generator, "ICON_DIR", Path(directory)), patch.object(generator, "fetch_path", return_value="M80-80Z"):
                generator.add_icons(generator.UPSTREAM_REVISION, "filled", ["LightMode"])
                first = path.read_text()
                generator.add_icons(generator.UPSTREAM_REVISION, "filled", ["LightMode"])
            self.assertEqual(first, path.read_text())
            self.assertIn('val Old = "untouched"', first)
            self.assertEqual([("Filled.LightMode", "M80-80Z")], generator.ENTRY_RE.findall(first))


if __name__ == "__main__":
    unittest.main()
