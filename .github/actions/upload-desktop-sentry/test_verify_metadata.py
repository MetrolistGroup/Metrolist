from pathlib import Path
import tempfile
import unittest
import warnings
from zipfile import BadZipFile, ZipFile

from verify_metadata import verify_metadata


class VerifyMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.metadata = self.root / "expected.properties"
        self.metadata.write_bytes(b"io.sentry.bundle-ids=example\n")

    def jar(self, name, identities):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(path, "w") as archive:
            archive.writestr("other.class", b"unrelated")
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                for identity in identities:
                    archive.writestr("sentry-debug-meta.properties", identity)

    def test_scans_nested_archives_and_every_matching_entry(self):
        self.jar("app with spaces/a.jar", [self.metadata.read_bytes()])
        self.jar("app with spaces/b.jar", [])
        self.jar("second app/a.jar", [self.metadata.read_bytes()])
        self.assertEqual(2, verify_metadata(self.metadata, self.root))

    def test_rejects_mismatch_even_after_a_matching_duplicate_entry(self):
        self.jar("app.jar", [self.metadata.read_bytes(), b"wrong"])
        with self.assertRaisesRegex(ValueError, "mismatch"):
            verify_metadata(self.metadata, self.root)

    def test_rejects_missing_identity(self):
        self.jar("app.jar", [])
        with self.assertRaisesRegex(ValueError, "no Sentry"):
            verify_metadata(self.metadata, self.root)

    def test_rejects_invalid_archive(self):
        (self.root / "broken.jar").write_bytes(b"not a zip")
        with self.assertRaises(BadZipFile):
            verify_metadata(self.metadata, self.root)


if __name__ == "__main__":
    unittest.main()
