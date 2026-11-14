"""Keep build logs and provenance, without caching profiles or application outputs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from package import archive


def collect(work, target):
    evidence = work / 'evidence'
    evidence.mkdir(parents=True, exist_ok=True)
    for name in ('build.log', 'java-glue.log', 'builder-time.json', 'dependencies.txt'):
        if (work / name).is_file():
            shutil.copy2(work / name, evidence / name)
    artifacts = {}
    for path in (work / 'packages').glob('*'):
        if path.is_file():
            with path.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            artifacts[path.name] = {'bytes': path.stat().st_size, 'sha256': digest}
    pins = json.loads((Path(__file__).resolve().parents[2] / 'scripts/graalvm-pins.json').read_text())
    info = {'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
            'platform': target, 'graalvm': pins['version'], 'collector': 'serial', 'optimization': '-Os',
            'heap_min_mib': 32, 'heap_max_mib': 384, 'strict_registration': True,
            'artifacts': artifacts, 'run_url': os.environ.get('GITHUB_SERVER_URL', '') + '/' + os.environ.get('GITHUB_REPOSITORY', '') + '/actions/runs/' + os.environ.get('GITHUB_RUN_ID', '')}
    result = evidence / 'smoke/result.txt'
    info['smoke_result'] = result.read_text() if result.is_file() else 'Smoke did not complete'
    info['gui_verified'] = info['smoke_result'].strip() == 'Desktop launch smoke test passed'
    (evidence / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')
    archive(evidence, work / 'native-evidence.zip')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--platform', required=True)
    args = parser.parse_args()
    collect(args.work.resolve(), args.platform)
