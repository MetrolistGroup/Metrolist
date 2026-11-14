"""Check the launcher, runtime and native libraries in a packaged macOS app."""

import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile


def verify_binary(path, architecture, label):
    description = subprocess.check_output(['file', '-b', str(path)], text=True).strip()
    architectures = subprocess.check_output(['lipo', '-archs', str(path)], text=True).strip()
    print(f'{label}: {description}; architectures: {architectures}', flush=True)
    if architectures.split() != [architecture]:
        raise ValueError(f'{label}: expected only {architecture}, found {architectures}')


def has_x64_counterpart(name, names):
    parts = name.split('/')
    for index, part in enumerate(parts[:-1]):
        for arm in ('aarch64', 'arm64'):
            for prefix in ('', 'darwin-', 'macos-', 'macosx-'):
                if part != prefix + arm:
                    continue
                for x64 in ('x86_64', 'x86-64', 'x64', 'amd64'):
                    counterpart = '/'.join([*parts[:index], prefix + x64, *parts[index + 1:]])
                    if counterpart in names:
                        return True
    return False


def verify_app(app, architecture):
    launcher = app / 'Contents/MacOS/Metrolist'
    if not launcher.is_file():
        raise ValueError(f'Missing launcher: {launcher}')
    app_directory = app / 'Contents/app'
    skiko_architecture = 'x64' if architecture == 'x86_64' else 'arm64'
    for config in sorted(app_directory.glob('*.cfg')):
        for line in config.read_text().splitlines():
            key, _, option = line.partition('=')
            option = option.strip()
            if key.strip() == 'java-options' and option.startswith('-Dskiko.library.path='):
                directory = option.split('=', 1)[1].replace('$APPDIR', str(app_directory))
                library = Path(directory) / f'libskiko-macos-{skiko_architecture}.dylib'
                if not library.is_file():
                    raise ValueError(f'{config}: skiko.library.path requires missing library: {library}')
    libraries = sorted(path for path in app.rglob('*') if path.suffix in ('.dylib', '.jnilib') and path.is_file())
    if not any(path.name == 'libjvm.dylib' for path in libraries):
        raise ValueError(f'Missing bundled libjvm.dylib in {app}')
    for path in [launcher, *libraries]:
        verify_binary(path, architecture, path.relative_to(app))
    # Check natives stored in JARs as well as the loose libraries above.
    with tempfile.TemporaryDirectory(prefix='metrolist-macos-natives-') as directory:
        for jar in sorted(app.rglob('*.jar')):
            with zipfile.ZipFile(jar) as archive:
                natives = [entry for entry in archive.infolist()
                           if not entry.is_dir() and Path(entry.filename).suffix in ('.dylib', '.jnilib')]
                names = {entry.filename for entry in natives}
                for entry in natives:
                    if architecture == 'x86_64' and has_x64_counterpart(entry.filename, names):
                        continue
                    library = Path(directory) / ('native' + Path(entry.filename).suffix)
                    library.write_bytes(archive.read(entry))
                    verify_binary(library, architecture, f'{jar.relative_to(app)}!/{entry.filename}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', type=Path)
    parser.add_argument('architecture', choices=['x86_64', 'arm64'])
    args = parser.parse_args()
    verify_app(args.app, args.architecture)
