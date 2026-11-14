#!/usr/bin/env python3
"""Exercise the XML and escape rules shared by CI and the pre-commit hook."""
from pathlib import Path
from tempfile import TemporaryDirectory
from check_compose_resource_escapes import invalid_escapes


with TemporaryDirectory() as directory:
    resources = Path(directory)
    try:
        list(invalid_escapes(resources))
    except FileNotFoundError:
        pass
    else:
        raise AssertionError("An empty resource directory must fail")

    locale = resources / "values-it"
    locale.mkdir()
    file = locale / "strings.xml"
    invalid = [
        ('<resources><string name="label">l\\\'album</string></resources>', "label"),
        ('<resources><string name="quoted">\\"Liked\\"</string></resources>', "quoted"),
        ('<resources><string name="percent">50%%</string></resources>', "percent"),
        ('<resources><plurals name="count"><item quantity="one">%%</item></plurals></resources>', "one"),
        ('<resources><string name="mixed">text <b>%%</b></string></resources>', "mixed"),
    ]
    for content, key in invalid:
        file.write_text(content, encoding="utf-8")
        assert list(invalid_escapes(resources)) == [f"{file}: {key}: Android-only escape"]

    file.write_text('<resources><string name="label">l\'album "50%" &amp; &apos; &quot;</string></resources>', encoding="utf-8")
    assert not list(invalid_escapes(resources))
    file.write_text('<resources><string name="label">Boyut: %%1$d</string></resources>', encoding="utf-8")
    assert not list(invalid_escapes(resources))
    file.write_bytes('<?xml version="1.0" encoding="utf-16"?><resources><string name="label">caffè</string></resources>'.encode("utf-16"))
    assert not list(invalid_escapes(resources))

    for content in (
        b"", b"<resources><string>", b'<resources><string name="label">\xff</string></resources>',
        b'<?xml version="1.0" encoding="unknown"?><resources/>',
        b'<?xml version="1.0" encoding="utf-32"?><resources/>',
    ):
        file.write_bytes(content)
        errors = list(invalid_escapes(resources))
        assert len(errors) == 1 and errors[0].startswith(f"{file}: invalid XML:"), errors

    file.unlink()
    base = resources / "values"
    base.mkdir()
    (base / "strings.xml").write_text('<resources><string name="label">50%%</string></resources>', encoding="utf-8")
    assert len(list(invalid_escapes(resources))) == 1

print("Compose resource XML and escape checks passed")
