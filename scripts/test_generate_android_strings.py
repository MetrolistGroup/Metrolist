"""Run the production Gradle string task on isolated catalogs, without the Android SDK."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]


class GenerateAndroidStringsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.catalog = self.root / 'shared/src/commonMain/composeResources'
        self.output = self.root / 'build/generated/androidStrings/res'
        source = (ROOT / 'androidApp/build.gradle.kts').read_text()
        task = source.split('abstract class VerifyComposeResourcesTask', 1)[0]
        registration = 'val generateAndroidStrings =' + source.split(
            'val generateAndroidStrings =', 1
        )[1].split('\nandroidComponents {', 1)[0]
        (self.root / 'settings.gradle.kts').write_text('rootProject.name = "android-strings-test"\n')
        (self.root / 'build.gradle.kts').write_text(task + registration)
        self.write('src/main/kotlin/References.kt', '''
val title = R.string.title
val count = R.plurals.tracks
val frameworkTitle = android.R.string.ok
val frameworkCount = android.R.plurals.unknown_framework_plural
''')
        self.write('src/main/res/layout/example.xml', '''
<View title="@string/xml_only" count="@plurals/xml_tracks" hint="@android:string/cancel" />
''')
        self.write('shared/src/androidMain/kotlin/References.kt', 'val shared = R.string.shared_only')
        for source_set in ('test', 'androidTest'):
            self.write(f'src/{source_set}/References.kt', 'val excluded = R.string.not_in_catalog')
        self.write_catalog('values', '''<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
<string name="title">It's <b>Sam's <i>music's</i></b> turn</string>
<plurals name="tracks"><item quantity="one">One artist's <b>track's</b> title</item>
<item quantity="other"><![CDATA[%1$d artists' tracks]]></item></plurals>
<string name="xml_only" translatable="false">XML</string>
<plurals name="xml_tracks"><item quantity="other">%1$d tracks</item></plurals>
<string name="shared_only"><xliff:g id="name">%1$s</xliff:g>'s music</string>
<string name="unused">Unused</string>
</resources>''')
        self.write_catalog('values-fr', '''<resources>
<string name="title">C'est <b>"l'artiste"</b></string>
<string name="unused">Inutilisé</string>
</resources>''')

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def write_catalog(self, qualifier, text):
        path = self.catalog / qualifier / 'strings.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def generate(self, succeeds=True, extra_args=()):
        result = subprocess.run(
            [os.environ.get('GRADLE', str(ROOT / 'gradlew')), '-p', str(self.root),
             'generateAndroidStrings', '--no-configuration-cache', '--console=plain', *extra_args],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(succeeds, result.returncode == 0, result.stdout + result.stderr)
        return result.stdout + result.stderr

    def test_scans_app_references_and_escapes_nested_text_and_plurals(self):
        original = (self.catalog / 'values/strings.xml').read_bytes()
        self.generate()
        base = ElementTree.parse(self.output / 'values/strings.xml').getroot()
        entries = {entry.attrib['name']: entry for entry in base}
        self.assertEqual({'title', 'tracks', 'xml_only', 'xml_tracks', 'shared_only'}, set(entries))
        self.assertEqual('false', entries['xml_only'].get('translatable'))
        self.assertEqual("It\\u0027s Sam\\u0027s music\\u0027s turn", ''.join(entries['title'].itertext()))
        self.assertEqual('music\\u0027s', entries['title'].find('b/i').text)
        items = entries['tracks'].findall('item')
        self.assertEqual(['one', 'other'], [item.attrib['quantity'] for item in items])
        self.assertEqual("One artist\\u0027s track\\u0027s title", ''.join(items[0].itertext()))
        self.assertEqual('%1$d artists\\u0027 tracks', items[1].text)
        self.assertEqual('%1$s\\u0027s music', ''.join(entries['shared_only'].itertext()))
        self.assertIsNotNone(entries['shared_only'].find('{urn:oasis:names:tc:xliff:document:1.2}g'))
        localized = ElementTree.parse(self.output / 'values-fr/strings.xml').getroot()
        self.assertEqual(['title'], [entry.attrib['name'] for entry in localized])
        self.assertEqual('C\\u0027est \\u0022l\\u0027artiste\\u0022', ''.join(localized[0].itertext()))
        self.assertEqual(original, (self.catalog / 'values/strings.xml').read_bytes())

    def test_cache_restores_identical_catalog_in_another_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / 'cache'
            settings = self.root / 'settings.gradle.kts'
            settings.write_text(settings.read_text() + f'buildCache {{ local {{ directory = file("{cache.as_posix()}") }} }}\n')
            self.generate(extra_args=('--build-cache',))
            expected = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()}
            relocated = Path(directory) / 'checkout'
            shutil.copytree(self.root, relocated, ignore=shutil.ignore_patterns('build', '.gradle'))
            self.root = relocated
            self.output = relocated / 'build/generated/androidStrings/res'
            output = self.generate(extra_args=('--build-cache',))
            self.assertIn(':generateAndroidStrings FROM-CACHE', output)
            actual = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()}
            self.assertEqual(expected, actual)

    def test_missing_base_keys_fail_before_deleting_previous_output(self):
        self.write('src/main/kotlin/Missing.kt', 'val x = R.string.missing\nval y = R.plurals.missing_count')
        self.write_catalog('values-fr', '<resources><string name="missing">Absent</string></resources>')
        self.output.mkdir(parents=True)
        previous = self.output / 'previous'
        previous.write_text('previous generation')
        output = self.generate(succeeds=False)
        self.assertIn('Android references missing shared strings: plurals/missing_count, string/missing', output)
        self.assertEqual('previous generation', previous.read_text())

    def test_regeneration_removes_stale_entries_and_locales(self):
        self.generate()
        self.write('src/main/kotlin/References.kt', 'val title = R.string.title')
        (self.root / 'src/main/res/layout/example.xml').unlink()
        (self.root / 'shared/src/androidMain/kotlin/References.kt').unlink()
        (self.catalog / 'values-fr/strings.xml').unlink()
        self.generate()
        base = ElementTree.parse(self.output / 'values/strings.xml').getroot()
        self.assertEqual(['title'], [entry.attrib['name'] for entry in base])
        self.assertFalse((self.output / 'values-fr').exists())


if __name__ == '__main__':
    unittest.main()
