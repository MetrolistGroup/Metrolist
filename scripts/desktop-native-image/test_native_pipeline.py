"""Check downloads, package boundaries and build isolation without a compiler or GUI."""

import fnmatch
import hashlib
import io
import json
import os
from pathlib import Path
import plistlib
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import native_image
import package
import toolchain
from verify_linux_audio import verify_linux_audio


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


def macos_jdk(graal):
    for library in package.MACOS_JDK_DYLIBS:
        posix_executable(graal/'lib'/library, 'macos-arm64')
    (graal/'lib/fontconfig.bfc').write_bytes(b'font configuration')
    (graal/'lib/fontconfig.properties.src').write_text('# JDK font configuration\nversion=1\n')
    (graal/'lib/unrelated.txt').write_text('not bundled')


class PipelineTest(unittest.TestCase):
    def test_windows_registers_the_jna_callback_exception_handler(self):
        metadata_directory = native_image.METADATA / 'windows'
        self.assertIn(metadata_directory, native_image.configurations('windows-x64', agent=None))
        metadata = json.loads((metadata_directory / 'reachability-metadata.json').read_text())
        registration = next(
            entry for entry in metadata['reflection']
            if entry['type'] == 'com.sun.jna.Callback$UncaughtExceptionHandler'
        )
        self.assertTrue(registration['jniAccessible'])
        self.assertEqual(
            [{'name': 'uncaughtException', 'parameterTypes': ['com.sun.jna.Callback', 'java.lang.Throwable']}],
            registration['methods'],
        )

    def test_platform_natives_use_nucleus_and_javacpp_platform_names(self):
        with tempfile.TemporaryDirectory() as directory:
            for target, nucleus, javacpp in [('linux-arm64', 'linux-aarch64', 'linux-arm64'), ('macos-arm64', 'darwin-aarch64', 'macosx-arm64'),
                                              ('windows-x64', 'win32-x64', 'windows-x86_64')]:
                path = native_image.platform_natives(Path(directory), target) / 'reachability-metadata.json'
                globs = [entry['glob'] for entry in json.loads(path.read_text())['resources']]
                self.assertEqual([f'nucleus/native/{nucleus}/*', f'org/{javacpp}/*', f'org/{javacpp}-gpl/*', f'{javacpp}/*', f'{javacpp}-gpl/*',
                                  native_image.JAVACPP_INDEX], globs)

    def test_javacpp_index_lists_bundled_native_names_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'ffmpeg-windows.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('org/bytedeco/ffmpeg/windows-x86_64-gpl/jniavutil.dll', b'')
                archive.writestr('org/bytedeco/ffmpeg/global/avutil.class', b'')
                archive.writestr('org/bytedeco/ffmpeg/windows-x86_64-gpl/', b'')
                archive.writestr('com/example/windows-x86_64/other.dll', b'')
            classes = root / 'classes/org/bytedeco/javacpp/windows-x86_64'
            classes.mkdir(parents=True)
            (classes / 'jnijavacpp.dll').write_bytes(b'')
            native_image.javacpp_resource_index(os.pathsep.join([str(jar), str(root / 'classes'), str(root / 'missing.jar')]), root)
            self.assertEqual(['jniavutil.dll', 'jnijavacpp.dll', 'windows-x86_64-gpl/jniavutil.dll', 'windows-x86_64/jnijavacpp.dll'],
                             (root / native_image.JAVACPP_INDEX).read_text().splitlines())

    def test_windows_resources_declare_windows_10_and_link_into_the_image(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            with patch('subprocess.run') as run:
                res = native_image.windows_resources(work, '14.2.1+abcdef0', rc='rc.exe')
            run.assert_called_once()
            self.assertEqual(['rc.exe', '/nologo', '/fo', str(res)], run.call_args.args[0][:4])
            manifest = (work/'windows-resources/metrolist.manifest').read_text()
            self.assertIn('{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}', manifest)
            self.assertIn('PerMonitorV2', manifest)
            rc = (work/'windows-resources/metrolist.rc').read_text()
            self.assertIn('FILEVERSION 14,2,1,0', rc)
            self.assertIn('"ProductVersion", "14.2.1+abcdef0"', rc)
            self.assertIn('logo.ico', rc)
            (work/'classpath.txt').write_text('app.jar')
            command = native_image.arguments(work, work, work/'image/Metrolist.exe', 'windows-x64')
            self.assertIn(f'-H:NativeLinkerOption={work / "windows-resources/metrolist.res"}', command)
            linux = native_image.arguments(work, work, work/'image/Metrolist', 'linux-x64')
            self.assertFalse(any('metrolist.res' in arg for arg in linux))
            # Main.kt renames the X11 window class through this package.
            self.assertIn('--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED', linux)
            self.assertNotIn('--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED', command)

    def test_output_argument_leaves_exe_suffix_to_native_image(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory); (work/'classpath.txt').write_text('app.jar')
            for target, output, expected in [('windows-x64', work/'image/Metrolist.exe', work/'image/Metrolist'),
                                             ('linux-x64', work/'image/Metrolist', work/'image/Metrolist')]:
                command = native_image.arguments(work, work, output, target)
                self.assertEqual(str(expected), command[command.index('-o') + 1])
                self.assertIn('-march=compatibility', command)
                metadata = json.loads((work/'platform-natives/reachability-metadata.json').read_text())
                self.assertEqual({'glob': f'nucleus/native/{target.replace("windows", "win32")}/*'}, metadata['resources'][0])

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
            self.assertEqual((package.ROOT/'scripts/linux-appimage-apprun.sh').read_text(), (app/'AppRun').read_text())
            self.assertTrue((app/'resources').is_symlink())

    def test_linux_packages_reject_host_audio_libraries(self):
        for name in ('libasound.so', 'libasound.so.2', 'libpulse.so.0', 'libpulse-simple.so.0',
                     'libpulsecommon-17.0.so', 'libjack.so.0', 'libpipewire-0.3.so.0', 'libspa-0.2.so'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                image = root / 'image'
                posix_executable(image / 'Metrolist', 'linux-x64')
                library = image / 'lib' / name
                posix_executable(library, 'linux-x64')
                with self.assertRaisesRegex(ValueError, 'host audio libraries'):
                    package.package(image, 'linux-x64', root / 'dist', '14.0.0-test')
                library.unlink()
                library.symlink_to('missing-system-library')
                with self.assertRaisesRegex(ValueError, 'host audio libraries'):
                    verify_linux_audio([image])

    def test_linux_verifier_checks_jars_and_tarballs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'ffmpeg.jar'
            for library in ('libavcodec.so.62', 'libasound.so.2'):
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr(f'org/bytedeco/ffmpeg/linux-arm64/{library}', b'native')
                tar = root / 'Metrolist.tar.gz'
                with tarfile.open(tar, 'w:gz') as archive:
                    archive.add(jar, arcname='lib/app/ffmpeg.jar')
                for source in (jar, tar):
                    if library.startswith('libasound'):
                        with self.assertRaisesRegex(ValueError, 'libasound.so.2'):
                            verify_linux_audio([source])
                    else:
                        verify_linux_audio([source])
                jar.unlink()
                tar.unlink()
            with tarfile.open(tar, 'w:gz') as archive:
                archive.addfile(tarfile.TarInfo('lib/libasound.so.2'))
            with self.assertRaisesRegex(ValueError, 'libasound.so.2'):
                verify_linux_audio([tar])
            with self.assertRaisesRegex(ValueError, 'does not exist'):
                verify_linux_audio([root / 'missing'])

    def test_linux_native_build_rejects_audio_libraries_before_compilation(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            jar = work / 'ffmpeg.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('org/bytedeco/ffmpeg/linux-arm64/libasound.so.2', b'native')
            (work / 'classpath.txt').write_text(str(jar))
            for target in ('linux-x64', 'linux-arm64'):
                with patch.object(native_image, 'host_platform', return_value=target), patch('subprocess.run') as run:
                    with self.assertRaisesRegex(ValueError, 'libasound.so.2'):
                        native_image.build(work, work, target)
                    run.assert_not_called()

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
            graal = root/'graal'; macos_jdk(graal)
            (image/'libjvm.dylib').write_bytes((graal/'lib/server/libjvm.dylib').read_bytes() + b'generated shim')
            (image/'libjawt.dylib').write_bytes((graal/'lib/libjawt.dylib').read_bytes() + b'generated jawt')
            (graal/'lib/libmlib_image.dylib').unlink()
            signed = []
            edited = []
            def sign(command, **kwargs):
                self.assertTrue(kwargs['check'])
                path = Path(command[-1])
                app = root/'dist/metrolist-desktop-native-macos-arm64-14.0.0-test/Metrolist.app'
                if command[0] == 'install_name_tool':
                    self.assertEqual(command[1:4], ['-add_rpath', '@executable_path/.', str(app/'Contents/MacOS/Metrolist')])
                    self.assertFalse(signed)
                    edited.append(path)
                    return
                self.assertTrue((app/'Contents/Resources/logo.icns').is_file())
                self.assertTrue((app/'Contents/Info.plist').is_file())
                if '--sign' in command:
                    self.assertNotIn('--deep', command)
                    self.assertEqual(edited, [app/'Contents/MacOS/Metrolist'])
                    if path == app/'Contents/MacOS/Metrolist':
                        self.assertIn(app/'Contents/MacOS/lib/libjawt.dylib', signed)
                        self.assertIn(app/'Contents/MacOS/libawt.dylib', signed)
                    signed.append(path)
                else:
                    self.assertEqual(command, ['codesign', '--verify', '--deep', '--strict', str(app)])
                    self.assertEqual(signed[-1], app)
            with patch('subprocess.run', side_effect=sign), patch('subprocess.check_output', return_value='Load command 1\n      cmd LC_BUILD_VERSION\n    minos 14.0\n'):
                result = package.package(image, 'macos-arm64', root/'dist', '14.0.0-test', graal)
            self.assertEqual(len(signed), len(package.MACOS_JDK_DYLIBS) + 3)
            self.assertEqual(signed[-2].name, 'Metrolist')
            with zipfile.ZipFile(result) as archive:
                info = plistlib.loads(archive.read('Metrolist.app/Contents/Info.plist'))
                self.assertTrue(info['NSHighResolutionCapable'])
                self.assertEqual(info['LSMinimumSystemVersion'], '14.0')
                self.assertEqual(info['CFBundleURLTypes'][0]['CFBundleURLSchemes'], ['metrolist', 'metrolist-auth'])
                self.assertEqual(info['NSBonjourServices'], ['_fcast._tcp', '_googlecast._tcp'])
                self.assertEqual(archive.read('Metrolist.app/Contents/Resources/config.txt'), b'resource')
                self.assertEqual(archive.read('Metrolist.app/Contents/Resources/fontconfig.properties'),
                                 (graal/'lib/fontconfig.properties.src').read_bytes())
                self.assertFalse(any(name.endswith(('fontconfig.bfc', 'fontconfig.properties.src')) for name in archive.namelist()))
                self.assertEqual(archive.read('Metrolist.app/Contents/MacOS/libjvm.dylib'), (image/'libjvm.dylib').read_bytes())
                self.assertEqual(archive.read('Metrolist.app/Contents/MacOS/libjawt.dylib'), (image/'libjawt.dylib').read_bytes())
                for library in package.MACOS_JDK_DYLIBS:
                    if library != 'libmlib_image.dylib':
                        self.assertIn('Metrolist.app/Contents/MacOS/' + Path(library).name, archive.namelist())
                self.assertFalse(any(name.endswith(('unrelated.txt', 'libmlib_image.dylib')) for name in archive.namelist()))
                self.assertEqual(archive.read('Metrolist.app/Contents/MacOS/lib/libjawt.dylib'), (graal/'lib/libjawt.dylib').read_bytes())
                self.assertTrue(archive.getinfo('Metrolist.app/Contents/MacOS/Metrolist').external_attr >> 16 & 0o111)

    def test_macos_rejects_missing_awt_foreign_libraries_and_data_before_signing(self):
        for failure in ('no-graal', 'no-awt', 'no-fontconfig-properties', 'foreign', 'data'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); image = root/'image'; graal = root/'graal'
                posix_executable(image/'Metrolist', 'macos-arm64')
                (image/'resources').mkdir()
                macos_jdk(graal)
                if failure == 'no-awt':
                    (graal/'lib/libawt.dylib').unlink()
                elif failure == 'no-fontconfig-properties':
                    (graal/'lib/fontconfig.properties.src').unlink()
                elif failure == 'foreign':
                    posix_executable(graal/'lib/libawt.dylib', 'linux-arm64')
                elif failure == 'data':
                    (image/'fontconfig.bfc').write_text('data in code directory')
                with patch('subprocess.run') as run, self.assertRaises(ValueError):
                    package.package(image, 'macos-arm64', root/'dist', '14.0.0', None if failure == 'no-graal' else graal)
                run.assert_not_called()
                self.assertEqual(list((root/'dist').glob('*.zip')), [])

    def test_macos_link_and_sign_failures_do_not_produce_an_archive(self):
        for tool in ('install_name_tool', 'codesign'):
            with self.subTest(tool=tool), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); image = root/'image'; graal = root/'graal'
                posix_executable(image/'Metrolist', 'macos-arm64')
                (image/'resources').mkdir(); macos_jdk(graal)
                def fail(command, **kwargs):
                    if command[0] == tool:
                        raise subprocess.CalledProcessError(1, command)
                with patch('subprocess.run', side_effect=fail), patch('subprocess.check_output', return_value='Load command 1\n      cmd LC_BUILD_VERSION\n    minos 14.0\n'):
                    with self.assertRaises(subprocess.CalledProcessError):
                        package.package(image, 'macos-arm64', root/'dist', '14.0.0', graal)
                self.assertEqual(list((root/'dist').glob('*.zip')), [])

    def test_macos_existing_executable_rpath_is_not_added_twice(self):
        for path in ('@executable_path/.', '@executable_path/'):
            with self.subTest(path=path), patch('subprocess.run') as run, patch('subprocess.check_output', return_value=f'Load command 0\n      cmd LC_RPATH\n  cmdsize 48\n     path {path} (offset 12)\n'):
                package.macos_rpath(Path('Metrolist'))
                run.assert_not_called()

    def test_macos_arguments_pin_link_target_and_keep_compatibility(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            work = Path(directory); (work/'classpath.txt').write_text('app.jar')
            with patch('subprocess.check_output', return_value='Load command 1\n      cmd LC_BUILD_VERSION\n    minos 11.0\n') as otool:
                command = native_image.arguments(work/'graal', work, work/'Metrolist', 'macos-arm64')
                otool.assert_called_once_with(['otool', '-l', str(work/'graal/lib/libawt.dylib')], text=True)
            self.assertIn('-march=compatibility', command)
            self.assertIn('-H:NativeLinkerOption=-mmacosx-version-min=11.0', command)
            self.assertIn(f'-H:NativeLinkerOption={work / "macos-awt.o"}', command)
            self.assertIn('-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED', command)
            self.assertIn('--features=com.metrolist.desktop.nativeimage.MacosAwtFeature', command)
            with patch.dict(os.environ, MACOSX_DEPLOYMENT_TARGET='13.2'), patch('subprocess.check_output') as otool:
                command = native_image.arguments(work/'graal', work, work/'Metrolist', 'macos-arm64')
                self.assertIn('-H:NativeLinkerOption=-mmacosx-version-min=13.2', command)
                otool.assert_not_called()
            for target in ('linux-x64', 'linux-arm64', 'windows-x64'):
                command = native_image.arguments(work/'graal', work, work/'Metrolist', target)
                self.assertFalse(any('macos' in option or 'MacosAwt' in option for option in command))

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
