import json
import tempfile
import unittest
from pathlib import Path

from collect_android_apks import collect


class CollectAndroidApksTest(unittest.TestCase):
    def test_collects_universal_apk_and_rejects_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.metadata(root, "release", ["universal"])
            (root / "stale.apk").write_bytes(b"stale")
            collect(root, root / "dist", "14.0.0-rc0", "release")
            self.assertEqual({"Metrolist.apk"}, {path.name for path in (root / "dist").iterdir()})
            self.assertEqual(b"universal", (root / "dist/Metrolist.apk").read_bytes())
            self.metadata(root, "release", ["universal", "arm64-v8a"])
            with self.assertRaises(ValueError):
                collect(root, root / "split", "14.0.0-rc0", "release")
            self.metadata(root, "release", ["arm64-v8a"])
            with self.assertRaises(ValueError):
                collect(root, root / "split", "14.0.0-rc0", "release")
            self.assertFalse((root / "split").exists())

    def test_debug_and_invalid_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = self.metadata(root, "debug", ["universal"])
            collect(root, root / "dist", "14.0.0-rc0", "debug")
            self.assertTrue((root / "dist/metrolist-android-14.0.0-rc0-debug.apk").is_file())
            with self.assertRaises(ValueError):
                collect(root, root / "bad", "14.0.0-rc0", "release")
            with self.assertRaises(ValueError):
                collect(root, root / "bad", "14.0.0", "debug")
            metadata["elements"].append(metadata["elements"][0])
            (root / "output-metadata.json").write_text(json.dumps(metadata))
            with self.assertRaises(ValueError):
                collect(root, root / "bad", "14.0.0-rc0", "debug")
            metadata["elements"] = [dict(metadata["elements"][0], outputFile="../outside.apk")]
            (root / "output-metadata.json").write_text(json.dumps(metadata))
            with self.assertRaises(ValueError):
                collect(root, root / "bad", "14.0.0-rc0", "debug")

    @staticmethod
    def metadata(root, variant, abis):
        elements = []
        for abi in abis:
            name = f"androidApp-{abi}-{variant}.apk"
            (root / name).write_bytes(abi.encode())
            elements.append({
                "filters": [] if abi == "universal" else [{"filterType": "ABI", "value": abi}],
                "versionName": "14.0.0-rc0",
                "outputFile": name,
            })
        metadata = {"variantName": variant, "elements": elements}
        (root / "output-metadata.json").write_text(json.dumps(metadata))
        return metadata


if __name__ == "__main__":
    unittest.main()
