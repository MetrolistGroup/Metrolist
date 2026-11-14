"""Exercise the generated shell wrapper with a stub launcher, without running the app."""

from pathlib import Path
import subprocess
import tempfile
import time
import unittest


class DesktopLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "app with spaces/bin"
        self.bin.mkdir(parents=True)
        self.tools = self.root / "tools"
        self.tools.mkdir()
        source = (
            Path(__file__).resolve().parents[2]
            / "desktopApp/packaging-launchers.gradle.kts"
        ).read_text()
        wrapper = source.split("// Write the wrapper script", 1)[1]
        wrapper = wrapper.split('$$"""', 1)[1].split('""".trimIndent()', 1)[0]
        self.launcher = self.bin / "Metrolist"
        self.executable(self.launcher, wrapper)
        self.executable(
            self.bin / "Metrolist.bin",
            'printf "%s\\n" "$XCURSOR_THEME" "$XCURSOR_SIZE" '
            '"$METROLIST_CURSOR_CONFIGURED" "$METROLIST_CDS_CACHE" "$@"',
        )
        self.environment = {
            "PATH": f"{self.tools}:/usr/bin:/bin",
            "HOME": str(self.root / "user home"),
            "DISPLAY": ":77",
            "PROBE_LOG": str(self.root / "probes"),
        }

    def executable(self, path, body):
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o755)

    def launch(self):
        result = subprocess.run(
            [self.launcher, "argument with spaces"],
            env=self.environment,
            capture_output=True,
            text=True,
            check=True,
            timeout=10,
        )
        return result.stdout.splitlines()

    def test_webview_uses_x11_and_preserves_explicit_backend(self):
        self.executable(self.bin / "Metrolist.bin", 'printf "%s\n" "$GDK_BACKEND"')
        self.environment.update(XCURSOR_THEME="Theme", XCURSOR_SIZE="24")
        self.assertEqual(["x11"], self.launch())
        self.environment["GDK_BACKEND"] = "x11,wayland"
        self.assertEqual(["x11,wayland"], self.launch())

    def test_missing_display_is_not_guessed_from_global_sockets(self):
        self.executable(self.bin / "Metrolist.bin", 'printf "%s\\n" "${DISPLAY-unset}"')
        self.environment.update(XCURSOR_THEME="UserTheme", XCURSOR_SIZE="24")
        del self.environment["DISPLAY"]
        self.assertEqual(["unset"], self.launch())

    def test_preserves_cursor_overrides_and_resolves_xdg_cache(self):
        for command in ("hyprctl", "gsettings"):
            self.executable(self.tools / command, 'echo called >> "$PROBE_LOG"')
        cache = str(self.root / "cache with spaces")
        self.environment.update(
            XCURSOR_THEME="UserTheme", XCURSOR_SIZE="32", XDG_CACHE_HOME=cache
        )
        self.assertEqual(
            ["UserTheme", "32", "1", f"{cache}/metrolist", "argument with spaces"],
            self.launch(),
        )
        self.assertFalse(Path(self.environment["PROBE_LOG"]).exists())

    def test_crash_file_path_respects_state_directory_and_escapes_hotspot_tokens(self):
        self.executable(
            self.bin / "Metrolist.bin", 'printf "%s\\n" "$METROLIST_JVM_ERROR_FILE"'
        )
        self.environment.update(XCURSOR_THEME="Theme", XCURSOR_SIZE="24")
        for state in (None, "relative", str(self.root / "state with spaces%p")):
            with self.subTest(state=state):
                if state is None:
                    self.environment.pop("XDG_STATE_HOME", None)
                else:
                    self.environment["XDG_STATE_HOME"] = state
                directory = (
                    f"{state}/metrolist/logs"
                    if state and Path(state).is_absolute()
                    else f'{self.environment["HOME"]}/.local/state/metrolist/logs'
                )
                expected = directory.replace("%", "%%") + "/hs_err_pid%p.log"
                self.assertEqual([expected], self.launch())

    def test_discovers_each_missing_cursor_value_once(self):
        self.executable(
            self.tools / "hyprctl",
            'echo "$2" >> "$PROBE_LOG"\n'
            'case "$2" in cursor:theme) echo "string: Detected";; '
            'cursor:size) echo "int: 24";; esac',
        )
        result = self.launch()
        self.assertEqual(["Detected", "24", "1"], result[:3])
        self.assertEqual(
            ["cursor:theme", "cursor:size"],
            Path(self.environment["PROBE_LOG"]).read_text().splitlines(),
        )
        self.assertEqual(f'{self.environment["HOME"]}/.cache/metrolist', result[3])

    def test_hanging_probe_is_bounded_and_falls_back(self):
        self.executable(self.tools / "hyprctl", "exec sleep 30")
        self.executable(self.tools / "gsettings", "echo \"'FallbackTheme'\"")
        self.environment["XCURSOR_SIZE"] = "24"
        start = time.monotonic()
        self.assertEqual(["FallbackTheme", "24", "1"], self.launch()[:3])
        self.assertLess(time.monotonic() - start, 5)

    def test_relative_xdg_cache_uses_home(self):
        self.environment.update(
            XCURSOR_THEME="UserTheme", XCURSOR_SIZE="32", XDG_CACHE_HOME="relative"
        )
        self.assertEqual(
            f'{self.environment["HOME"]}/.cache/metrolist', self.launch()[3]
        )


if __name__ == "__main__":
    unittest.main()
