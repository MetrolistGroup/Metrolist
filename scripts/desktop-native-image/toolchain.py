"""Install a checksum-verified builder archive. Cache archives, never extracted JDKs."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def verify(path, digest):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == digest


def extract(archive, destination):
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            for name in source.namelist():
                if not (destination / name).resolve().is_relative_to(destination.resolve()):
                    raise ValueError('Archive path escapes the toolchain directory')
            source.extractall(destination)
    else:
        with tarfile.open(archive) as source:
            source.extractall(destination, filter='data')


def install(platform, cache, destination, manifest=None):
    pins = manifest or json.loads((ROOT / 'scripts/graalvm-pins.json').read_text())
    pin = pins['platforms'][platform]
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / (pin['sha256'] + ('.zip' if pin['url'].endswith('.zip') else '.tar.gz'))
    if not archive.is_file() or not verify(archive, pin['sha256']):
        archive.unlink(missing_ok=True)
        temporary = archive.with_suffix(archive.suffix + '.download')
        try:
            with urllib.request.urlopen(pin['url'], timeout=120) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output)
            if not verify(temporary, pin['sha256']):
                raise ValueError('GraalVM archive checksum mismatch')
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as directory:
        unpacked = Path(directory)
        extract(archive, unpacked)
        homes = [p.parent for p in unpacked.rglob('release') if (p.parent / 'bin/java').is_file() or (p.parent / 'bin/java.exe').is_file()]
        if len(homes) != 1:
            raise ValueError('GraalVM archive must contain one JDK')
        roots = list(unpacked.iterdir())
        if len(roots) != 1 or not roots[0].is_dir():
            raise ValueError('GraalVM archive must have one root directory')
        relative_home = homes[0].relative_to(roots[0])
        backup = unpacked / 'previous-jdk'
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
    args = parser.parse_args()
    if not __import__('os').environ.get('GITHUB_ACTIONS') and any(p.resolve().is_relative_to('/tmp') for p in (args.cache, args.destination)):
        parser.error('Keep GraalVM archives and JDKs on disk, outside /tmp')
    home = install(args.platform, args.cache.resolve(), args.destination.resolve())
    print(home)
    import os
    if os.environ.get('GITHUB_ENV'):
        with open(os.environ['GITHUB_ENV'], 'a') as stream:
            stream.write(f'GRAALVM_HOME={home}\n')


if __name__ == '__main__':
    main()
