import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

INSTALLER_PATH = Path(__file__).with_name("install_ios.py")
INSTALLER_SPEC = importlib.util.spec_from_file_location("install_ios", INSTALLER_PATH)
assert INSTALLER_SPEC and INSTALLER_SPEC.loader
install_ios = importlib.util.module_from_spec(INSTALLER_SPEC)
INSTALLER_SPEC.loader.exec_module(install_ios)


class InstallIosTest(unittest.TestCase):
    def test_verified_ipa_accepts_matching_checksum(self):
        ipa = b"test ipa"
        digest = hashlib.sha256(ipa).hexdigest()
        release = {
            "tag_name": "v1",
            "assets": [
                {"name": "Metrolist.ipa", "browser_download_url": "https://github.com/project/Metrolist.ipa", "size": 8},
                {"name": "SHA256SUMS", "browser_download_url": "https://github.com/project/SHA256SUMS", "size": 75},
            ],
        }

        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "Metrolist.ipa"

            def write_download(url: str, path: Path) -> None:
                path.write_bytes(ipa if path == destination else f"{digest}  Metrolist.ipa\n".encode())

            with patch.object(install_ios, "download", side_effect=write_download):
                url = install_ios.download_verified_release_ipa(release, destination)

            self.assertEqual(url, "https://github.com/project/Metrolist.ipa")
            self.assertEqual(destination.read_bytes(), ipa)

    def test_verified_ipa_rejects_missing_and_wrong_checksums(self):
        ipa = b"test ipa"
        release = {
            "tag_name": "v1",
            "assets": [
                {"name": "Metrolist.ipa", "browser_download_url": "https://github.com/project/Metrolist.ipa", "size": 8},
                {"name": "SHA256SUMS", "browser_download_url": "https://github.com/project/SHA256SUMS", "size": 75},
            ],
        }

        for checksum in (b"", b"0" * 64 + b"  Metrolist.ipa\n"):
            with self.subTest(checksum=checksum), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "Metrolist.ipa"

                def write_download(url: str, path: Path) -> None:
                    path.write_bytes(ipa if path == destination else checksum)

                with patch.object(install_ios, "download", side_effect=write_download):
                    with self.assertRaises(install_ios.InstallError):
                        install_ios.download_verified_release_ipa(release, destination)

                self.assertFalse(destination.exists())

    def test_sideloader_assets_match_supported_platform_architectures(self):
        cases = (
            ("linux", "x86_64", "posix", "sideloader-cli-x86_64-linux-gnu.zip"),
            ("linux", "aarch64", "posix", "sideloader-cli-aarch64-linux-gnu.zip"),
            ("darwin", "arm64", "posix", "sideloader-cli-arm64-apple-macos.zip"),
            ("darwin", "x86_64", "posix", "sideloader-cli-x86_64-apple-darwin.zip"),
            ("win32", "AMD64", "nt", "sideloader-cli-x86_64-windows-msvc.zip"),
        )
        for platform_name, machine, os_name, expected in cases:
            with self.subTest(platform=platform_name, machine=machine):
                with (
                    patch.object(install_ios.sys, "platform", platform_name),
                    patch.object(install_ios.platform, "machine", return_value=machine),
                    patch.object(install_ios.os, "name", os_name),
                ):
                    self.assertEqual(install_ios.sideloader_asset_name(), expected)

    def test_windows_arm_rejects_before_installing_device_support(self):
        with tempfile.TemporaryDirectory() as directory:
            install_dir = Path(directory)
            with (
                patch.object(install_ios.sys, "platform", "win32"),
                patch.object(install_ios.platform, "machine", return_value="ARM64"),
                patch.object(install_ios.os, "name", "nt"),
                patch.object(install_ios, "install_windows_device_support") as install_support,
            ):
                with self.assertRaises(install_ios.InstallError):
                    install_ios.install_sideloader(install_dir)

            install_support.assert_not_called()

    def test_libplist_versions_incompatible_with_sideloader_are_rejected(self):
        install_ios.require_compatible_libplist("2.2.0")
        with self.assertRaises(install_ios.InstallError):
            install_ios.require_compatible_libplist("2.3.0")

    def test_release_lookup_uses_the_pinned_tag(self):
        payload = {
            "tag_name": install_ios.SIDELOADER_RELEASE,
            "assets": [
                {
                    "name": "sideloader-cli-x86_64-linux-gnu.zip",
                    "browser_download_url": "https://github.com/Dadoum/Sideloader/download/tool.zip",
                    "size": 10,
                }
            ],
        }
        with patch.object(install_ios, "request_bytes", return_value=json.dumps(payload).encode()) as request:
            install_ios.github_release_asset(
                install_ios.SIDELOADER_REPOSITORY,
                "sideloader-cli-x86_64-linux-gnu.zip",
                release_tag=install_ios.SIDELOADER_RELEASE,
            )

        self.assertIn(f"/releases/tags/{install_ios.SIDELOADER_RELEASE}", request.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
