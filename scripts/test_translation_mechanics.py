#!/usr/bin/env python3
"""Exercise placeholder contracts, CLDR categories and missing-key reports."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from xml.etree import ElementTree

from check_translation_mechanics import (
    CATEGORIES, catalog_errors, locale_for_directory, missing_translations, translation_errors,
)


def catalog(xml):
    return {element.get("name"): element for element in ElementTree.fromstring(f"<resources>{xml}</resources>")}


def plural(items):
    return '<plurals name="count">' + ''.join(
        f'<item quantity="{quantity}">{text}</item>' for quantity, text in items.items()
    ) + '</plurals>'


class TranslationMechanicsTest(unittest.TestCase):
    def test_crash_recovery_resources_use_only_the_base_catalog(self):
        for name in ('crash_title', 'crash_copy_logs', 'crash_report_app_version', 'database_downgrade'):
            with self.subTest(name=name):
                base = catalog(f'<string name="{name}" translatable="false">Report</string>')
                self.assertEqual([], list(catalog_errors(base, base, 'values')))
                self.assertEqual([], list(catalog_errors({}, base, 'values-fr')))
                localized = catalog(f'<string name="{name}">Rapport</string>')
                self.assertTrue(any('must not appear' in error for error in catalog_errors(localized, base, 'values-fr')))
                self.assertTrue(any('must set translatable' in error for error in catalog_errors(localized, localized, 'values')))

    def test_placeholder_indices_and_types(self):
        base = catalog('<string name="message">%1$d tracks by %2$s</string>')
        for text in ('%2$s: %1$d titres', '%2$s: <b>%1$d</b> titres (%1$d)'):
            self.assertEqual([], list(catalog_errors(catalog(f'<string name="message">{text}</string>'), base, 'values-fr')))
        for text in ('%s: %d', '%02d %s', '%1$s: %2$d', '%1$d', '%1$d %3$s', '%0$d %2$s', '%1$f %2$s', '%1$ d %2$s', '%1$d %1$s %2$s', '%1$D %2$s'):
            with self.subTest(text=text):
                self.assertTrue(list(catalog_errors(catalog(f'<string name="message">{text}</string>'), base, 'values-fr')))

    def test_literal_percent_is_not_a_format_flag(self):
        base = catalog('<string name="message">50% discount</string>')
        localized = catalog('<string name="message">50% de remise</string>')
        self.assertEqual([], list(catalog_errors(base, base, 'values')))
        self.assertEqual([], list(catalog_errors(localized, base, 'values-fr')))

    def test_incomplete_base_placeholder_fails_without_a_translation(self):
        for text in ('%1$', '%1$?', '%1$2'):
            base = catalog(f'<string name="message">{text}</string>')
            self.assertTrue(list(catalog_errors(base, base, 'values')))

    def test_categories_include_fractional_and_large_number_forms(self):
        base = catalog(plural({'one': '%1$d track', 'other': '%1$d tracks'}))
        for locale, categories in {
            'ru': 'one few many other', 'uk': 'one few many other', 'pl': 'one few many other',
            'ar': 'zero one two few many other', 'cs': 'one few many other', 'sk': 'one few many other',
            'ga': 'one two few many other', 'lt': 'one few many other', 'sl': 'one two few other',
            'ca': 'one many other', 'he': 'one two other', 'ja': 'other',
        }.items():
            self.assertEqual(set(categories.split()), CATEGORIES[locale])
            items = {category: '%1$d' for category in categories.split()}
            self.assertEqual([], list(catalog_errors(catalog(plural(items)), base, f'values-{locale}')))
            for category in items:
                incomplete = {key: value for key, value in items.items() if key != category}
                errors = list(catalog_errors(catalog(plural(incomplete)), base, f'values-{locale}'))
                self.assertTrue(any('missing plural categories' in error for error in errors), errors)

    def test_fixed_count_omission_does_not_hide_missing_other_arguments(self):
        base = catalog(plural({'one': '%1$d track by %2$s', 'other': '%1$d tracks by %2$s'}))
        items = {'one': 'O piesă de %2$s', 'few': '%1$d piese de %2$s', 'other': '%1$d de piese de %2$s'}
        self.assertEqual([], list(catalog_errors(catalog(plural(items)), base, 'values-ro')))
        for category in items:
            broken = dict(items, **{category: 'O piesă'})
            self.assertTrue(list(catalog_errors(catalog(plural(broken)), base, 'values-ro')))
        items = {category: '%1$d' for category in CATEGORIES['ar']}
        items.update(one='واحد', two='اثنان')
        count_base = catalog(plural({'one': '%1$d', 'other': '%1$d'}))
        self.assertEqual([], list(catalog_errors(catalog(plural(items)), count_base, 'values-ar')))
        self.assertTrue(list(catalog_errors(catalog(plural({'one': 'one', 'other': '%1$d'})), count_base, 'values-hi')))

    def test_count_is_not_always_the_first_argument(self):
        base = catalog(plural({'one': '%1$s from one playlist', 'other': '%1$s from %2$d playlists'}).replace('name="count"', 'name="import_playlists_notice"'))
        valid = catalog(plural({'one': '%1$s from one playlist', 'other': '%1$s from %2$d playlists'}).replace('name="count"', 'name="import_playlists_notice"'))
        self.assertEqual([], list(catalog_errors(valid, base, 'values-de')))
        missing_tracks = catalog(plural({'one': 'one playlist', 'other': '%1$s from %2$d playlists'}).replace('name="count"', 'name="import_playlists_notice"'))
        self.assertTrue(list(catalog_errors(missing_tracks, base, 'values-de')))
        self.assertTrue(list(catalog_errors(valid, base, 'values-hi')))

    def test_qualifiers_aliases_and_unknown_languages(self):
        for directory, locale in {'values-b+hi+Latn': 'hi', 'values-b+pt+PT': 'pt-PT',
                                  'values-pt-rBR': 'pt', 'values-pt-rPT': 'pt-PT',
                                  'values-in': 'id', 'values-iw': 'he', 'values-bn-rIN': 'bn'}.items():
            self.assertEqual(locale, locale_for_directory(directory))
        self.assertTrue(list(catalog_errors({}, {}, 'values-unknown')))

    def test_duplicate_categories_and_resource_kind(self):
        base = catalog(plural({'one': '%1$d', 'other': '%1$d'}))
        duplicate = catalog('<plurals name="count"><item quantity="other">%1$d</item><item quantity="other">%1$d</item></plurals>')
        self.assertTrue(any('duplicate' in error for error in catalog_errors(duplicate, base, 'values-ja')))
        self.assertTrue(list(catalog_errors(catalog('<string name="count">%1$d</string>'), base, 'values-ja')))

    def test_catalog_files_and_missing_report(self):
        with TemporaryDirectory() as directory:
            resources = Path(directory)
            (resources / 'values').mkdir()
            (resources / 'values-fr').mkdir()
            (resources / 'values/strings.xml').write_text('<resources><string name="label">Label</string><string name="brand" translatable="false">Brand</string></resources>')
            locale = resources / 'values-fr/strings.xml'
            locale.write_text('<resources/>')
            self.assertEqual([], list(translation_errors(resources)))
            self.assertEqual({'values-fr': ['label']}, missing_translations(resources))
            locale.write_text('<resources><string name="label">A</string><string name="label">B</string></resources>')
            self.assertTrue(any('duplicate resource' in error for error in translation_errors(resources)))
            locale.write_text('<resources>')
            self.assertTrue(list(translation_errors(resources)))


if __name__ == '__main__':
    unittest.main()
