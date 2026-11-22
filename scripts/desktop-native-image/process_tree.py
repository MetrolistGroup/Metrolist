#!/usr/bin/env python3
"""Sample CPU and memory for a process and all of its descendants, including WebKit helpers.

Attach to a running app with --pid, or wait for one with --match. Write the current scenario name
to --phase-file to label samples, for example "idle", "mint", "reset", "playing" or "skip".
"""

import argparse
import json
import os
from pathlib import Path
import time

MEMORY_FIELDS = ("Rss:", "Pss:", "Pss_Anon:", "Pss_File:", "Pss_Shmem:", "SwapPss:")
TICK = os.sysconf("SC_CLK_TCK")


def _stat(path):
    # comm truncates at 15 bytes, which can split a UTF-8 character; strict decoding must not abort a profile.
    name, rest = Path(path).read_text(encoding="utf-8", errors="replace").rsplit(")", 1)
    fields = rest.split()
    return name.split("(", 1)[1], (int(fields[11]) + int(fields[12])) / TICK, int(fields[19])


GONE = (FileNotFoundError, ProcessLookupError, PermissionError)


def descendants(pid):
    # A set, because an exiting thread's children move to a sibling thread and can be listed twice.
    found, pending = set(), [pid]
    while pending:
        current = pending.pop()
        if current in found:
            continue
        found.add(current)
        try:
            tasks = list(Path(f"/proc/{current}/task").iterdir())
        except GONE:
            continue
        for task in tasks:
            try:
                pending += [int(child) for child in (task / "children").read_text().split()]
            except GONE:
                pass
    return sorted(found)


def process(pid):
    name, cpu, start = _stat(f"/proc/{pid}/stat")
    memory = {}
    for line in Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines():
        if line.startswith(MEMORY_FIELDS):
            key, value, _ = line.split()
            memory[key.rstrip(":").lower().replace("swappss", "swap_pss") + "_kib"] = int(value)
    return {"pid": pid, "start_ticks": start, "name": name, "cpu_seconds": cpu, **memory}


def threads(pid):
    """Cumulative CPU seconds per thread, keyed by thread ID and start time so recycled IDs stay separate."""
    totals = {}
    try:
        tasks = list(Path(f"/proc/{pid}/task").iterdir())
    except GONE:
        return totals
    for task in tasks:
        try:
            name, cpu, start = _stat(task / "stat")
        except GONE:
            continue
        totals[(int(task.name), start)] = (name, cpu)
    return totals


def thread_cpu(first, last):
    """CPU per thread name between two snapshots. Threads that exited in between are not visible."""
    by_name = {}
    for key, (name, cpu) in last.items():
        by_name[name] = by_name.get(name, 0) + cpu - first.get(key, (name, 0))[1]
    return by_name


def sample(root):
    processes = []
    for pid in descendants(root):
        try:
            processes.append(process(pid))
        except GONE:
            pass  # The process exited between listing and reading.
    keys = {key for entry in processes for key in entry if key.endswith(("_kib", "_seconds")) and key != "start_ticks"}
    total = {key: sum(entry.get(key, 0) for entry in processes) for key in sorted(keys)}
    return {"time": time.monotonic(), "total": total, "processes": processes}


def summarize(samples):
    """Per phase: CPU percent of one core across the phase, and min/mean/max tree PSS."""
    phases = {samples[0].get("phase", ""): {"cpu": 0.0, "seconds": 0.0, "pss": [samples[0]["total"].get("pss_kib", 0) / 1024]}}
    for previous, current in zip(samples, samples[1:]):
        phase = phases.setdefault(current.get("phase", ""), {"cpu": 0.0, "seconds": 0.0, "pss": []})
        # PID plus start time identifies a process across PID reuse. A process absent from the previous
        # sample started since then, so all of its CPU time belongs to this interval. Polling cannot see
        # CPU used by a helper after the last sample in which it was still alive.
        previous_cpu = {(entry["pid"], entry["start_ticks"]): entry["cpu_seconds"] for entry in previous["processes"]}
        phase["cpu"] += sum(entry["cpu_seconds"] - previous_cpu.get((entry["pid"], entry["start_ticks"]), 0)
                            for entry in current["processes"])
        phase["seconds"] += current["time"] - previous["time"]
        phase["pss"].append(current["total"].get("pss_kib", 0) / 1024)
    return {name: {"seconds": round(value["seconds"], 1),
                   "cpu_percent_one_core": round(100 * value["cpu"] / value["seconds"], 1) if value["seconds"] else None,
                   "pss_mib_min": round(min(value["pss"]), 1), "pss_mib_mean": round(sum(value["pss"]) / len(value["pss"]), 1),
                   "pss_mib_max": round(max(value["pss"]), 1)}
            for name, value in phases.items()}


def find(name, timeout):
    """Newest process whose command name or executable basename equals name."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        matches = []
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit() or int(entry.name) == os.getpid():
                continue
            try:
                command, _, start = _stat(entry / "stat")
                executable = os.path.basename(os.readlink(entry / "exe"))
            except GONE:
                continue
            if name in (command, executable):
                matches.append((start, int(entry.name)))
        if matches:
            return max(matches)[1]
        time.sleep(0.2)
    raise SystemExit(f"No process named {name!r}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--pid", type=int)
    target.add_argument("--match", help="Command or executable name of the root process; the newest match wins")
    parser.add_argument("--seconds", type=float, required=True)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--phase-file", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    root = args.pid or find(args.match, 60)
    samples, start = [], time.monotonic()
    first_threads = threads(root)
    while time.monotonic() - start < args.seconds and Path(f"/proc/{root}").exists():
        entry = sample(root)
        if not entry["processes"]:
            break  # The app exited after the liveness check.
        if args.phase_file and args.phase_file.exists():
            entry["phase"] = args.phase_file.read_text().strip()
        samples.append(entry)
        time.sleep(max(0.0, args.interval - (time.monotonic() - entry["time"])))
    last_threads = threads(root)
    busiest = sorted(((name, round(cpu, 2)) for name, cpu in thread_cpu(first_threads, last_threads).items()),
                     key=lambda item: -item[1])[:25]
    result = {"root": root, "interval": args.interval, "summary": summarize(samples) if len(samples) > 1 else {},
              "thread_cpu_seconds": dict(busiest), "samples": samples}
    args.out.write_text(json.dumps(result, indent=1) + "\n")
    print(json.dumps({key: result[key] for key in ("summary", "thread_cpu_seconds")}, indent=2))


if __name__ == "__main__":
    main()
