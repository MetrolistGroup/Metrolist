"""Check downloads, package boundaries and build isolation without a compiler or GUI."""

import hashlib
import io
import json
import os
from pathlib import Path
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
            root=Path(directory);image=root/'image';image.mkdir();(image/'Metrolist').write_text('binary')
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


if __name__=='__main__':
    unittest.main()
