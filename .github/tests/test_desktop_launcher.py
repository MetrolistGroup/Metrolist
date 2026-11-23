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
        self.native_launcher = self.bin / "Metrolist.native-wrapper"
        native = Path(__file__).resolve().parents[2] / "scripts/desktop-native-image/linux-launcher.sh"
        self.executable(self.native_launcher, native.read_text())
        native_binary = self.bin.parent / "lib/native/Metrolist"
        native_binary.parent.mkdir(parents=True)
        self.executable(native_binary, (self.bin / "Metrolist.bin").read_text())
        self.environment = {
            "PATH": f"{self.tools}:/usr/bin:/bin",
            "HOME": str(self.root / "user home"),
            "DISPLAY": ":77",
            "PROBE_LOG": str(self.root / "probes"),
        }

    def executable(self, path, body):
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o755)

    def launch(self, native=False):
        result = subprocess.run(
            [self.native_launcher if native else self.launcher, "argument with spaces"],
            env=self.environment,
            capture_output=True,
            text=True,
            check=True,
            timeout=10,
        )
        return result.stdout.splitlines()

    def test_webview_uses_x11_and_preserves_explicit_backend(self):
        probe = 'printf "%s\n" "${GDK_BACKEND-unset}"'
        self.executable(self.bin / "Metrolist.bin", probe)
        self.executable(self.bin.parent / "lib/native/Metrolist", probe)
        for native in (False, True):
            with self.subTest(native=native):
                self.environment.pop("GDK_BACKEND", None)
                self.environment.pop("WAYLAND_DISPLAY", None)
                self.environment.pop("METROLIST_USE_WAYLAND", None)
                self.environment.pop("METROLIST_USE_WAKEFIELD", None)
                self.environment.update(DISPLAY=":77", XCURSOR_THEME="Theme", XCURSOR_SIZE="24")
                self.assertEqual(["x11"], self.launch(native))
                self.environment["GDK_BACKEND"] = "x11,wayland"
                self.assertEqual(["x11,wayland"], self.launch(native))
                del self.environment["GDK_BACKEND"]
                del self.environment["DISPLAY"]
                self.environment["WAYLAND_DISPLAY"] = "wayland-0"
                self.assertEqual(["wayland"], self.launch(native))
                self.environment["DISPLAY"] = ":77"
                self.environment["METROLIST_USE_WAYLAND"] = "1"
                self.assertEqual(["wayland"], self.launch(native))

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

    def test_kde_cursor_settings_take_precedence_in_both_launchers(self):
        self.environment["XDG_CURRENT_DESKTOP"] = "KDE"
        self.executable(self.tools / "gsettings", 'echo called >> "$PROBE_LOG"\necho 24')
        self.executable(
            self.tools / "kreadconfig6",
            'case "$6" in cursorTheme) echo Oxygen_White;; cursorSize) echo 48;; esac',
        )
        for native in (False, True):
            with self.subTest(native=native):
                self.assertEqual(["Oxygen_White", "48", "1"], self.launch(native)[:3])
        self.assertFalse(Path(self.environment["PROBE_LOG"]).exists())

    def test_kde5_and_explicit_cursor_overrides(self):
        self.environment.update(KDE_FULL_SESSION="true", XCURSOR_SIZE="64")
        self.executable(self.tools / "kreadconfig6", 'exit 1')
        self.executable(self.tools / "kreadconfig5", 'echo KDE5Theme')
        for native in (False, True):
            with self.subTest(native=native):
                self.assertEqual(["KDE5Theme", "64", "1"], self.launch(native)[:3])
                self.environment["XCURSOR_THEME"] = "UserTheme"
                self.assertEqual(["UserTheme", "64", "1"], self.launch(native)[:3])
                del self.environment["XCURSOR_THEME"]

    def test_missing_or_auto_kde_cursor_settings_leave_size_unset(self):
        self.environment["XDG_CURRENT_DESKTOP"] = "KDE"
        self.executable(self.tools / "gsettings", 'echo called >> "$PROBE_LOG"\necho 24')
        self.executable(self.tools / "kreadconfig5", 'exit 1')
        for value in ("", "0", "-1", "invalid"):
            self.executable(self.tools / "kreadconfig6", f"printf '%s' '{value}'")
            for native in (False, True):
                with self.subTest(value=value, native=native):
                    self.assertEqual("", self.launch(native)[1])
        self.assertFalse(Path(self.environment["PROBE_LOG"]).exists())

    def test_relative_xdg_cache_uses_home(self):
        self.environment.update(
            XCURSOR_THEME="UserTheme", XCURSOR_SIZE="32", XDG_CACHE_HOME="relative"
        )
        self.assertEqual(
            f'{self.environment["HOME"]}/.cache/metrolist', self.launch()[3]
        )


if __name__ == "__main__":
    unittest.main()
