"""Record elapsed time and peak builder memory without a platform-specific time binary."""

import json
from pathlib import Path
import subprocess
import sys
import time


def main():
    destination = Path(sys.argv[1])
    started = time.monotonic()
    command = sys.argv[2:]
    if sys.platform == 'win32' and command[0].lower().endswith('.cmd'):
        if any('%' in arg or '"' in arg for arg in command):
            raise ValueError('Windows builder paths must not contain percent signs or quotes')
        process = subprocess.Popen(' '.join('"' + arg + '"' for arg in command), shell=True)
    else:
        process = subprocess.Popen(command)
    result = process.wait()
    metrics = {'exit_code': result, 'elapsed_seconds': time.monotonic() - started}
    if sys.platform != 'win32':
        import resource
        usage = resource.getrusage(resource.RUSAGE_CHILDREN)
        metrics.update(peak_rss_kib=usage.ru_maxrss / 1024 if sys.platform == 'darwin' else usage.ru_maxrss,
                       user_cpu_seconds=usage.ru_utime, system_cpu_seconds=usage.ru_stime)
    if sys.platform == 'linux':
        group = Path('/proc/self/cgroup').read_text().strip().split(':', 2)[2]
        peak = Path('/sys/fs/cgroup') / group.lstrip('/') / 'memory.peak'
        if peak.is_file():
            metrics['scope_peak_bytes'] = int(peak.read_text())
    # Windows Native Image reports peak RSS in build.log; Python has no resource module there.
    destination.write_text(json.dumps(metrics, indent=2) + '\n')
    return result


if __name__ == '__main__':
    sys.exit(main())
