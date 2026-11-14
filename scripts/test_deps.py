"""Exercise dependency setup with fake tools and synthetic local configuration."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("deps.sh").resolve()


class DependencySetupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.environment = dict(os.environ, PATH=f"{self.bin}:/usr/bin:/bin", ANDROID_HOME=str(self.root / "sdk"))
        self.url_file = self.root / "download-url"
        self.tool("git", "pwd")
        self.tool("java", "echo 'openjdk version \"25.0.5\"' >&2")
        self.tool("curl", "touch \"$3\"\nprintf '%s\\n' \"$4\" > \"$CURL_URL_FILE\"")
        self.tool("unzip", '''mkdir -p "$4/cmdline-tools/bin"
printf '#!/bin/sh\nexit 0\n' > "$4/cmdline-tools/bin/sdkmanager"
chmod +x "$4/cmdline-tools/bin/sdkmanager"''')

    def tool(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o755)

    def run_setup(self, host, machine="x86_64"):
        self.tool("uname", 'if [ "$1" = "-m" ]; then printf "%s\\n" "$UNAME_MACHINE"; else printf "%s\\n" "$UNAME_SYSTEM"; fi')
        environment = dict(
            self.environment,
            UNAME_SYSTEM=host,
            UNAME_MACHINE=machine,
            CURL_URL_FILE=str(self.url_file),
        )
        return subprocess.run(["bash", str(SCRIPT)], cwd=self.root, env=environment,
                              capture_output=True, text=True, timeout=10)

    def test_missing_sdk_property_preserves_existing_configuration_without_final_newline(self):
        config = self.root / "local.properties"
        config.write_text("custom.setting=preserve-me")
        result = self.run_setup("Linux")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"custom.setting=preserve-me\nsdk.dir={self.root / 'sdk'}\n", config.read_text())
        self.assertEqual(
            "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip\n",
            self.url_file.read_text(),
        )

    def test_existing_sdk_property_preserves_other_configuration(self):
        config = self.root / "local.properties"
        config.write_text("custom.setting=preserve-me\nsdk.dir=/old/sdk\n")
        result = self.run_setup("Darwin")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"custom.setting=preserve-me\nsdk.dir={self.root / 'sdk'}\n", config.read_text())
        self.assertEqual(
            "https://dl.google.com/android/repository/commandlinetools-mac_x86_64-16111833_latest.zip\n",
            self.url_file.read_text(),
        )

    def test_apple_silicon_uses_arm64_command_line_tools(self):
        result = self.run_setup("Darwin", "arm64")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            "https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip\n",
            self.url_file.read_text(),
        )

    def test_windows_stops_before_installing_anything(self):
        result = self.run_setup("MINGW64_NT")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("install Temurin 25 and Android SDK", result.stderr)
        self.assertFalse((self.root / "sdk").exists())


if __name__ == "__main__":
    unittest.main()
