"""Build an opt-in native application image on its target OS and architecture."""

import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile

from serialization_metadata import generate

ROOT = Path(__file__).resolve().parents[2]
METADATA = Path(__file__).parent / 'resources/META-INF/native-image/com.metrolist/desktop-experiment'


def host_platform():
    system = {'Linux': 'linux', 'Windows': 'windows', 'Darwin': 'macos'}[platform.system()]
    arch = {'x86_64': 'x64', 'AMD64': 'x64', 'aarch64': 'arm64', 'arm64': 'arm64'}[platform.machine()]
    return system + '-' + arch


def configurations(target, agent, experiment=False):
    system = target.split('-')[0]
    packs = [system, 'fonts', 'skiko']
    if not experiment:
        packs += ['jna']
    if system != 'linux':
        packs += ['awt', 'graphics2d']
    if system == 'windows':
        packs += ['jna-win32']
    paths = [METADATA, METADATA / 'application']
    paths += [METADATA / 'nucleus' / pack for pack in packs]
    if not experiment:
        paths += [METADATA / system, METADATA / 'wry']
    if agent and (agent / 'reachability-metadata.json').is_file():
        paths.append(agent)
    return paths


NUCLEUS_NATIVE_DIRECTORIES = {'linux-x64': 'linux-x64', 'linux-arm64': 'linux-aarch64',
                              'macos-arm64': 'darwin-aarch64', 'windows-x64': 'win32-x64'}


def nucleus_natives(work, target):
    # Nucleus extracts its JNI libraries from nucleus/native/<platform>/ at runtime; traces only saw linux-x64.
    directory = work / 'nucleus-natives'
    directory.mkdir(parents=True, exist_ok=True)
    glob = f'nucleus/native/{NUCLEUS_NATIVE_DIRECTORIES[target]}/*'
    (directory / 'reachability-metadata.json').write_text(json.dumps({'resources': [{'glob': glob}]}, indent=2) + '\n')
    return directory


# Nucleus's GraalVM manifest plus supportedOS: without it GetVersion reports Windows 8 (build 9200) and the
# Windows 10 startup check rejects the native executable. The JVM build inherits java.exe's manifest.
WINDOWS_MANIFEST = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0" xmlns:asmv3="urn:schemas-microsoft-com:asm.v3">
  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1">
    <application>
      <supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/>
    </application>
  </compatibility>
  <asmv3:application>
    <asmv3:windowsSettings>
      <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/PM</dpiAware>
      <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2,PerMonitor</dpiAwareness>
      <activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</activeCodePage>
      <longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">true</longPathAware>
    </asmv3:windowsSettings>
  </asmv3:application>
</assembly>
"""


def windows_resources(work, version, rc=None):
    """Compile the manifest, icon and version info that Native Image links into Metrolist.exe."""
    directory = work / 'windows-resources'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'metrolist.manifest').write_text(WINDOWS_MANIFEST, encoding='utf-8')
    numbers = [int(part) for part in version.split('+')[0].split('-')[0].split('.')] + [0]
    numeric = ','.join(str(part) for part in numbers[:4])
    quoted = lambda path: '"' + str(path).replace('\\', '\\\\') + '"'
    (directory / 'metrolist.rc').write_text('\n'.join([
        '#pragma code_page(65001)',
        f'1 ICON {quoted(ROOT / "desktopApp/src/desktopMain/resources/logo.ico")}',
        f'1 24 {quoted(directory / "metrolist.manifest")}',
        '1 VERSIONINFO', f'FILEVERSION {numeric}', f'PRODUCTVERSION {numeric}', 'BEGIN',
        '  BLOCK "StringFileInfo"', '  BEGIN', '    BLOCK "040904B0"', '    BEGIN',
        '      VALUE "FileDescription", "Metrolist"', f'      VALUE "FileVersion", "{version}"',
        '      VALUE "InternalName", "Metrolist"', '      VALUE "OriginalFilename", "Metrolist.exe"',
        '      VALUE "ProductName", "Metrolist"', f'      VALUE "ProductVersion", "{version}"',
        '    END', '  END', '  BLOCK "VarFileInfo"', '  BEGIN', '    VALUE "Translation", 0x0409, 0x04B0', '  END', 'END', '']),
        encoding='utf-8')
    if rc is None:
        kits = sorted(Path(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)'), 'Windows Kits/10/bin').glob('10.*/x64/rc.exe'))
        rc = shutil.which('rc') or (kits[-1] if kits else None)
        if rc is None:
            raise RuntimeError('rc.exe from the Windows SDK is required to build the Windows native image')
    output = directory / 'metrolist.res'
    subprocess.run([str(rc), '/nologo', '/fo', str(output), str(directory / 'metrolist.rc')], check=True)
    return output


def arguments(graal, work, output, target, experiment=False, agent=None):
    windows = target.startswith('windows-')
    compiler = graal / 'bin' / ('native-image.cmd' if windows else 'native-image')
    options = [str(compiler), '-J-Xmx12g', '-J-XX:ActiveProcessorCount=4',
               f'-J-Djava.io.tmpdir={work / "native-tmp"}', '--no-fallback', '--parallelism=2',
               os.environ.get('NATIVE_IMAGE_OPTIMIZATION', '-Os') if experiment else '-Os',
               '--gc=' + (os.environ.get('NATIVE_IMAGE_GC', 'serial') if experiment else 'serial'),
               '--exact-reachability-metadata', '--enable-url-protocols=http,https',
               '--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=false',
               '-H:+AddAllCharsets', '-H:+IncludeAllLocales',
               '-H:ConfigurationFileDirectories=' + ','.join(str(p.resolve()) for p in [*configurations(target, agent, experiment), work / 'serialization-config', nucleus_natives(work, target)])]
    if not experiment:
        # Testers fall back like the JVM on a missing registration; CI smoke fails on any reported registration.
        options += ['-R:MaxHeapSize=384m', '-R:MinHeapSize=32m', '-R:MissingRegistrationReportingMode=Warn']
        # The default x86-64-v3 / armv8.1-a target refuses to start on CPUs without AVX2 or on Raspberry Pi 4 class ARM.
        options += ['-march=compatibility']
        if windows:
            # Match Nucleus's GUI launcher while retaining Native Image's main entry point.
            options += ['-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS', '-H:NativeLinkerOption=/ENTRY:mainCRTStartup']
            options += [f'-H:NativeLinkerOption={work / "windows-resources/metrolist.res"}']
    main = 'com.metrolist.desktop.experiment.NativeImageMainKt' if experiment else 'com.metrolist.desktop.nativeimage.NativeDesktopMainKt'
    classpath = (work / 'classpath.txt').read_text().strip()
    classpath = str(work / 'serialization-config') + os.pathsep + classpath
    if not experiment:
        classpath = str(work / 'native-glue') + os.pathsep + classpath
    # Native Image appends .exe on Windows itself, so -o names the image without the suffix.
    return [*options, '-cp', classpath, '-o', str(output.with_suffix('') if windows else output), main]


def build(graal, work, target, experiment=False, agent=None):
    if target != host_platform() or target not in ('linux-x64', 'linux-arm64', 'windows-x64', 'macos-arm64'):
        raise ValueError('Native Image must build on its supported target OS and architecture')
    generate((work / 'classpath.txt').read_text().strip().split(os.pathsep), work / 'serialization-config')
    (work / 'native-tmp').mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='image-build.', dir=work) as directory:
        image = Path(directory)
        name = 'metrolist' if experiment else 'Metrolist'
        output = image / (name + ('.exe' if target.startswith('windows') else ''))
        if not experiment:
            glue = work / 'native-glue'
            if glue.exists():
                shutil.rmtree(glue)
            glue.mkdir()
            sources = Path(__file__).parent / 'java'
            java = [graal / 'bin' / ('javac.exe' if target.startswith('windows') else 'javac'),
                    '--add-modules', 'org.graalvm.nativeimage', '-cp',
                    str(graal / 'lib/svm/builder/svm.jar') + os.pathsep + (work / 'classpath.txt').read_text().strip(),
                    '-d', str(glue), str(sources / 'NativeCrashLog.java'),
                    str(sources / ('windows' if target.startswith('windows') else 'posix') / 'NativeErrorOutput.java')]
            java_args = work / 'native-javac.args'
            java_args.write_text('\n'.join(json.dumps(str(arg), ensure_ascii=False) for arg in java[1:]) + '\n', encoding='utf-8')
            with (work / 'java-glue.log').open('w') as log:
                subprocess.run([str(java[0]), '-J-Xmx512m', '@' + str(java_args)], check=True, stdout=log, stderr=subprocess.STDOUT)
        if target.startswith('windows') and not experiment:
            windows_resources(work, os.environ.get('ORG_GRADLE_PROJECT_appVersion') or next(
                line.split('=', 1)[1] for line in (ROOT / 'gradle.properties').read_text().splitlines() if line.startswith('appVersion=')))
        command = arguments(graal, work, output, target, experiment, agent)
        argfile = work / 'native-image.args'
        argfile.write_text('\n'.join(json.dumps(arg, ensure_ascii=False) for arg in command[1:]) + '\n', encoding='utf-8')
        command = [command[0], '@' + str(argfile)]
        with (work / 'build.log').open('w') as log:
            subprocess.run([sys.executable, str(Path(__file__).with_name('measure-build.py')),
                            str(work / 'builder-time.json'), *command], check=True, stdout=log, stderr=subprocess.STDOUT)
        if not output.is_file():
            raise RuntimeError('Native Image returned success without an executable')
        # Native Image emits small JVM shims. Full JDK libraries must never overwrite them.
        library = 'jawt.dll' if target.startswith('windows') else ('libjawt.dylib' if target.startswith('macos') else 'libjawt.so')
        source = graal / ('bin' if target.startswith('windows') else 'lib') / library
        if not (image / library).exists():
            shutil.copy2(source, image / library)
        freetype = graal / 'lib/libfreetype.so'
        if freetype.exists() and not (image / freetype.name).exists():
            shutil.copy2(freetype, image / freetype.name)
        (image / 'lib').mkdir(exist_ok=True)
        shutil.copy2(source, image / 'lib' / library)
        # Windows AWT reads its font configuration from java.home/lib, which is the image directory here.
        # macOS must not get these: data files inside Contents/MacOS break codesign.
        if target.startswith('windows'):
            for name in ('fontconfig.bfc', 'fontconfig.properties.src', 'psfontj2d.properties', 'psfont.properties.ja'):
                if (graal / 'lib' / name).is_file():
                    shutil.copy2(graal / 'lib' / name, image / 'lib' / name)
        if target.startswith('windows'):
            (image / 'bin').mkdir(exist_ok=True)
            shutil.copy2(source, image / 'bin' / library)
        resources = work / 'resources'
        if resources.exists():
            shutil.copytree(resources, image / 'resources')
        target_dir = work / 'image'
        backup = work / 'image.previous'
        if backup.exists():
            shutil.rmtree(backup)
        if target_dir.exists():
            target_dir.rename(backup)
        try:
            image.rename(target_dir)
        except OSError:
            if backup.exists():
                backup.rename(target_dir)
            raise
        if backup.exists():
            shutil.rmtree(backup)
        # TemporaryDirectory owns the old path, which has moved into the published image.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, default=Path('/tmp/r6-native-image'))
    parser.add_argument('--graalvm', type=Path, default=Path(os.environ.get('GRAALVM_HOME', '/mnt/work/tools/graalvm-25')))
    parser.add_argument('--platform', default=host_platform())
    parser.add_argument('--experiment', action='store_true')
    parser.add_argument('--agent', type=Path)
    parser.add_argument('--inside-scope', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    args.work = args.work.resolve(); args.graalvm = args.graalvm.resolve()
    args.work.mkdir(parents=True, exist_ok=True)
    (args.work / 'builder-time.json').unlink(missing_ok=True)
    (args.work / 'build.log').write_text('')
    (args.work / 'java-glue.log').unlink(missing_ok=True)
    if sys.platform == 'linux' and not args.inside_scope:
        import fcntl
        with open('/tmp/metrolist-gradle.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            command = ['systemd-run', '--user', '--scope', '--quiet', '-p', 'MemoryMax=16G',
                       '-p', 'MemorySwapMax=2G', '-p', 'CPUWeight=50', sys.executable,
                       str(Path(__file__).resolve()), *sys.argv[1:], '--inside-scope']
            subprocess.run(command, check=True)
    else:
        build(args.graalvm, args.work, args.platform, args.experiment, args.agent)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
