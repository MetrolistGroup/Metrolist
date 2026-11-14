#!/usr/bin/env python3
"""Check desktop smoke-test failures without launching Metrolist or a display."""

from contextlib import redirect_stderr, nullcontext
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

import desktop_smoke as smoke

ROOT = Path(__file__).resolve().parents[1]
HEADER = "[2026-11-10 12:00:00.000]"


class DesktopSmokeTest(unittest.TestCase):
    def test_profile_uses_real_settings_and_log_paths_on_every_platform(self):
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory) / "profile with spaces"
            env = smoke.profile_environment(profile)
            for kind in ("CONFIG", "DATA", "STATE", "CACHE"):
                self.assertEqual(str(profile / kind.lower()), env[f"XDG_{kind}_HOME"])
                self.assertTrue(Path(env[f"XDG_{kind}_HOME"]).is_dir())
            settings = (profile / "config/metrolist/settings.properties").read_text()
            for setting in ("desktopOnboardingComplete=true", "desktopOnboardingInitialized=true",
                            "sentryEnabled=false", "enableLogging=true"):
                self.assertIn(setting + "\n", settings)
            self.assertIn(f'"-Duser.home={profile}"', env["_JAVA_OPTIONS"])
            self.assertIn(f'"-XX:ErrorFile={profile}/state/metrolist/logs/hs_err_pid%p.log"', env["_JAVA_OPTIONS"])

    def test_parses_applogger_levels_tags_and_multiline_native_errors(self):
        harmless = (
            f"{HEADER} [WARN] [Network] Request failed\n"
            f"{HEADER} [ERRO] [Home] Failed to load recommendations\n"
            f"{HEADER} [INFO] [Main] Crash reporting disabled\n"
        )
        self.assertEqual([], smoke.fatal_records(harmless))
        for record in (
            "[ERRO] [UncaughtException] Uncaught exception type=IllegalStateException\nstack trace",
            "[ERRO] [Main] Fatal startup failure",
            "[ERRO] [Crash] Native startup failed",
            "[ERRO] [Player] Initialization failed\njava.lang.UnsatisfiedLinkError: missing DLL",
            "[ERRO] [Main] java.lang.NoClassDefFoundError: MissingClass",
        ):
            with self.subTest(record=record):
                entry = f"{HEADER} {record}"
                self.assertEqual([entry], smoke.fatal_records(harmless + entry + "\n" + harmless))

    @unittest.skipUnless(os.sys.platform == "linux", "Uses a fake AppImage and POSIX signals")
    def test_lifecycle_keeps_evidence_and_rejects_startup_and_shutdown_failures(self):
        for mode in ("healthy", "cleanup", "exit-zero", "exit-error", "fatal", "native", "late-fatal", "hung", "no-log", "screenshot-error"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                package = root / "fake.AppImage"
                package.write_text('''#!/usr/bin/env python3
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

if "--appimage-extract-and-run" in sys.argv:
    child = subprocess.Popen([sys.executable, __file__, "child"])
    status = child.wait()
    if os.environ["SMOKE_TEST_MODE"] == "cleanup":
        time.sleep(0.3)
    sys.exit(status)

mode = os.environ["SMOKE_TEST_MODE"]
logs = Path(os.environ["XDG_STATE_HOME"]) / "metrolist/logs"
log = logs / "app.log"
if mode != "no-log":
    log.write_text("[2026-11-10 12:00:00.000] [INFO] [Main] Started\\n")
print("fake app stdout", flush=True)
print("fake app stderr", file=sys.stderr, flush=True)
def quit_app(*args):
    if mode == "late-fatal":
        with log.open("a") as output:
            output.write("[2026-11-10 12:00:00.000] [ERRO] [UncaughtException] Late failure\\n")
    sys.exit(0)
signal.signal(signal.SIGTERM, signal.SIG_IGN if mode == "hung" else quit_app)
if mode.startswith("exit-"):
    sys.exit(0 if mode == "exit-zero" else 1)
if mode == "fatal":
    log.write_text("[2026-11-10 12:00:00.000] [ERRO] [UncaughtException] Failed\\n")
if mode == "native":
    (logs / "hs_err_pid123.log").write_text("native failure")
while True:
    time.sleep(1)
''')
                package.chmod(0o755)
                output = root / "evidence"

                def capture(destination, system):
                    if mode == "screenshot-error":
                        raise RuntimeError("capture failed")
                    destination.write_bytes(b"fake screenshot")

                with patch.dict(os.environ, {"SMOKE_TEST_MODE": mode}), \
                        patch.object(smoke, "STARTUP_SECONDS", 1), \
                        patch.object(smoke, "SHUTDOWN_SECONDS", 0.2 if mode in ("hung", "cleanup") else 2), \
                        patch.object(smoke, "screenshot", side_effect=capture), redirect_stderr(io.StringIO()):
                    result = smoke.smoke(package, output, "linux")
                self.assertEqual(0 if mode in ("healthy", "cleanup") else 1, result)
                stdout = (output / "stdout.log").read_text()
                self.assertIn("fake app stdout", stdout)
                self.assertIn("fake app stderr", stdout)
                self.assertEqual(mode != "no-log", (output / "app.log").exists())
                self.assertEqual(mode != "screenshot-error", (output / "desktop.png").exists())
                report = (output / "result.txt").read_text()
                expected = {
                    "healthy": "passed", "cleanup": "passed", "exit-zero": "exited during startup with code 0",
                    "exit-error": "exited during startup with code 1", "fatal": "UncaughtException",
                    "native": "HotSpot crash report", "late-fatal": "Late failure", "hung": "Shutdown failed",
                    "no-log": "app.log is missing", "screenshot-error": "Screenshot failed",
                }
                self.assertIn(expected[mode], report)
                if mode == "native":
                    self.assertEqual("native failure", (output / "0-hs_err_pid123.log").read_text())

    def test_collects_rotated_logs_and_crash_reports_outside_log_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            smoke.profile_environment(work / "profile")
            output = work / "evidence"
            output.mkdir()
            logs = work / "profile/state/metrolist/logs"
            (logs / "app.log").touch()
            (logs / "app.log.1").write_text(f"{HEADER} [ERRO] [UncaughtException] Rotated crash\n")
            (work / "hs_err_pid123.log").write_text("cwd fallback")
            errors = smoke.collect_logs(work, output)
            self.assertEqual(2, len(errors))
            self.assertIn("Rotated crash", errors[0])
            self.assertTrue((output / "app.log.1").exists())
            self.assertTrue((output / "0-hs_err_pid123.log").exists())

    def test_windows_without_a_desktop_session_skips_without_launching(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "out"
            with patch.object(smoke, "screenshot", side_effect=RuntimeError("handle is invalid")), \
                    patch.object(smoke.subprocess, "Popen") as popen, patch("builtins.print") as printed:
                self.assertEqual(0, smoke.smoke(Path(temporary) / "Metrolist.zip", output, "win32"))
            popen.assert_not_called()
            self.assertIn("skipped", (output / "result.txt").read_text())
            self.assertTrue(printed.call_args.args[0].startswith("::warning::"))

    def test_native_service_runner_uses_backend_probe_instead_of_skipping(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(smoke, "desktop_available", return_value=False), patch.object(smoke, "native_backend_smoke", return_value=1) as backend:
            self.assertEqual(1, smoke.smoke(Path("native.zip"), Path(directory), system="win32", native=True))
            backend.assert_called_once()

    def test_backend_probe_rejects_registration_errors_in_redirected_native_logs(self):
        for missing in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory);output=root/"evidence";output.mkdir()
                def run(command, **options):
                    options["stdout"].write(b"[NativeBackendSmoke] passed\n")
                    logs=Path(options["env"]["XDG_STATE_HOME"])/"metrolist/logs"
                    if missing:
                        (logs/"native_err_pid1.log").write_text("MissingJNIRegistrationError")
                    return Mock(returncode=0)
                with patch.object(smoke,"packaged_command",return_value=nullcontext(["Metrolist.exe"])), patch.object(smoke.subprocess,"run",side_effect=run):
                    self.assertEqual(int(missing),smoke.native_backend_smoke(Path("native.zip"),output,"win32"))

    def test_windows_uses_the_extracted_zip_launcher(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            package = work / "portable.zip"
            with zipfile.ZipFile(package, "w") as archive:
                archive.writestr("Metrolist.exe", b"launcher")
                archive.writestr("runtime/bin/server/jvm.dll", b"runtime")
            with smoke.packaged_command(package, work, "win32", None) as command:
                self.assertEqual([str(work / "app/Metrolist.exe")], command)
                self.assertEqual(b"launcher", Path(command[0]).read_bytes())
                self.assertTrue((work / "app/runtime/bin/server/jvm.dll").is_file())

    def test_macos_launches_from_final_dmg_and_detaches_on_failure(self):
        for failure in (False, True):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                work = Path(directory)
                with patch.object(smoke.subprocess, "run") as run:
                    try:
                        with smoke.packaged_command(work / "final.dmg", work, "darwin", None) as command:
                            self.assertEqual([str(work / "dmg/Metrolist.app/Contents/MacOS/Metrolist")], command)
                            if failure:
                                raise RuntimeError("launch failed")
                    except RuntimeError:
                        self.assertTrue(failure)
                self.assertEqual(["attach", "detach"], [call.args[0][1] for call in run.call_args_list])
                self.assertIn(str(work / "final.dmg"), run.call_args_list[0].args[0])

    def test_screenshots_use_platform_tools_and_propagate_capture_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "desktop.png"
            for system, tool in (("linux", "scrot"), ("win32", "powershell.exe"), ("darwin", "screencapture")):
                with self.subTest(system=system), patch.object(smoke.subprocess, "run") as run:
                    run.side_effect = lambda *args, **kwargs: destination.write_bytes(b"screenshot")
                    smoke.screenshot(destination, system)
                    self.assertEqual(tool, run.call_args.args[0][0])
                    self.assertTrue(run.call_args.kwargs["check"])
                    run.side_effect = subprocess.CalledProcessError(1, tool)
                    with self.assertRaises(subprocess.CalledProcessError):
                        smoke.screenshot(destination, system)
                    destination.write_bytes(b"stale screenshot")
                    run.side_effect = None
                    with self.assertRaisesRegex(RuntimeError, "screenshot is missing or empty"):
                        smoke.screenshot(destination, system)

    def test_windows_requests_window_close_before_waiting(self):
        process = Mock(pid=123, returncode=0)
        process.poll.return_value = 0
        with patch.object(smoke.subprocess, "run") as run:
            smoke.quit_app(process, r"C:\app\Metrolist.exe", "win32")
        script = run.call_args.args[0][-1]
        self.assertIn("CloseMainWindow()", script)
        self.assertIn(r"C:\app\Metrolist.exe", script)
        self.assertNotIn("Stop-Process", script)
        process.wait.assert_called_once_with(timeout=smoke.SHUTDOWN_SECONDS)

    def test_windows_shutdown_timeout_fails_and_kills_the_process_tree(self):
        for trace in (False, True):
            with self.subTest(java_trace=trace):
                process = Mock(pid=123)
                process.poll.return_value = None
                process.wait.side_effect = [subprocess.TimeoutExpired("Metrolist.exe", 2), None]
                with patch.object(smoke.subprocess, "run") as run:
                    with self.assertRaises(subprocess.TimeoutExpired):
                        smoke.quit_app(process, r"C:\app\Metrolist.exe", "win32", java_trace=trace)
                self.assertEqual(["taskkill", "/PID", "123", "/T", "/F"], run.call_args.args[0])
                if trace:
                    self.assertIn("Get-Process -Id 123", run.call_args_list[0].args[0][-1])

    def test_script_changes_trigger_x64_self_check_without_new_runners(self):
        sys.path.insert(0, str(ROOT / ".github/scripts"))
        from ci_paths import classify
        workflow = (ROOT / ".github/workflows/build-desktop.yml").read_text()
        for event in ("push", "pull_request"):
            block = re.search(rf"(?ms)^  {event}:\n(.*?)(?=^  \w|^\w|\Z)", workflow)[1]
            self.assertNotIn("paths:", block)
            for path in ("scripts/desktop_smoke.py", "scripts/test_desktop_smoke.py"):
                self.assertTrue(classify([path])["desktop"], path)
        verify = (ROOT / ".github/workflows/reusable-desktop-verify.yml").read_text()
        step = verify.split("      - name: Check local CI scripts\n")[1].split("      - name:")[0]
        self.assertIn("if: matrix.arch == 'x64'", step)
        self.assertIn("python3 scripts/test_desktop_smoke.py", step)
        for system in ("linux", "windows", "macos"):
            package = (ROOT / f".github/workflows/reusable-desktop-{system}.yml").read_text()
            self.assertIn("timeout-minutes: 3", package)
            self.assertIn("scripts/desktop_smoke.py", package)
            self.assertIn("if: always() && steps.smoke.outcome != 'skipped'", package)


if __name__ == "__main__":
    unittest.main()
