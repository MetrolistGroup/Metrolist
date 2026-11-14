"""Package a native application image without changing JVM distributions."""

import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def archive(directory, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED) as output:
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                info = zipfile.ZipInfo(path.relative_to(directory).as_posix())
                info.external_attr = path.stat().st_mode << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with path.open('rb') as source, output.open(info, 'w') as target:
                    shutil.copyfileobj(source, target)


def validate_windows(image):
    if not (image / 'Metrolist.exe').is_file():
        raise ValueError('Windows native image has no Metrolist.exe launcher')
    for path in image.rglob('*'):
        if path.suffix.lower() not in ('.exe', '.dll'):
            continue
        with path.open('rb') as stream:
            header = stream.read(64)
            if len(header) < 64 or header[:2] != b'MZ':
                raise ValueError(f'Invalid Windows executable: {path.name}')
            stream.seek(int.from_bytes(header[60:64], 'little'))
            pe = stream.read(6)
        if pe[:4] != b'PE\0\0' or int.from_bytes(pe[4:6], 'little') != 0x8664:
            raise ValueError(f'Expected x64 Windows executable: {path.name}')


def package(image, target, destination, version):
    name = f'metrolist-desktop-native-{target}-{version}'
    destination.mkdir(parents=True, exist_ok=True)
    if target.startswith('linux-'):
        app = destination / (name + '.AppDir')
        if app.exists():
            shutil.rmtree(app)
        (app / 'lib').mkdir(parents=True)
        shutil.copytree(image, app / 'lib/native')
        (app / 'bin').mkdir()
        shutil.copy2(Path(__file__).with_name('linux-launcher.sh'), app / 'bin/Metrolist')
        (app / 'bin/Metrolist').chmod(0o755)
        (app / 'AppRun').write_text('#!/bin/sh\nexec "$(dirname "$(readlink -f "$0")")/bin/Metrolist" "$@"\n')
        (app / 'AppRun').chmod(0o755)
        (app / 'Metrolist.desktop').write_text('[Desktop Entry]\nType=Application\nName=Metrolist\nExec=bin/Metrolist %u\nIcon=Metrolist\nStartupWMClass=Metrolist\nComment=Metrolist music client\nCategories=AudioVideo;Player;\nTerminal=false\nMimeType=x-scheme-handler/metrolist;x-scheme-handler/metrolist-auth;\n')
        shutil.copy2(ROOT / 'desktopApp/src/desktopMain/resources/logo.png', app / 'Metrolist.png')
        (app / 'resources').symlink_to('lib/native/resources', target_is_directory=True)
        output = destination / (name + '.AppImage')
        subprocess.run(['bash', str(ROOT / 'scripts/build_appimage.sh'), '--native-appdir', str(app.resolve()), str(output.resolve())], check=True)
    elif target == 'windows-x64':
        validate_windows(image)
        helper = image / 'resources/windows/metro-wv2-helper.exe'
        checksum = helper.with_suffix('.exe.sha256')
        if not helper.is_file() or not checksum.is_file():
            raise ValueError('Windows native image is missing the login helper or its checksum')
        with helper.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != checksum.read_text().strip():
            raise ValueError('Windows login helper checksum mismatch')
        (image / 'install-manifest.json').write_text(json.dumps({'schema':1,'appId':'com.metrolist.desktop','version':version,'platform':'windows','architecture':'x86_64','launcher':'Metrolist.exe'},indent=2)+'\n')
        output = destination / (name + '.zip')
        archive(image, output)
    elif target == 'macos-arm64':
        root = destination / name
        app = root / 'Metrolist.app/Contents'
        if root.exists():
            shutil.rmtree(root)
        shutil.copytree(image, app / 'MacOS', ignore=shutil.ignore_patterns('resources'))
        shutil.copytree(image / 'resources', app / 'Resources')
        shutil.copy2(ROOT / 'desktopApp/src/desktopMain/resources/logo.icns', app / 'Resources/logo.icns')
        with (app / 'Info.plist').open('wb') as stream:
            plistlib.dump({'CFBundleExecutable':'Metrolist','CFBundleIdentifier':'com.metrolist.desktop','CFBundleName':'Metrolist','CFBundlePackageType':'APPL','CFBundleShortVersionString':version.split('-')[0],'CFBundleVersion':version.split('-')[0],'CFBundleIconFile':'logo.icns'},stream)
        subprocess.run(['codesign','--force','--deep','--sign','-',str(app.parent)],check=True)
        output = destination / (name + '.zip')
        archive(root, output)
    else:
        raise ValueError('Unsupported native package platform')
    if not output.is_file() or output.stat().st_size == 0:
        raise RuntimeError('Native packaging produced no artifact')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    version = next(line.split('=',1)[1] for line in (ROOT/'gradle.properties').read_text().splitlines() if line.startswith('appVersion='))
    output = package(args.image.resolve(), args.platform, args.output.resolve(), version)
    print(output)
    import os
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a') as stream:
            stream.write(f'path={output}\n')


if __name__ == '__main__':
    main()
