"""Exercise macOS native checks and x64 packaging arguments without macOS tools."""

import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from test_release_workflows import step_script

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_macos_arch', ROOT / '.github/scripts/verify_macos_arch.py')
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class MacosArchitectureTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.app = Path(temporary.name) / 'Metrolist with spaces.app'
        self.launcher = self.app / 'Contents/MacOS/Metrolist'
        self.runtime = self.app / 'Contents/runtime/Contents/Home/lib/server/libjvm.dylib'
        self.library = self.app / 'Contents/app/resources/libsidecar.dylib'
        for path in (self.launcher, self.runtime, self.library):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('x86_64')
        self.jar = self.app / 'Contents/app/ffmpeg.jar'
        self.write_jar('x86_64')

    def write_jar(self, architecture, extension='.dylib'):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('org/bytedeco/ffmpeg/macosx-x86_64/libavcodec' + extension, architecture)
            archive.writestr('example.class', b'not a native library')

    def inspect(self, command, text):
        self.assertTrue(text)
        self.assertIn(command[:2], (['file', '-b'], ['lipo', '-archs']))
        architectures = Path(command[2]).read_text()
        if command[0] == 'file':
            return f'Mach-O {architectures}\n'
        return architectures + '\n'

    def verify(self, architecture='x86_64'):
        with patch.object(VERIFY.subprocess, 'check_output', side_effect=self.inspect) as tool:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                VERIFY.verify_app(self.app, architecture)
        return tool, output.getvalue()

    def test_checks_launcher_runtime_sidecars_and_jar_libraries(self):
        tool, output = self.verify()
        self.assertEqual(8, tool.call_count)
        self.assertIn('Contents/MacOS/Metrolist', output)
        self.assertIn('libjvm.dylib', output)
        self.assertIn('ffmpeg.jar!/org/bytedeco/ffmpeg/macosx-x86_64/libavcodec.dylib', output)

    def test_rejects_wrong_or_mixed_architecture_at_each_location(self):
        for path in (self.launcher, self.runtime, self.library):
            for architecture in ('arm64', 'x86_64 arm64'):
                with self.subTest(path=path, architecture=architecture):
                    path.write_text(architecture)
                    with self.assertRaisesRegex(ValueError, 'expected only x86_64'):
                        self.verify()
                    path.write_text('x86_64')
        for extension in ('.dylib', '.jnilib'):
            with self.subTest(extension=extension):
                self.write_jar('arm64', extension)
                with self.assertRaisesRegex(ValueError, 'ffmpeg.jar!/'):
                    self.verify()

    def test_requires_launcher_and_jvm(self):
        for path, message in ((self.launcher, 'Missing launcher'), (self.runtime, 'Missing bundled libjvm')):
            with self.subTest(path=path):
                path.unlink()
                with self.assertRaisesRegex(ValueError, message):
                    self.verify()
                path.write_text('x86_64')

    def test_skiko_library_path_requires_a_loose_target_library_even_when_jar_contains_it(self):
        app_directory = self.app / 'Contents/app'
        (app_directory / 'Metrolist.cfg').write_text(
            '[JavaOptions]\njava-options=-Xmx384m\njava-options=-Dskiko.library.path=$APPDIR\n')
        for architecture, skiko_architecture in (('x86_64', 'x64'), ('arm64', 'arm64')):
            native = f'libskiko-macos-{skiko_architecture}.dylib'
            with self.subTest(architecture=architecture):
                with zipfile.ZipFile(app_directory / 'metrolist-skiko-awt-runtime-all.jar', 'w') as archive:
                    archive.writestr(native, architecture)
                with self.assertRaisesRegex(ValueError, f'skiko.library.path.*{native}'):
                    self.verify(architecture)
                (app_directory / native).mkdir()
                with self.assertRaisesRegex(ValueError, f'skiko.library.path.*{native}'):
                    self.verify(architecture)
                (app_directory / native).rmdir()

    def test_skiko_library_path_accepts_and_checks_the_matching_loose_library(self):
        app_directory = self.app / 'Contents/app'
        for architecture, skiko_architecture in (('x86_64', 'x64'), ('arm64', 'arm64')):
            for path in (self.launcher, self.runtime, self.library):
                path.write_text(architecture)
            self.write_jar(architecture)
            native = app_directory / f'libskiko-macos-{skiko_architecture}.dylib'
            for directory in ('$APPDIR', '$APPDIR/', str(app_directory)):
                with self.subTest(architecture=architecture, directory=directory):
                    (app_directory / 'Metrolist.cfg').write_text(
                        f'[JavaOptions]\njava-options=-Dskiko.library.path={directory}\n')
                    native.write_text(architecture)
                    _, output = self.verify(architecture)
                    self.assertIn(f'Contents/app/{native.name}', output)
                    native.write_text('arm64' if architecture == 'x86_64' else 'x86_64')
                    with self.assertRaisesRegex(ValueError, f'expected only {architecture}'):
                        self.verify(architecture)
            native.unlink()

    def test_skiko_in_a_jar_is_allowed_without_a_library_path_override(self):
        (self.app / 'Contents/app/Metrolist.cfg').write_text(
            '[JavaOptions]\njava-options=-Xmx384m\n')
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('libskiko-macos-x64.dylib', 'x86_64')
        self.verify()

    def test_accepts_architecture_folders_with_matching_x64_libraries(self):
        for arm in ('arm64', 'aarch64'):
            for x64 in ('x86_64', 'x86-64', 'x64', 'amd64'):
                for prefix in ('', 'darwin-', 'macos-', 'macosx-'):
                    with self.subTest(arm=arm, x64=x64, prefix=prefix):
                        with zipfile.ZipFile(self.jar, 'w') as archive:
                            for architecture, contents in ((arm, 'arm64'), (x64, 'x86_64')):
                                archive.writestr(f'composetray/native/{prefix}{architecture}/libMacTray.dylib', contents)
                        _, output = self.verify()
                        self.assertIn(f'{prefix}{x64}/libMacTray.dylib', output)

    def test_foreign_library_requires_the_same_relative_path_in_the_same_jar(self):
        foreign = 'composetray/native/darwin-aarch64/libMacTray.dylib'
        for counterpart in (None, 'composetray/native/darwin-x86_64/libOther.dylib',
                            'other/native/darwin-x86_64/libMacTray.dylib',
                            'composetray/native/darwin-x86_64/libMacTray.dylib/'):
            with self.subTest(counterpart=counterpart):
                with zipfile.ZipFile(self.jar, 'w') as archive:
                    archive.writestr(foreign, 'arm64')
                    if counterpart:
                        archive.writestr(counterpart, 'x86_64')
                other_jar = self.jar.with_name('other.jar')
                with zipfile.ZipFile(other_jar, 'w') as archive:
                    archive.writestr(foreign.replace('aarch64', 'x86_64'), 'x86_64')
                with self.assertRaisesRegex(ValueError, 'expected only x86_64'):
                    self.verify()

    def test_counterpart_must_contain_only_x64(self):
        for architecture in ('arm64', 'x86_64 arm64'):
            with self.subTest(architecture=architecture), zipfile.ZipFile(self.jar, 'w') as archive:
                archive.writestr('darwin-aarch64/libMacTray.dylib', 'arm64')
                archive.writestr('darwin-x86_64/libMacTray.dylib', architecture)
            with self.assertRaisesRegex(ValueError, 'darwin-x86_64/libMacTray.dylib'):
                self.verify()

    def test_generic_paths_and_architecture_in_filenames_are_not_exempt(self):
        for foreign, counterpart in (('native/libMacTray-arm64.dylib', 'native/libMacTray-x64.dylib'),
                                     ('arm64-support/libMacTray.dylib', 'x64-support/libMacTray.dylib'),
                                     ('native/libMacTray.dylib', 'x64/libMacTray.dylib')):
            with self.subTest(foreign=foreign):
                with zipfile.ZipFile(self.jar, 'w') as archive:
                    archive.writestr(foreign, 'arm64')
                    archive.writestr(counterpart, 'x86_64')
                with self.assertRaisesRegex(ValueError, 'expected only x86_64'):
                    self.verify()

    def test_tool_failure_and_corrupt_jar_fail_verification(self):
        for tool in ('file', 'lipo'):
            def inspect(command, text):
                if command[0] == tool:
                    raise subprocess.CalledProcessError(1, command)
                return self.inspect(command, text)

            with self.subTest(tool=tool), patch.object(VERIFY.subprocess, 'check_output', side_effect=inspect):
                with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(subprocess.CalledProcessError):
                    VERIFY.verify_app(self.app, 'x86_64')
        self.jar.write_bytes(b'broken zip')
        with self.assertRaises(zipfile.BadZipFile):
            self.verify()


class MacosBuildTest(unittest.TestCase):
    def test_checks_the_final_dmg_and_propagates_verification_and_detach_failures(self):
        script = step_script('reusable-desktop-macos.yml', 'Verify x64 DMG native architectures')
        for count, attach_status, inspection_status, detach_status in (
            (1, 0, 0, 0), (1, 0, 1, 0), (1, 0, 0, 1), (1, 1, 0, 0), (0, 0, 0, 0), (2, 0, 0, 0),
        ):
            with self.subTest(count=count, attach=attach_status, inspection=inspection_status, detach=detach_status), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                output = root / 'desktopApp/build/compose/binaries/main-release/dmg'
                output.mkdir(parents=True)
                for index in range(count):
                    (output / f'Metrolist-{index}.dmg').touch()
                tools = root / 'tools'
                tools.mkdir()
                for name, body in (
                    ('hdiutil', 'echo "$1" >> "$TOOL_LOG"\nif [[ "$1" == detach ]]; then exit "$DETACH_STATUS"; fi\nexit "$ATTACH_STATUS"'),
                    ('python3', 'printf "%s\\n" "$@" >> "$TOOL_LOG"\nexit "$INSPECTION_STATUS"'),
                ):
                    tool = tools / name
                    tool.write_text('#!/bin/bash\n' + body + '\n')
                    tool.chmod(0o755)
                result = subprocess.run(['bash', '-c', script], cwd=root, capture_output=True, text=True, env={
                    **os.environ, 'RUNNER_TEMP': str(root), 'PATH': f'{tools}:{os.environ["PATH"]}',
                    'TOOL_LOG': str(root / 'tools.log'), 'DETACH_STATUS': str(detach_status),
                    'INSPECTION_STATUS': str(inspection_status), 'ATTACH_STATUS': str(attach_status),
                })
                self.assertEqual(int(count != 1) or attach_status or inspection_status or detach_status, result.returncode, result.stderr)
                if count != 1:
                    self.assertFalse((root / 'tools.log').exists())
                    continue
                log = (root / 'tools.log').read_text().splitlines()
                self.assertEqual('attach', log[0])
                if attach_status:
                    self.assertEqual(['attach', 'detach'], log)
                    continue
                self.assertEqual('.github/scripts/verify_macos_arch.py', log[1])
                self.assertTrue(log[2].endswith('/Metrolist.app'))
                self.assertEqual(['x86_64', 'detach'], log[3:])

    def test_x64_uses_native_gradle_and_a_separate_packaging_jdk(self):
        template = step_script('reusable-desktop-macos.yml', 'Build macOS DMG')
        for architecture, verification in (('x64', 'true'), ('x64', 'false'), ('arm64', 'true'), ('arm64', 'false')):
            script = template.replace('${{ inputs.run-verification }}', verification)
            with self.subTest(architecture=architecture, verification=verification), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                gradlew = root / 'gradlew'
                gradlew.write_text('#!/bin/bash\necho "$JAVA_HOME"\nprintf "%s\\n" "$@"\n')
                gradlew.chmod(0o755)
                result = subprocess.run(['bash', '-c', script], cwd=root, capture_output=True, text=True, env={
                    **os.environ, 'ARCH': architecture, 'RUNNER_TEMP': str(root), 'JAVA_HOME': '/test JDK/Contents/Home',
                    'PACKAGING_JAVA_HOME': '/x64 JDK/Contents/Home',
                })
                self.assertEqual(0, result.returncode, result.stderr)
                native_home, *arguments = result.stdout.splitlines()
                self.assertEqual('/test JDK/Contents/Home', native_home)
                self.assertEqual(':desktopApp:packageReleaseDmg', arguments[0])
                self.assertIn('--no-configuration-cache', arguments)
                self.assertNotIn('--init-script', arguments)
                if architecture == 'x64':
                    self.assertIn('-Pmetrolist.desktop.arch=x86_64', arguments)
                    self.assertIn('-Pmetrolist.desktop.javaHome=/x64 JDK/Contents/Home', arguments)
                    self.assertIn('-Dorg.gradle.java.installations.auto-detect=false', arguments)
                    self.assertIn('-Dorg.gradle.java.installations.auto-download=false', arguments)
                    self.assertIn('-Dorg.gradle.java.installations.paths=/test JDK/Contents/Home', arguments)
                else:
                    self.assertEqual([
                        ':desktopApp:packageReleaseDmg', f'-Pmetrolist.verifyPackaging={verification}', '--no-daemon', '--no-configuration-cache',
                    ], arguments)


if __name__ == '__main__':
    unittest.main()
