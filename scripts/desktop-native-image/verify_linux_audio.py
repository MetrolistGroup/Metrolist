"""Reject host audio libraries in Linux package directories, JARs and tarballs."""

import argparse
from fnmatch import fnmatchcase
import io
from pathlib import Path
import tarfile
import zipfile


def system_audio_library(name):
    return any(fnmatchcase(name.rsplit('/', 1)[-1], pattern) for pattern in (
        'libasound.so*', 'libpulse*.so*', 'libjack*.so*', 'libpipewire-*.so*', 'libspa-*.so*',
    ))


def verify_linux_audio(paths):
    def check(name):
        if system_audio_library(name):
            raise ValueError(f'Linux packages must use host audio libraries: {name}')

    def check_jar(source, label):
        with zipfile.ZipFile(source) as archive:
            for name in archive.namelist():
                check(f'{label}!/{name}')

    for path in map(Path, paths):
        if not path.exists():
            raise ValueError(f'Package input does not exist: {path}')
        for entry in path.rglob('*') if path.is_dir() else (path,):
            check(entry.as_posix())
            if entry.is_file() and entry.suffix == '.jar':
                check_jar(entry, entry)
            elif entry.is_file() and (entry.name.endswith(('.tar.gz', '.tar.xz', '.tar.bz2', '.tgz')) or entry.suffix == '.tar'):
                with tarfile.open(entry) as archive:
                    for member in archive:
                        check(f'{entry}!/{member.name}')
                        if member.isfile() and member.name.endswith('.jar'):
                            with archive.extractfile(member) as source:
                                check_jar(io.BytesIO(source.read()), f'{entry}!/{member.name}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('paths', type=Path, nargs='+')
    verify_linux_audio(parser.parse_args().paths)
