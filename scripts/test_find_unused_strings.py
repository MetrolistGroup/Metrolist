#!/usr/bin/env python3
"""Core checks for conservative reference discovery and byte-preserving pruning."""

from pathlib import Path
import tempfile

from find_unused_strings import CATALOG, ROOT, apply, audit, prune_xml


def main() -> None:
    scratch = ROOT / "build"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=scratch) as directory:
        root = Path(directory)
        names = ["compose_name", "android_name", "plural_name", "array_name", "xml_name",
                 "swift_name", "plist_name", "flatpak_name", "table_name", "map_name",
                 "comment_name", "template_one", "concat_one", "swift_one", "format_one",
                 "alias_name", "unused_name", "unused_plural", "unused_array"]
        catalog = root / CATALOG / "values/strings.xml"
        catalog.parent.mkdir(parents=True)
        catalog.write_text('<resources>\n<!-- attribution stays -->\n' + ''.join(
            f'  <string name="{name}">{name}</string>\n' for name in names[:-2]
        ) + '<plurals name="unused_plural"><item quantity="other">Many</item></plurals>\n'
          '<string-array name="unused_array"><item>A</item></string-array>\n'
          '<string name="alias">@string/alias_name</string>\n</resources>\n')
        locale = root / CATALOG / "values-test/strings.xml"
        locale.parent.mkdir()
        locale.write_text('<resources><!-- credits --><string name="unused_name">Text</string>'
                          '<string name="orphan">Text</string></resources>')
        sources = {
            'shared/Example.kt': 'Res.string.compose_name R.plurals.plural_name Res.array.array_name\n'
                                 'val keys = listOf("map_name") // comment_name\n'
                                 '"template_$id" + "concat_" + id',
            'androidApp/Example.kt': 'R.string.android_name',
            'androidApp/src/main/AndroidManifest.xml': '@string/xml_name',
            'iosApp/Example.swift': 'NSLocalizedString("swift_name", comment: "") + "swift_\\(id)"',
            'iosApp/Info.plist': '<string>plist_name</string>',
            'desktopApp/app.metainfo.xml': '<name>flatpak_name</name>',
            'scripts/table.json': '["table_name"]',
            'scripts/example.py': '"format_{}".format(value)',
            'shared/build/generated.kt': 'Res.string.unused_name',
        }
        for name, content in sources.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        before = catalog.read_bytes()
        report = audit(root)
        assert catalog.read_bytes() == before, "Dry run wrote a catalog"
        assert report['unused'] == ['unused_array', 'unused_name', 'unused_plural']
        assert set(report['dynamic_keys']) == {'template_one', 'concat_one', 'swift_one', 'swift_name', 'format_one'}
        assert report['translation_orphans'] == {str(locale.relative_to(root)): ['orphan']}
        apply(root, set(report['unused']))
        assert b'<!-- attribution stays -->' in catalog.read_bytes()
        assert locale.read_text() == '<resources><!-- credits --><string name="orphan">Text</string></resources>'
        assert audit(root)['unused'] == []
    data = b'<resources>\r\n  <!-- keep -->\r\n  <string name="gone"/>\r\n  <string name="stay">A &amp; B</string>\r\n</resources>'
    assert prune_xml(data, {'gone'}) == data.replace(b'  <string name="gone"/>\r\n', b'')
    data = b'<resources><string name="gone"/></resources>'
    assert prune_xml(data, {'gone'}) == b'<resources></resources>'
    data = b'<resources><string-array name="gone"><item/></string-array></resources>'
    assert prune_xml(data, {'gone'}) == b'<resources></resources>'
    print('Unused resource audit checks passed')


if __name__ == '__main__':
    main()
