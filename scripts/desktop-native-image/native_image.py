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
               '-H:ConfigurationFileDirectories=' + ','.join(str(p.resolve()) for p in configurations(target, agent, experiment))]
    if not experiment:
        # Testers fall back like the JVM on a missing registration; CI smoke passes -XX:...=Exit to stay strict.
        options += ['-R:MaxHeapSize=384m', '-R:MinHeapSize=32m', '-R:MissingRegistrationReportingMode=Warn']
        if windows:
            # Match Nucleus's GUI launcher while retaining Native Image's main entry point.
            options += ['-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS', '-H:NativeLinkerOption=/ENTRY:mainCRTStartup']
    main = 'com.metrolist.desktop.experiment.NativeImageMainKt' if experiment else 'com.metrolist.desktop.nativeimage.NativeDesktopMainKt'
    classpath = (work / 'classpath.txt').read_text().strip()
    if not experiment:
        classpath = str(work / 'native-glue') + os.pathsep + classpath
    return [*options, '-cp', classpath, '-o', str(output), main]


def build(graal, work, target, experiment=False, agent=None):
    if target != host_platform() or target not in ('linux-x64', 'linux-arm64', 'windows-x64', 'macos-arm64'):
        raise ValueError('Native Image must build on its supported target OS and architecture')
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
