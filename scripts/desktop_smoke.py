#!/usr/bin/env python3
"""Launch a desktop package with a disposable profile and retain CI evidence."""

import argparse
import base64
from contextlib import contextmanager, nullcontext
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import zipfile

STARTUP_SECONDS = 45
# Main.kt's runBoundedShutdown halts after two seconds; allow time for the JVM and launcher to exit.
SHUTDOWN_SECONDS = 10
RECORD = re.compile(r"^\[[^\n\]]+\] \[([A-Z]{4})\] \[([^\n\]]+)\] ", re.MULTILINE)
FATAL = re.compile(
    r"\b(?:fatal|crash(?:ed)?|uncaught(?:exception)?|UnsatisfiedLinkError|"
    r"NoClassDefFoundError|ExceptionInInitializerError)\b", re.IGNORECASE
)


def fatal_records(text):
    records = list(RECORD.finditer(text))
    failures = []
    for index, record in enumerate(records):
        end = records[index + 1].start() if index + 1 < len(records) else len(text)
        entry = text[record.start():end].strip()
        if record[1] in ("ERRO", "FATA") and FATAL.search(entry):
            failures.append(entry)
    return failures


def profile_environment(profile):
    env = os.environ.copy()
    for kind in ("CONFIG", "DATA", "STATE", "CACHE"):
        directory = profile / kind.lower()
        directory.mkdir(parents=True)
        env[f"XDG_{kind}_HOME"] = str(directory)
    config = profile / "config/metrolist"
    config.mkdir()
    (config / "settings.properties").write_text(
        "desktopOnboardingComplete=true\ndesktopOnboardingInitialized=true\n"
        "sentryEnabled=false\nenableLogging=true\n", encoding="utf-8"
    )
    logs = profile / "state/metrolist/logs"
    logs.mkdir(parents=True)
    # XdgUtils honors these paths on every OS. Isolate legacy home-directory files too.
    # Setting ErrorFile now avoids the app's crash-log configuration relaunch.
    env["_JAVA_OPTIONS"] = f'"-Duser.home={profile}" "-XX:ErrorFile={logs}/hs_err_pid%p.log"'
    return env


@contextmanager
def packaged_command(package, work, system, stdout):
    if system == "linux":
        # The AppImage runtime extracts and runs without requiring a FUSE mount.
        yield [str(package), "--appimage-extract-and-run"]
    elif system == "win32":
        app = work / "app"
        with zipfile.ZipFile(package) as archive:
            archive.extractall(app)
        yield [str(app / "Metrolist.exe")]
    elif system == "darwin" and package.suffix == ".zip":
        app = work / "app"
        with zipfile.ZipFile(package) as archive:
            archive.extractall(app)
            for entry in archive.infolist():
                if entry.external_attr >> 16 and not entry.is_dir():
                    (app / entry.filename).chmod((entry.external_attr >> 16) & 0o777)
        yield [str(app / "Metrolist.app/Contents/MacOS/Metrolist")]
    elif system == "darwin":
        mount = work / "dmg"
        mount.mkdir()
        try:
            subprocess.run(
                ["hdiutil", "attach", str(package), "-mountpoint", str(mount), "-readonly", "-nobrowse"],
                check=True, timeout=30, stdout=stdout, stderr=subprocess.STDOUT,
            )
            yield [str(mount / "Metrolist.app/Contents/MacOS/Metrolist")]
        finally:
            subprocess.run(
                ["hdiutil", "detach", "-force", str(mount)], check=True, timeout=15,
                stdout=stdout, stderr=subprocess.STDOUT,
            )
    else:
        raise ValueError(f"Unsupported smoke-test platform: {system}")


def powershell(script):
    return ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "$ErrorActionPreference = 'Stop'; " + script]


def screenshot(destination, system):
    destination.unlink(missing_ok=True)
    if system == "win32":
        path = str(destination).replace("'", "''")
        command = powershell(
            "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
            "$bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen; "
            "$image = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height; "
            "$graphics = [System.Drawing.Graphics]::FromImage($image); "
            "try { $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size); "
            f"$image.Save('{path}', [System.Drawing.Imaging.ImageFormat]::Png) "
            "} finally { $graphics.Dispose(); $image.Dispose() }"
        )
    elif system == "darwin":
        command = ["screencapture", "-x", str(destination)]
    else:
        command = ["scrot", str(destination)]
    subprocess.run(command, check=True, timeout=15)
    if not destination.is_file() or destination.stat().st_size == 0:
        raise RuntimeError("Desktop screenshot is missing or empty")


def preview(source, system):
    """Return a small JPEG of the screenshot for the job log, or None. Best effort."""
    target = source.with_name("preview.jpg")
    if system == "darwin":
        command = ["sips", "-Z", "960", "-s", "format", "jpeg", str(source), "--out", str(target)]
    elif system == "win32":
        src, dst = (str(p).replace("'", "''") for p in (source, target))
        command = powershell(
            "Add-Type -AssemblyName System.Drawing; "
            f"$image = [System.Drawing.Image]::FromFile('{src}'); "
            "$scale = 960 / $image.Width; "
            "$small = New-Object System.Drawing.Bitmap $image, 960, ([int]($image.Height * $scale)); "
            f"try {{ $small.Save('{dst}', [System.Drawing.Imaging.ImageFormat]::Jpeg) }} "
            "finally { $small.Dispose(); $image.Dispose() }"
        )
    elif shutil.which("convert"):
        command = ["convert", str(source), "-resize", "960x", "-quality", "70", str(target)]
    else:
        return None
    try:
        subprocess.run(command, check=True, timeout=30, capture_output=True)
    except Exception:
        return None
    return target if target.is_file() and target.stat().st_size > 0 else None


def report(output, errors, system):
    """Print evidence to the job log, since artifact uploads can fail on a full quota."""
    print("Desktop launch smoke test " + ("failed" if errors else "passed"))
    if errors:
        for name in ("stdout.log", "app.log"):
            log = output / name
            if log.is_file():
                tail = log.read_text(encoding="utf-8", errors="replace").splitlines()[-60:]
                print(f"--- last {len(tail)} lines of {name} ---")
                print("\n".join(tail))
        for crash in sorted(output.glob("*-hs_err_pid*.log")):
            head = crash.read_text(encoding="utf-8", errors="replace").splitlines()[:400]
            print(f"--- first {len(head)} lines of {crash.name} ---")
            print("\n".join(head))
    shot = output / "desktop.png"
    image = preview(shot, system) if shot.is_file() else None
    if image is not None:
        # Decode with: sed -n '/BEGIN SCREENSHOT/,/END SCREENSHOT/p' log | sed '1d;$d' | base64 -d
        print("--- BEGIN SCREENSHOT (base64 JPEG) ---")
        print(base64.encodebytes(image.read_bytes()).decode("ascii"), end="")
        print("--- END SCREENSHOT ---")
    sys.stdout.flush()


def wait_for_startup(process):
    deadline = time.monotonic() + STARTUP_SECONDS
    while True:
        if process.poll() is not None:
            raise RuntimeError(f"Packaged app exited during startup with code {process.returncode}")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        time.sleep(min(1, remaining))


def quit_app(process, launcher, system, java_trace=False):
    try:
        if system == "win32":
            if java_trace:
                close = f"$app = Get-Process -Id {process.pid}; if (-not $app.CloseMainWindow()) {{ throw 'Window close failed' }}"
            else:
                path = launcher.replace("'", "''")
                # jpackage can keep a parent launcher waiting for the GUI process.
                close = (
                    f"$apps = @(Get-Process Metrolist | Where-Object {{ $_.Path -eq '{path}' -and $_.MainWindowHandle -ne 0 }}); "
                    "if ($apps.Count -eq 0) { throw 'Packaged app has no main window to close' }; "
                    "foreach ($app in $apps) { if (-not $app.CloseMainWindow()) { throw 'Window close failed' } }"
                )
            subprocess.run(powershell(close), check=True, timeout=15)
        elif system == "linux":
            # Extract-and-run waits for AppRun in a child. Let it reap and clean up.
            children = Path(f"/proc/{process.pid}/task/{process.pid}/children").read_text().split()
            if len(children) != 1:
                raise RuntimeError(f"Expected one AppImage child, found {len(children)}")
            pidfd = os.pidfd_open(int(children[0]))
            try:
                signal.pidfd_send_signal(pidfd, signal.SIGTERM)
                if not select.select([pidfd], [], [], SHUTDOWN_SECONDS)[0]:
                    raise RuntimeError("Packaged app exceeded the shutdown watchdog")
            finally:
                os.close(pidfd)
        else:
            os.killpg(process.pid, signal.SIGTERM)
        # AppImage removes its extracted files after the app exits.
        process.wait(timeout=15 if system == "linux" else SHUTDOWN_SECONDS)
        if process.returncode != 0:
            raise RuntimeError(f"Packaged app exited during shutdown with code {process.returncode}")
    finally:
        if process.poll() is None:
            if system == "win32":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=True, timeout=10)
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def collect_logs(work, output, native=False):
    errors = []
    logs = work / "profile/state/metrolist/logs"
    if not (logs / "app.log").is_file():
        errors.append("app.log is missing; startup did not reach the logger")
    for log in sorted(logs.glob("app.log*")):
        shutil.copy2(log, output / log.name)
        errors.extend(f"{log.name}: {record}" for record in fatal_records(log.read_text(encoding="utf-8", errors="replace")))
    for index, crash in enumerate(work.rglob("hs_err_pid*.log")):
        shutil.copy2(crash, output / f"{index}-{crash.name}")
        errors.append(f"HotSpot crash report: {crash.name}")
    if native:
        for log in work.rglob("native_err_pid*.log"):
            shutil.copy2(log, output / log.name)
    return errors


def desktop_available(output, system):
    """Windows service runners have no interactive desktop, so no GUI app can start there."""
    if system != "win32":
        return True
    probe = output / "desktop-probe.png"
    try:
        screenshot(probe, system)
        return True
    except Exception:
        return False
    finally:
        probe.unlink(missing_ok=True)


def native_backend_smoke(package, output, system):
    with tempfile.TemporaryDirectory(prefix="metrolist-native-backend-") as directory:
        work = Path(directory)
        profile = work / "profile"
        env = profile_environment(profile)
        env.pop("_JAVA_OPTIONS", None)
        env.update(HOME=str(profile), USERPROFILE=str(profile))
        with (output / "stdout.log").open("wb") as log:
            with packaged_command(package, work, system, log) as command:
                result = subprocess.run([*command, f"-Duser.home={profile}", f"-Djava.io.tmpdir={work}",
                    "-Djava.awt.headless=true", "-Dmetrolist.native.backendSmoke=true",
                    "-XX:MissingRegistrationReportingMode=Exit"], env=env, cwd=work,
                    stdout=log, stderr=subprocess.STDOUT, timeout=60)
        for log in work.rglob("native_err_pid*.log"):
            shutil.copy2(log, output / log.name)
        text = "\n".join(log.read_text(encoding="utf-8", errors="replace") for log in output.glob("*.log"))
        passed = result.returncode == 0 and "[NativeBackendSmoke] passed" in text and "Unable to configure native crash log:" not in text and not re.search(r"Missing\w*RegistrationError", text)
        message = "Native backend smoke " + ("passed" if passed else "failed") + "; GUI unavailable and unverified"
        (output / "result.txt").write_text(message + "\n")
        print(message)
        return 0 if passed else 1


def smoke(package, output, system=sys.platform, native=False, trace_java=None, trace_classpath=None, trace_agent=None, resources=None):
    output.mkdir(parents=True, exist_ok=True)
    if not desktop_available(output, system):
        message = "Desktop launch smoke test skipped: this runner has no interactive desktop session"
        (output / "result.txt").write_text(message + "\n", encoding="utf-8")
        print(f"::warning::{message}")
        return native_backend_smoke(package, output, system) if native else 0
    errors = []
    # A DMG that fails to detach must not hide the launch result behind a cleanup error.
    with tempfile.TemporaryDirectory(prefix="metrolist-smoke-", ignore_cleanup_errors=True) as temporary:
        work = Path(temporary)
        profile = work / "profile"
        env = profile_environment(profile)
        # A non-English locale reaches localized JDK and app resources that an English trace never loads.
        native_options = [f"-Duser.home={profile}", f"-Djava.io.tmpdir={work}", "-Duser.language=fr", "-Duser.country=FR",
                          "-XX:MissingRegistrationReportingMode=Exit"]
        if native and system != "win32":
            import resource
            resource.setrlimit(resource.RLIMIT_CORE, (0, resource.getrlimit(resource.RLIMIT_CORE)[1]))
        if native:
            env.pop("_JAVA_OPTIONS", None)
            env.update(HOME=str(profile), USERPROFILE=str(profile), DBUS_SESSION_BUS_ADDRESS="unix:path=/nonexistent-metrolist-native-smoke")
        if trace_java:
            trace_agent.mkdir(parents=True, exist_ok=True)
            command_context = nullcontext([str(trace_java), f"-agentlib:native-image-agent=config-merge-dir={trace_agent}",
                "--enable-native-access=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                f"-Dcompose.application.resources.dir={resources}", "-Xms32m", "-Xmx384m",
                "-cp", trace_classpath.read_text().strip(), "com.metrolist.desktop.MainKt"])
        with (output / "stdout.log").open("wb") as stdout:
            try:
                with (command_context if trace_java else packaged_command(package, work, system, stdout)) as command:
                    if native:
                        command = [*command, *native_options]
                    process = None
                    try:
                        process = subprocess.Popen(
                            command, cwd=work, env=env, stdout=stdout, stderr=subprocess.STDOUT,
                            start_new_session=system != "win32",
                        )
                        wait_for_startup(process)
                    except Exception as error:
                        errors.append(str(error))
                    finally:
                        try:
                            screenshot(output / "desktop.png", system)
                        except Exception as error:
                            errors.append(f"Screenshot failed: {error}")
                        if process is not None:
                            if process.poll() is not None:
                                errors.append(f"Packaged app exited before quit with code {process.returncode}")
                            else:
                                try:
                                    quit_app(process, command[0], system, java_trace=bool(trace_java))
                                except Exception as error:
                                    errors.append(f"Shutdown failed: {error}")
            except Exception as error:
                errors.append(f"Package preparation or cleanup failed: {error}")
        # Normal shutdown flushes the logger, including late failures.
        errors.extend(collect_logs(work, output, native=native))
        if native:
            registration = re.compile(r"Missing\w*RegistrationError")
            for log in output.glob("*.log"):
                if registration.search(log.read_text(encoding="utf-8", errors="replace")) or "Unable to configure native crash log:" in log.read_text(encoding="utf-8", errors="replace"):
                    errors.append(f"Missing Native Image reachability registration in {log.name}")
    (output / "result.txt").write_text("\n".join(errors) + "\n" if errors else "Desktop launch smoke test passed\n", encoding="utf-8")
    for error in errors:
        print(error, file=sys.stderr)
    report(output, errors, system)
    return 1 if errors else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--native", action="store_true")
    parser.add_argument("--trace-java", type=Path)
    parser.add_argument("--trace-classpath", type=Path)
    parser.add_argument("--trace-agent", type=Path)
    parser.add_argument("--resources", type=Path)
    args = parser.parse_args()
    if args.trace_java and (not args.trace_classpath or not args.trace_agent or not args.resources or args.native):
        parser.error("Tracing requires classpath, agent directory and resources, without --native")
    return smoke(args.package.resolve(), args.output.resolve(), native=args.native,
                 trace_java=args.trace_java, trace_classpath=args.trace_classpath,
                 trace_agent=args.trace_agent, resources=args.resources)


if __name__ == "__main__":
    sys.exit(main())
