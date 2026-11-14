#!/usr/bin/env python3
"""Run one Linux experiment on a private display and profile. Requires python-xlib and Pillow."""

import argparse
import contextlib
import json
import os
from pathlib import Path
import resource
import re
import signal
import socket
import subprocess
import sys
import time

from PIL import Image
from Xlib import X, display, protocol

from audio import private_audio


ROOT = Path(__file__).resolve().parents[2]
MAIN = "com.metrolist.desktop.experiment.NativeImageMainKt"


def disable_core_dump():
    resource.setrlimit(resource.RLIMIT_CORE, (0, resource.getrlimit(resource.RLIMIT_CORE)[1]))


def sample(pid):
    stat = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    memory = {}
    for line in Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines():
        if line.startswith(("Rss:", "Pss:", "Pss_Anon:", "Pss_File:", "Pss_Shmem:", "Swap:", "SwapPss:")):
            key, value, _ = line.split()
            name = key.rstrip(":").lower().replace("swappss", "swap_pss")
            memory[name + "_kib"] = int(value)
    for line in Path(f"/proc/{pid}/status").read_text().splitlines():
        if line.startswith("VmSwap:"):
            memory["vm_swap_kib"] = int(line.split()[1])
    group = Path(f"/proc/{pid}/cgroup").read_text().strip().split(":", 2)[2]
    cgroup = Path("/sys/fs/cgroup") / group.lstrip("/")
    memory["scope_swap_bytes"] = int((cgroup / "memory.swap.current").read_text())
    return {"time": time.monotonic(), "cpu_seconds": (int(stat[11]) + int(stat[12])) / os.sysconf("SC_CLK_TCK"), **memory}


def environment(profile):
    profile.mkdir(parents=True, exist_ok=True, mode=0o700)
    env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY"):
        env.pop(key, None)
    for kind in ("CONFIG", "CACHE", "DATA", "STATE"):
        path = profile / kind.lower()
        path.mkdir(exist_ok=True)
        env[f"XDG_{kind}_HOME"] = str(path)
    config = profile / "config/metrolist/settings.properties"
    config.parent.mkdir(exist_ok=True)
    if not config.exists():
        config.write_text("desktopOnboardingComplete=true\ndesktopOnboardingInitialized=true\n"
                          "sentryEnabled=false\nenableLogging=true\nenableDiscordIntegration=false\n")
    env.update(HOME=str(profile), DBUS_SESSION_BUS_ADDRESS="unix:path=/nonexistent-r6-bus",
               MALLOC_ARENA_MAX="2", METROLIST_STARTUP_TRACE="1", SKIKO_RENDER_API="SOFTWARE_COMPAT",
               LIBGL_ALWAYS_SOFTWARE="1", METROLIST_CURSOR_CONFIGURED="1")
    temporary = profile / "tmp"
    temporary.mkdir(exist_ok=True)
    env["TMPDIR"] = str(temporary)
    return env


@contextlib.contextmanager
def private_display(work, env):
    runtime = work / "runtime"
    runtime.mkdir(mode=0o700)
    env = dict(env, XDG_RUNTIME_DIR=str(runtime), WAYLAND_DISPLAY="r6-wayland")
    processes = []
    with contextlib.ExitStack() as stack:
        try:
            log = stack.enter_context((work / "weston.log").open("w"))
            weston = subprocess.Popen(["weston", "--backend=headless", "--renderer=pixman", "--socket=r6-wayland",
                                       "--idle-time=0", "--width=1280", "--height=900"], env=env, stdout=log, stderr=log)
            processes.append(weston)
            deadline = time.monotonic() + 15
            while not (runtime / "r6-wayland").exists():
                if weston.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Weston did not start; see weston.log")
                time.sleep(0.1)
            listener = stack.enter_context(socket.socket())
            listener.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            number = listener.getsockname()[1] - 6000
            log = stack.enter_context((work / "xwayland.log").open("w"))
            xwayland = subprocess.Popen(["Xwayland", f":{number}", "-geometry", "1280x900",
                                         "-listenfd", str(listener.fileno()), "-nolisten", "tcp", "-nolisten", "unix",
                                         "-nolock", "-ac", "-noreset"], env=env, pass_fds=(listener.fileno(),), stdout=log, stderr=log)
            processes.append(xwayland)
            env["DISPLAY"] = f"127.0.0.1:{number}"
            # Xwayland uses only our inherited TCP listener, without global X11 sockets or locks.
            time.sleep(1)
            if xwayland.poll() is not None:
                raise RuntimeError("Xwayland did not start; see xwayland.log")
            # python-xlib reads the observer's environment, not the child's private HOME.
            authority = os.environ.get("XAUTHORITY")
            os.environ["XAUTHORITY"] = str(runtime / "unused-xauthority")
            try:
                connection = display.Display(env["DISPLAY"])
            finally:
                if authority is None:
                    os.environ.pop("XAUTHORITY", None)
                else:
                    os.environ["XAUTHORITY"] = authority
            try:
                yield env, connection
            finally:
                connection.close()
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


def window_image(connection):
    for window in connection.screen().root.query_tree().children:
        if window.get_attributes().map_state != X.IsViewable:
            continue
        identity = " ".join(window.get_wm_class() or ()) + " " + str(window.get_wm_name() or "")
        if "metrolist" not in identity.lower():
            continue
        size = window.get_geometry()
        if size.width < 300 or size.height < 300:
            continue
        pixels = window.get_image(0, 0, size.width, size.height, X.ZPixmap, 0xffffffff)
        image = Image.frombytes("RGB", (size.width, size.height), pixels.data, "raw", "BGRX")
        return window, image
    return None, None


def release_options(config, profile):
    cache = profile / "cache/metrolist"
    logs = profile / "state/metrolist/logs"
    cache.mkdir(parents=True, exist_ok=True)
    logs.mkdir(parents=True, exist_ok=True)
    variables = {"APPDIR": str(config.resolve().parent), "METROLIST_CDS_CACHE": str(cache),
                 "METROLIST_JVM_ERROR_FILE": str(logs / "hs_err_pid%p.log")}

    def expand(value):
        def replace(match):
            if match[1] not in variables:
                raise ValueError(f"Unrecognized package variable: {match[1]}")
            return variables[match[1]]
        return re.sub(r"\$([A-Za-z_][A-Za-z0-9_]*)", replace, value)

    rows = config.read_text().splitlines()
    classpath = ":".join(expand(row.split("=", 1)[1]) for row in rows if row.startswith("app.classpath="))
    def exists(path):
        return any(Path(path[:-2]).glob("*.jar")) if path.endswith("/*") else Path(path).is_file()
    if not classpath or not all(exists(path) for path in classpath.split(":")):
        raise ValueError("Package classpath is missing or incomplete")
    options = [expand(row.split("=", 1)[1]) for row in rows if row.startswith("java-options=")]
    return options, classpath


def playback_summary(output):
    pattern = re.compile(r"^\[NativeImagePlayback\] positionMs=(\d+) durationMs=(\d+) playing=(true|false) ready=(true|false) error=(.*)$")
    points = [match.groups() for line in output.splitlines() if (match := pattern.match(line))]
    points = points[-30:]
    healthy = sum(playing == "true" and ready == "true" and error == "null" for _, _, playing, ready, error in points)
    progress = int(points[-1][0]) - int(points[0][0]) if len(points) > 1 else 0
    rate = progress / (len(points) - 1) if len(points) > 1 else 0
    return {"samples": len(points), "healthy_samples": healthy, "progress_ms": progress,
            "media_ms_per_probe_second": rate,
            "valid": healthy >= 25 and progress >= 20_000 and 750 <= rate <= 1250}


def command(args, profile):
    options = [f"-Duser.home={profile}", f"-Djava.io.tmpdir={profile / 'tmp'}",
               "-Djava.awt.headless=false", "-Dskiko.renderApi=SOFTWARE_COMPAT"]
    if args.media is not None:
        options += [f"-Dmetrolist.experiment.media={args.media}",
                    f"-Dmetrolist.experiment.trigger={args.work / args.name / 'playback.trigger'}"]
    text = (ROOT / "desktopApp/build.gradle.kts").read_text()
    block = text.split("val desktopMemoryJvmArgs =", 1)[1].split("interface ArchiveOps", 1)[0]
    memory_options = re.findall(r'"([^"\n]+)"', block)
    if args.mode == "native":
        heap = [option for option in memory_options if option.startswith(("-Xms", "-Xmx"))]
        return [str(args.work / "image/metrolist"), f"-XX:MissingRegistrationReportingMode={args.registration_mode}",
                f"-Djava.home={args.work / 'image'}", *heap, *options]
    if args.jvm_config:
        packaged, classpath = release_options(args.jvm_config, profile)
        options = packaged + options
        options += [f"-Xlog:cds=info:file={args.work / args.name / 'cds.log'}"]
    else:
        classpath = (args.work / "classpath.txt").read_text()
        options += memory_options
        options += ["--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"]
    if args.mode == "trace":
        metadata = args.work / "agent"
        metadata.mkdir(exist_ok=True)
        options += [f"-agentlib:native-image-agent=config-merge-dir={metadata}"]
    return [str(args.java), *options, "-cp", classpath, MAIN]


def run(args):
    disable_core_dump()
    profile = args.work / "profiles" / args.profile
    work = args.work / args.name
    work.mkdir(parents=True)
    env = environment(profile)
    result = {"mode": args.mode, "profile": args.profile, "samples": [], "first_paint_seconds": None}
    with private_display(work, env) as (env, connection), \
            (private_audio(work, env) if args.media else contextlib.nullcontext(env)) as env, \
            (work / "stdout.log").open("w") as log:
        invocation = command(args, profile)
        (work / "command.json").write_text(json.dumps(invocation, indent=2) + "\n")
        start = time.monotonic()
        result["launch_monotonic"] = start
        process = subprocess.Popen(invocation, env=env, stdout=log, stderr=log)
        (work / "pid").write_text(str(process.pid) + "\n")
        window = None
        try:
            while process.poll() is None and time.monotonic() - start < args.seconds:
                elapsed = time.monotonic() - start
                if args.media and elapsed >= 15 and result["first_paint_seconds"] is not None:
                    (work / "playback.trigger").touch(exist_ok=True)
                if result["first_paint_seconds"] is None:
                    window, image = window_image(connection)
                    if image is not None:
                        crop = image.crop((80, 120, image.width - 80, image.height - 80))
                        if len(crop.resize((200, 150)).getcolors(30001) or []) > 64:
                            result["first_paint_seconds"] = time.monotonic() - start
                            image.save(work / "first-paint.png")
                sample_start = 40 if args.media else 15
                if elapsed >= sample_start and (not result["samples"] or time.monotonic() - result["samples"][-1]["time"] >= 5):
                    result["samples"].append(sample(process.pid))
                time.sleep(0.05 if result["first_paint_seconds"] is None else 0.2)
            window, image = window_image(connection)
            result["windows"] = [
                {"id": child.id, "name": child.get_wm_name(), "class": child.get_wm_class()}
                for child in connection.screen().root.query_tree().children
            ]
            if image:
                image.save(work / "final.png")
            if process.poll() is None and window:
                event = protocol.event.ClientMessage(window=window, client_type=connection.intern_atom("WM_PROTOCOLS"),
                                                     data=(32, [connection.intern_atom("WM_DELETE_WINDOW"), X.CurrentTime, 0, 0, 0]))
                window.send_event(event)
                connection.flush()
            elif process.poll() is None:
                process.send_signal(signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                result["forced_shutdown"] = True
                process.kill()
                process.wait()
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            result["exit_code"] = process.returncode
            samples = result["samples"]
            if len(samples) > 1:
                result["cpu_percent_one_core"] = 100 * (samples[-1]["cpu_seconds"] - samples[0]["cpu_seconds"]) / (samples[-1]["time"] - samples[0]["time"])
            result["phase"] = "playing" if args.media else "idle"
            if args.media:
                log.flush()
                result["playback"] = playback_summary((work / "stdout.log").read_text())
                result["audio_linked"] = " -> r6-null:playback_" in (work / "audio/links.log").read_text()
                if not result["playback"]["valid"] or not result["audio_linked"]:
                    result["phase"] = "playback-failed"
            (work / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return int(result["exit_code"] != 0 or result["first_paint_seconds"] is None or
               result.get("forced_shutdown", False) or result["phase"] == "playback-failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("jvm", "trace", "native"))
    parser.add_argument("--work", type=Path, default=Path("/tmp/r6-native-image"))
    parser.add_argument("--java", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--profile", default="comparison")
    parser.add_argument("--seconds", type=float, default=40)
    parser.add_argument("--registration-mode", choices=("Exit", "Warn"), default="Exit")
    parser.add_argument("--media", help="Local audio file or YouTube URL; starts after a 15-second warmup")
    parser.add_argument("--jvm-config", type=Path, help="Use release package classpath, CDS and JVM options")
    args = parser.parse_args()
    args.work = args.work.resolve()
    if args.work == Path("/tmp") or not args.work.is_relative_to("/tmp"):
        parser.error("--work must be a dedicated directory under /tmp")
    if not all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", value) for value in (args.name, args.profile)):
        parser.error("--name and --profile must be single directory names")
    if not (args.work / "profiles" / args.profile).resolve().is_relative_to(args.work / "profiles"):
        parser.error("The profile must stay inside the experiment directory")
    if args.mode != "native" and args.java is None:
        parser.error("--java must name the JVM executable")
    if args.media and args.seconds < 60:
        parser.error("--media requires --seconds of at least 60")
    if args.jvm_config and args.mode != "jvm":
        parser.error("--jvm-config applies only to the JVM control")
    sys.exit(run(args))
