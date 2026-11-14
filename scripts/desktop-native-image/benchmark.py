#!/usr/bin/env python3
"""Compare two cold/warm startup pairs and local/YouTube playback, one process at a time."""

import argparse
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=Path("/tmp/r6-native-image"))
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--jvm-config", type=Path, required=True)
    parser.add_argument("--local-media", type=Path, required=True)
    parser.add_argument("--youtube", required=True)
    parser.add_argument("--name", default="bench-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", args.name):
        parser.error("--name must contain only letters, digits, underscores and hyphens")
    work = args.work.resolve()
    if work == Path("/tmp") or not work.is_relative_to("/tmp"):
        parser.error("--work must be a dedicated directory under /tmp")
    profile_name = args.name + "-comparison"
    profile = work / "profiles" / profile_name
    assert profile.resolve().is_relative_to(work / "profiles")
    output = work / (args.name + ".results.json")
    if output.exists():
        parser.error("This benchmark name already has results; choose another name")
    results = []
    runner = Path(__file__).with_name("run.py")

    def run(runtime, workload, cache, reset):
        if reset and profile.exists():
            shutil.rmtree(profile)
        label = f"{args.name}-{len(results) + 1:02d}-{runtime}-{workload}-{cache}"
        command = ["systemd-run", "--user", "--scope", "--quiet",
                   f"--unit=metrolist-r6-measure-{os.getpid()}-{len(results) + 1}",
                   "-p", "MemoryMax=2G", "-p", "MemorySwapMax=0", "-p", "CPUWeight=50",
                   sys.executable, str(runner), runtime, "--work", str(work), "--name", label,
                   "--profile", profile_name, "--seconds", "40" if workload == "idle" else "75"]
        if runtime == "jvm":
            command += ["--java", str(args.java.resolve()), "--jvm-config", str(args.jvm_config.resolve())]
        if workload != "idle":
            media = str(args.local_media.resolve()) if workload == "local" else args.youtube
            command += ["--media", media]
        with (work / (label + ".driver.log")).open("w") as log:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        path = work / label / "result.json"
        result = json.loads(path.read_text()) if path.exists() else {}
        result.update(label=label, runtime=runtime, workload=workload, cache=cache, runner_exit=completed.returncode)
        results.append(result)
        output.write_text(json.dumps(results, indent=2) + "\n")
        print(label, "exit", completed.returncode, flush=True)

    # Exclude lane builds from the comparison, just as the native builder does.
    with open("/tmp/metrolist-gradle.lock", "w") as gradle_lock:
        fcntl.flock(gradle_lock, fcntl.LOCK_EX)
        for order in (("native", "jvm"), ("jvm", "native")):
            for runtime in order:
                run(runtime, "idle", "cold", reset=True)
                run(runtime, "idle", "warm", reset=False)
        for workload, order in (("local", ("jvm", "native")), ("youtube", ("native", "jvm"))):
            for runtime in order:
                run(runtime, workload, "fresh", reset=True)
    return int(any(result["runner_exit"] != 0 for result in results))


if __name__ == "__main__":
    sys.exit(main())
