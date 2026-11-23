#!/usr/bin/env python3
"""Install a checksum-verified JetBrains Runtime archive for Linux desktop packaging."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def verify(path, digest):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha512').hexdigest() == digest


def extract(archive, destination):
    with tarfile.open(archive) as source:
        source.extractall(destination, filter='data')


def install(platform, cache, destination, manifest=None):
    pins = manifest or json.loads((ROOT / 'scripts/jbr-pins.json').read_text(encoding='utf-8'))
    pin = pins['platforms'][platform]
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / (pin['sha512'][:32] + '.tar.gz')
    if not archive.is_file() or not verify(archive, pin['sha512']):
        archive.unlink(missing_ok=True)
        temporary = archive.with_suffix(archive.suffix + '.download')
        try:
            with urllib.request.urlopen(pin['url'], timeout=120) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output)
            if not verify(temporary, pin['sha512']):
                raise ValueError('JetBrains Runtime archive checksum mismatch')
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as directory:
        unpacked = Path(directory)
        extract(archive, unpacked)
        homes = [p.parent for p in unpacked.rglob('release') if (p.parent / 'bin/java').is_file()]
        if len(homes) != 1:
            raise ValueError('JetBrains Runtime archive must contain one JDK')
        if not (homes[0] / 'lib/libawt_wlawt.so').is_file():
            raise ValueError('JetBrains Runtime archive is missing lib/libawt_wlawt.so')
        roots = list(unpacked.iterdir())
        if len(roots) != 1 or not roots[0].is_dir():
            raise ValueError('JetBrains Runtime archive must have one root directory')
        relative_home = homes[0].relative_to(roots[0])
        backup = unpacked / 'previous-jbr'
        if destination.exists():
            destination.rename(backup)
        try:
            roots[0].rename(destination)
        except OSError:
            if backup.exists():
                backup.rename(destination)
            raise
    return destination / relative_home


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--env-var', default='PACKAGING_JAVA_HOME')
    args = parser.parse_args()
    home = install(args.platform, args.cache.resolve(), args.destination.resolve())
    print(home)
    if os.environ.get('GITHUB_ENV'):
        with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as stream:
            stream.write(f'{args.env_var}={home}\n')


if __name__ == '__main__':
    main()
