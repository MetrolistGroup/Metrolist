"""Run the production DMG task with fake macOS tools in an isolated Gradle build."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CASES = ('success', 'detach-failure', 'background-failure', 'swift-failure')
TOOLS = '''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shutil
import sys

root = Path(os.environ['DMG_TEST_ROOT'])
args = sys.argv[1:]
tool = Path(sys.argv[0]).name
case = next(root / name for name in ('success', 'detach-failure', 'background-failure', 'swift-failure')
            if any(str(root / name) in arg for arg in args))
with (case / 'commands.jsonl').open('a') as log:
    log.write(json.dumps([tool, *args]) + '\\n')
if tool == 'hdiutil':
    if args[0] == 'convert':
        output = Path(args[args.index('-o') + 1])
        if args[args.index('-format') + 1] == 'UDRW':
            shutil.copyfile(args[1], output)
        else:
            mount = output.parent / 'mount'
            output.write_text(json.dumps({str(p.relative_to(mount)): p.read_text()
                                         for p in mount.rglob('*') if p.is_file()}))
    elif args[0] == 'attach':
        Path(args[args.index('-mountpoint') + 1], 'mounted-data').write_text('retain me')
    elif args[0] == 'detach' and case.name == 'detach-failure':
        sys.exit(77)
elif tool == 'osascript':
    script = Path(args[0])
    (case / 'layout.scpt').write_text(script.read_text())
    if case.name == 'background-failure':
        sys.exit(77)
    (script.parent / 'mount/.DS_Store').write_text('Finder layout')
elif tool == 'SetFile' and args[:2] == ['-a', 'C']:
    Path(args[2], 'finder-flags').write_text('C')
elif tool == 'swift' and case.name == 'swift-failure':
    sys.exit(77)
'''


class DmgPackagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(temporary.cleanup)
        cls.root = Path(temporary.name)
        source = (ROOT / 'desktopApp/packaging-integrity.gradle.kts').read_text()
        imports = source.split('// Packaging and integrity tasks', 1)[0]
        task = source.split('abstract class PatchDmgAppearanceTask', 1)[1].split('if (platform == "mac") {', 1)[0]
        task = 'abstract class PatchDmgAppearanceTask' + task
        assert task.count('if (!os.isMacOsX) return') == 1
        task = task.replace('if (!os.isMacOsX) return', '')
        task = task.replace('if (File("/usr/bin/SetFile").exists()) "/usr/bin/SetFile" else "SetFile"', '"SetFile"')
        (cls.root / 'settings.gradle.kts').write_text('rootProject.name = "dmg-test"\n')
        registrations = []
        for index, name in enumerate(CASES):
            case = cls.root / name
            (case / 'dmgs').mkdir(parents=True)
            (case / 'background.tiff').write_text('background artwork')
            (case / 'logo.icns').write_text('volume artwork')
            (case / 'dmgs/Metrolist.dmg').write_text('original image')
            registrations.append(f'''
tasks.register<PatchDmgAppearanceTask>("patch{index}") {{
    backgroundFile.set(layout.projectDirectory.file("{name}/background.tiff"))
    iconFile.set(layout.projectDirectory.file("{name}/logo.icns"))
    dmgDirectory.set(layout.projectDirectory.dir("{name}/dmgs"))
}}
''')
        (cls.root / 'build.gradle.kts').write_text(imports + task + '\n'.join(registrations))
        tools = cls.root / 'tools'
        tools.mkdir()
        for name in ('hdiutil', 'osascript', 'SetFile', 'swift'):
            tool = tools / name
            tool.write_text(TOOLS)
            tool.chmod(0o755)
        cls.result = subprocess.run(
            [os.environ.get('GRADLE', str(ROOT / 'gradlew')), '-p', str(cls.root),
             *(f'patch{i}' for i in range(len(CASES))), '--continue',
             '--no-configuration-cache', '--console=plain'],
            cwd=ROOT, capture_output=True, text=True,
            env={**os.environ, 'PATH': f'{tools}:{os.environ["PATH"]}', 'DMG_TEST_ROOT': str(cls.root)},
        )

    def commands(self, name):
        case = self.root / name
        log = case / 'commands.jsonl'
        self.assertTrue(log.exists(), self.result.stdout + self.result.stderr)
        return [json.loads(line) for line in log.read_text().splitlines()]

    def test_patches_artwork_and_layout_with_one_conversion_cycle(self):
        case = self.root / 'success'
        commands = self.commands('success')
        hdiutil = [command[1:] for command in commands if command[0] == 'hdiutil']
        self.assertEqual(['convert', 'attach', 'detach', 'convert'], [args[0] for args in hdiutil])
        self.assertEqual(['UDRW', 'ULMO'], [args[args.index('-format') + 1] for args in hdiutil if args[0] == 'convert'])
        self.assertEqual('swift', commands[-1][0])
        contents = json.loads((case / 'dmgs/Metrolist.dmg').read_text())
        self.assertEqual('background artwork', contents['.background/background.tiff'])
        self.assertEqual('volume artwork', contents['.VolumeIcon.icns'])
        self.assertEqual('C', contents['finder-flags'])
        layout = (case / 'layout.scpt').read_text()
        for instruction in ('{400, 100, 1060, 528}', '{163, 200}', '{497, 189}', 'delay 5'):
            self.assertIn(instruction, layout)
        self.assertEqual(['Metrolist.dmg'], [p.name for p in (case / 'dmgs').iterdir()])

    def test_failed_detach_retains_mount_and_original_image(self):
        commands = self.commands('detach-failure')
        self.assertEqual(5, sum(command[:2] == ['hdiutil', 'detach'] for command in commands))
        self.assertEqual(1, sum(command[:2] == ['hdiutil', 'convert'] for command in commands))
        case = self.root / 'detach-failure/dmgs'
        self.assertEqual('original image', (case / 'Metrolist.dmg').read_text())
        mounts = list(case.glob('metrolist-bg-*/mount/mounted-data'))
        self.assertEqual(1, len(mounts))
        self.assertEqual('retain me', mounts[0].read_text())

    def test_failed_background_update_detaches_before_cleanup(self):
        commands = self.commands('background-failure')
        self.assertEqual(['hdiutil', 'detach'], commands[-1][:2])
        self.assert_original_and_cleanup('background-failure')

    def test_failed_final_icon_update_preserves_original_image(self):
        commands = self.commands('swift-failure')
        self.assertEqual('swift', commands[-1][0])
        self.assert_original_and_cleanup('swift-failure')

    def assert_original_and_cleanup(self, name):
        case = self.root / name / 'dmgs'
        self.assertEqual('original image', (case / 'Metrolist.dmg').read_text())
        self.assertEqual(['Metrolist.dmg'], [p.name for p in case.iterdir()])
        self.assertNotEqual(0, self.result.returncode)


class TargetNativesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(temporary.cleanup)
        cls.root = Path(temporary.name)
        source = (ROOT / 'desktopApp/build.gradle.kts').read_text()
        selection = 'val targetArchitecture =' + source.split('val targetArchitecture =', 1)[1].split('val localProperties =', 1)[0]
        trimming = 'val jnaPlatform =' + source.split('val jnaPlatform =', 1)[1].split('val arm64WebViewJar =', 1)[0]
        imports = """
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
class TestOperatingSystem(val name: String) {
    val isMacOsX = name == "mac"
    val isWindows = name == "win"
    val isLinux = name == "linux"
}
val currentOperatingSystem = TestOperatingSystem(providers.gradleProperty("test.os").get())
"""
        setup = """
tasks.register("proguardReleaseJars") {
    outputs.dir(layout.projectDirectory.dir("jars"))
}
"""
        cls.results = {}
        for name, os_name, arch, host_arch in (
            ('native', 'mac', 'aarch64', 'aarch64'), ('cross', 'mac', 'x86_64', 'aarch64'),
            ('intel', 'mac', 'x86_64', 'x86_64'), ('cross-arm64', 'mac', 'aarch64', 'x86_64'),
            ('linux-x64', 'linux', 'x86_64', 'x86_64'), ('linux-arm64', 'linux', 'aarch64', 'aarch64'),
            ('win-x64', 'win', 'x86_64', 'x86_64'), ('win-arm64', 'win', 'aarch64', 'aarch64'),
        ):
            production = (selection + trimming).replace('System.getProperty("os.arch")', f'"{host_arch}"')
            arguments = [f'-Ptest.os={os_name}', f'-Pmetrolist.desktop.arch={arch}']
            jars = cls.root / name / 'jars'
            jars.mkdir(parents=True)
            for jar, entries in {
                'skiko-awt-runtime-all-test.jar': ('META-INF/MANIFEST.MF', 'META-INF/LICENSE',
                    'libskiko-macos-arm64.dylib', 'libskiko-macos-arm64.dylib.sha256',
                    'libskiko-macos-x64.dylib', 'libskiko-macos-x64.dylib.sha256', 'libskiko-linux-x64.so'),
                'jna-5-test.jar': ('example.class', 'com/sun/jna/darwin-aarch64/libjnidispatch.jnilib',
                                 'com/sun/jna/darwin-x86-64/libjnidispatch.jnilib'),
                'wrywebview-test.jar': ('example.class', 'darwin-aarch64/libcomposewebview_wry.dylib',
                                       'darwin-x86-64/libcomposewebview_wry.dylib'),
            }.items():
                with zipfile.ZipFile(jars / jar, 'w') as archive:
                    for entry in entries:
                        archive.writestr(entry, entry)
            for jar, prefix in (('nucleus.darkmode-test.jar', 'nucleus/native'),
                                ('share-jvm-test.jar', 'nucleus/native'),
                                ('ComposeNativeTray-jvm-test.jar', 'composetray/native')):
                with zipfile.ZipFile(jars / jar, 'w') as archive:
                    for entry in ('example.class', 'META-INF/LICENSE', 'META-INF/MANIFEST.MF'):
                        archive.writestr(entry, entry)
                    for platform in ('darwin', 'linux', 'win32'):
                        extension = {'darwin': 'dylib', 'linux': 'so', 'win32': 'dll'}[platform]
                        for architecture in ('x64', 'aarch64'):
                            entry = f'{prefix}/{platform}-{architecture}/native.{extension}'
                            archive.writestr(entry, entry)
            project = jars.parent
            (project / 'settings.gradle.kts').write_text('rootProject.name = "macos-natives-test"\n')
            (project / 'build.gradle.kts').write_text(imports + setup + production)
            cls.results[name] = subprocess.run(
                [os.environ.get('GRADLE', str(ROOT / 'gradlew')), '-p', str(project),
                 'proguardReleaseJars', '--no-configuration-cache', '--console=plain', *arguments],
                cwd=ROOT, capture_output=True, text=True,
            )

    def entries(self, mode, name):
        result = self.results[mode]
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        with zipfile.ZipFile(self.root / mode / 'jars' / name) as archive:
            return set(archive.namelist())

    def test_cross_package_unpacks_target_skiko_and_checksum_and_keeps_metadata(self):
        for mode, architecture in (('cross', 'x64'), ('cross-arm64', 'arm64')):
            with self.subTest(mode=mode):
                self.assertEqual({'META-INF/MANIFEST.MF', 'META-INF/LICENSE'},
                                 self.entries(mode, 'metrolist-skiko-awt-runtime-all-test.jar'))
                jars = self.root / mode / 'jars'
                self.assertFalse((jars / 'skiko-awt-runtime-all-test.jar').exists())
                native = f'libskiko-macos-{architecture}.dylib'
                self.assertEqual({native, native + '.sha256'}, {p.name for p in jars.glob('libskiko-*')})
                for name in (native, native + '.sha256'):
                    self.assertEqual(name, (jars / name).read_text())

    def test_native_package_leaves_skiko_for_compose_to_filter(self):
        for mode in ('native', 'intel', 'linux-x64', 'linux-arm64', 'win-x64', 'win-arm64'):
            with self.subTest(mode=mode):
                entries = self.entries(mode, 'skiko-awt-runtime-all-test.jar')
                self.assertIn('libskiko-macos-arm64.dylib', entries)
                self.assertIn('libskiko-macos-x64.dylib', entries)
                self.assertEqual([], list((self.root / mode / 'jars').glob('libskiko-*')))

    def test_nucleus_and_tray_follow_every_target_and_preserve_metadata(self):
        for mode, target in (('native', 'darwin-aarch64'), ('cross', 'darwin-x64'),
                             ('linux-x64', 'linux-x64'), ('linux-arm64', 'linux-aarch64'),
                             ('win-x64', 'win32-x64'), ('win-arm64', 'win32-aarch64')):
            extension = 'dylib' if target.startswith('darwin') else 'so' if target.startswith('linux') else 'dll'
            for jar, prefix in (('nucleus.darkmode-test.jar', 'nucleus/native'),
                                ('share-jvm-test.jar', 'nucleus/native'),
                                ('ComposeNativeTray-jvm-test.jar', 'composetray/native')):
                with self.subTest(mode=mode, jar=jar):
                    self.assertEqual({'example.class', 'META-INF/LICENSE', 'META-INF/MANIFEST.MF',
                                      f'{prefix}/{target}/native.{extension}'}, self.entries(mode, jar))

    def test_jna_and_webview_follow_the_target(self):
        for mode, architecture in (('native', 'aarch64'), ('cross', 'x86-64')):
            with self.subTest(mode=mode):
                self.assertEqual({'example.class', f'com/sun/jna/darwin-{architecture}/libjnidispatch.jnilib'},
                                 self.entries(mode, 'jna-5-test.jar'))
                self.assertEqual({'example.class', f'darwin-{architecture}/libcomposewebview_wry.dylib'},
                                 self.entries(mode, 'wrywebview-test.jar'))


if __name__ == '__main__':
    unittest.main()
