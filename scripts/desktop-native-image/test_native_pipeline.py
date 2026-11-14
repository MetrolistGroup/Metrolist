"""Check downloads, package boundaries and build isolation without a compiler or GUI."""

import fnmatch
import hashlib
import io
import json
import os
from pathlib import Path
import plistlib
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import native_image
import package
import toolchain


def jdk_archive():
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        archive.writestr('jdk/release', 'JAVA_VERSION="25.0.4"')
        archive.writestr('jdk/bin/java.exe', b'launcher')
    return output.getvalue()


def executable(path, machine=0x8664):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'MZ' + bytes(58) + struct.pack('<I',64) + b'PE\0\0' + struct.pack('<H',machine))


def posix_executable(path, target):
    path.parent.mkdir(parents=True, exist_ok=True)
    if target == 'macos-arm64':
        path.write_bytes(b'\xcf\xfa\xed\xfe' + struct.pack('<I', 0x100000C) + bytes(24))
    else:
        path.write_bytes(b'\x7fELF\x02\x01' + bytes(12) + struct.pack('<H', 62 if target == 'linux-x64' else 183) + bytes(44))
    path.chmod(0o755)


class PipelineTest(unittest.TestCase):
    def test_checksum_cache_hit_and_corrupt_cache_recovery(self):
        data = jdk_archive()
        pin = {'platforms': {'windows-x64': {'url':'https://example.invalid/jdk.zip','sha256':hashlib.sha256(data).hexdigest()}}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for attempt in range(3):
                with patch('urllib.request.urlopen', return_value=io.BytesIO(data)) as download:
                    home = toolchain.install('windows-x64',root/'cache',root/'jdk',pin)
                    self.assertTrue((home/'bin/java.exe').is_file())
                    self.assertEqual(download.call_count, 0 if attempt == 1 else 1)
                if attempt == 1:
                    next((root/'cache').iterdir()).write_bytes(b'poisoned cache')

    def test_bad_download_never_replaces_existing_toolchain(self):
        pin = {'platforms':{'windows-x64':{'url':'https://example.invalid/jdk.zip','sha256':'0'*64}}}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); (root/'jdk').mkdir(); (root/'jdk/good').touch()
            with patch('urllib.request.urlopen',return_value=io.BytesIO(b'corrupt download')):
                with self.assertRaisesRegex(ValueError,'checksum'):
                    toolchain.install('windows-x64',root/'cache',root/'jdk',pin)
            self.assertTrue((root/'jdk/good').exists())
            self.assertEqual(list((root/'cache').iterdir()),[])

    def test_archive_cannot_escape_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); path=root/'bad.zip'; target=root/'out'; target.mkdir()
            with zipfile.ZipFile(path,'w') as archive:
                archive.writestr('../outside','bad')
            with self.assertRaises(ValueError):
                toolchain.extract(path,target)
            self.assertFalse((root/'outside').exists())

    def test_mac_toolchain_keeps_bundle_files_outside_home(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as archive:
            archive.writestr('jdk/Contents/Home/release','release')
            archive.writestr('jdk/Contents/Home/bin/java','java')
            archive.writestr('jdk/Contents/MacOS/libjli.dylib','library')
        raw=data.getvalue(); pin={'platforms':{'macos-arm64':{'url':'https://example.invalid/jdk.zip','sha256':hashlib.sha256(raw).hexdigest()}}}
        with tempfile.TemporaryDirectory() as directory, patch('urllib.request.urlopen',return_value=io.BytesIO(raw)):
            root=Path(directory); home=toolchain.install('macos-arm64',root/'cache',root/'jdk',pin)
            self.assertTrue((home.parent/'MacOS/libjli.dylib').is_file())

    def test_linux_builder_always_takes_bounded_scope(self):
        if sys.platform != 'linux':
            self.skipTest('Linux scope')
        with tempfile.TemporaryDirectory() as directory, patch('fcntl.flock') as lock, patch('subprocess.run') as run, patch.object(sys,'argv',['native_image.py','--work',directory]):
            native_image.main()
            lock.assert_called_once()
            args=run.call_args.args[0]
            for value in ('MemoryMax=16G','MemorySwapMax=2G','CPUWeight=50','--inside-scope'):
                self.assertIn(value,args)

    def test_cross_compile_rejected_before_starting_tools(self):
        with tempfile.TemporaryDirectory() as directory, patch('subprocess.run') as run:
            root=Path(directory)
            with self.assertRaisesRegex(ValueError,'target OS'):
                native_image.build(root,root,'unsupported-target')
            run.assert_not_called()

    def test_windows_archive_requires_matching_helper_and_architecture(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); image=root/'image'; image.mkdir()
            executable(image/'Metrolist.exe')
            helper=image/'resources/windows/metro-wv2-helper.exe';executable(helper)
            checksum=helper.with_suffix('.exe.sha256'); checksum.write_text(hashlib.sha256(helper.read_bytes()).hexdigest())
            result=package.package(image,'windows-x64',root/'dist','14.0.0-test')
            with zipfile.ZipFile(result) as archive:
                self.assertIn('Metrolist.exe',archive.namelist())
                manifest=json.loads(archive.read('install-manifest.json'))
                self.assertEqual(manifest['launcher'],'Metrolist.exe')
                self.assertEqual(manifest['architecture'],'x86_64')
            executable(image/'foreign.dll',0xAA64)
            with self.assertRaisesRegex(ValueError,'x64'):
                package.package(image,'windows-x64',root/'dist','14.0.0-test')
            (image/'foreign.dll').unlink(); checksum.write_text('incorrect')
            with self.assertRaisesRegex(ValueError,'checksum'):
                package.package(image,'windows-x64',root/'dist','14.0.0-test')

    def test_linux_appimage_does_not_replace_jvm_package(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);image=root/'image';posix_executable(image/'Metrolist','linux-x64')
            previous=root/'dist/Metrolist.AppImage';previous.parent.mkdir();previous.write_text('JVM package')
            def pack(command,**_):
                Path(command[-1]).write_text('native package')
            with patch('subprocess.run',side_effect=pack):
                result=package.package(image,'linux-x64',root/'dist','14.0.0-test')
            self.assertEqual(previous.read_text(),'JVM package')
            self.assertNotEqual(result,previous)
            app=root/'dist/metrolist-desktop-native-linux-x64-14.0.0-test.AppDir'
            self.assertTrue(os.access(app/'AppRun',os.X_OK))
            self.assertTrue((app/'resources').is_symlink())

    def test_output_argument_leaves_exe_suffix_to_native_image(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory); (work/'classpath.txt').write_text('app.jar')
            for target, output, expected in [('windows-x64', work/'image/Metrolist.exe', work/'image/Metrolist'),
                                             ('linux-x64', work/'image/Metrolist', work/'image/Metrolist')]:
                command = native_image.arguments(work, work, output, target)
                self.assertEqual(str(expected), command[command.index('-o') + 1])
                self.assertIn('-march=compatibility', command)

    def test_posix_architecture_checks_launcher_libraries_and_sidecars(self):
        for target, foreign, library in [('linux-x64', 'linux-arm64', 'libshim.so'), ('linux-arm64', 'linux-x64', 'libavutil.so.60'), ('macos-arm64', 'linux-arm64', 'libjawt.dylib')]:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                image = Path(directory)
                paths = [image/'Metrolist', image/'lib'/library, image/'resources/sidecar']
                for path in paths:
                    posix_executable(path, target)
                self.assertCountEqual(package.validate_posix(image, target), paths)
                for path in paths:
                    posix_executable(path, foreign)
                    with self.assertRaisesRegex(ValueError, target):
                        package.validate_posix(image, target)
                    posix_executable(path, target)
                (image/'resources/Example.class').write_bytes(b'\xca\xfe\xba\xbe' + bytes(16))
                self.assertCountEqual(package.validate_posix(image, target), paths)
                if target == 'macos-arm64':
                    fat = lambda *types: b'\xca\xfe\xba\xbe' + struct.pack('>I', len(types)) + b''.join(struct.pack('>I', t) + bytes(16) for t in types)
                    paths[1].write_bytes(fat(0x1000007, 0x100000C))
                    self.assertCountEqual(package.validate_posix(image, target), paths)
                    paths[1].write_bytes(fat(0x1000007))
                    with self.assertRaisesRegex(ValueError, target):
                        package.validate_posix(image, target)
                    paths[1].write_bytes(b'\xcf\xfa\xed\xfe' + struct.pack('<I', 0x1000007) + bytes(24))
                    with self.assertRaisesRegex(ValueError, target):
                        package.validate_posix(image, target)
                (image/'Metrolist').write_bytes(b'\x7fELF')
                with self.assertRaises(ValueError):
                    package.validate_posix(image, target)
                (image/'Metrolist').unlink()
                with self.assertRaisesRegex(ValueError, 'no Metrolist'):
                    package.validate_posix(image, target)

    def test_macos_bundle_signs_completed_contents_before_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); image = root/'image'
            for name in ['Metrolist', 'libjawt.dylib', 'lib/libjawt.dylib', 'resources/macos/libsidecar.dylib']:
                posix_executable(image/name, 'macos-arm64')
            (image/'resources/config.txt').write_text('resource')
            signed = []
            def sign(command, **kwargs):
                self.assertTrue(kwargs['check'])
                path = Path(command[-1])
                app = root/'dist/metrolist-desktop-native-macos-arm64-14.0.0-test/Metrolist.app'
                self.assertTrue((app/'Contents/Resources/logo.icns').is_file())
                self.assertTrue((app/'Contents/Info.plist').is_file())
                if '--sign' in command:
                    self.assertNotIn('--deep', command)
                    signed.append(path)
                else:
                    self.assertEqual(command, ['codesign', '--verify', '--deep', '--strict', str(app)])
                    self.assertEqual(signed[-1], app)
            with patch('subprocess.run', side_effect=sign), patch('subprocess.check_output', return_value='Load command 1\n      cmd LC_BUILD_VERSION\n    minos 14.0\n'):
                result = package.package(image, 'macos-arm64', root/'dist', '14.0.0-test')
            self.assertEqual(len(signed), 5)
            with zipfile.ZipFile(result) as archive:
                info = plistlib.loads(archive.read('Metrolist.app/Contents/Info.plist'))
                self.assertTrue(info['NSHighResolutionCapable'])
                self.assertEqual(info['LSMinimumSystemVersion'], '14.0')
                self.assertEqual(info['CFBundleURLTypes'][0]['CFBundleURLSchemes'], ['metrolist', 'metrolist-auth'])
                self.assertEqual(info['NSBonjourServices'], ['_fcast._tcp', '_googlecast._tcp'])
                self.assertEqual(archive.read('Metrolist.app/Contents/Resources/config.txt'), b'resource')
                self.assertTrue(archive.getinfo('Metrolist.app/Contents/MacOS/Metrolist').external_attr >> 16 & 0o111)

    def test_macos_minimum_version_covers_legacy_and_current_load_commands(self):
        with patch('subprocess.check_output', side_effect=[
            'Load command 0\n      cmd LC_BUILD_VERSION\n    minos 11.0\n      sdk 15.0\n',
            'Load command 1\n      cmd LC_VERSION_MIN_MACOSX\n  version 12.3\n      sdk 14.0\n',
        ]):
            self.assertEqual(package.macos_minimum_version([Path('launcher'), Path('library')]), '12.3')
        with patch('subprocess.check_output', return_value='Load command 0\n      cmd LC_UUID\n'):
            with self.assertRaisesRegex(ValueError, 'minimum system version'):
                package.macos_minimum_version([Path('launcher')])

    def test_target_metadata_retains_platform_native_resources(self):
        for target, natives in {
            'macos-arm64': ['libskiko-macos-arm64.dylib', 'com/sun/jna/darwin-aarch64/libjnidispatch.jnilib', 'org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib', 'org/bytedeco/ffmpeg/macosx-arm64/libavcodec.dylib', 'org/bytedeco/javacpp/macosx-arm64/libjnijavacpp.dylib', 'darwin-aarch64/libcomposewebview_wry.dylib'],
            'linux-arm64': ['libskiko-linux-arm64.so', 'org/sqlite/native/Linux/aarch64/libsqlitejdbc.so', 'linux-aarch64/libcomposewebview_wry.so'],
        }.items():
            resources = []
            for directory in native_image.configurations(target, None):
                metadata = json.loads((directory/'reachability-metadata.json').read_text())
                resources.extend(item['glob'] for item in metadata.get('resources', []) if 'glob' in item)
            for name in natives:
                self.assertTrue(any(fnmatch.fnmatchcase(name, glob) for glob in resources), (target, name))


if __name__=='__main__':
    unittest.main()
