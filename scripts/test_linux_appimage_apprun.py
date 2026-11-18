"""Check persistent AppImage launchers without starting a window or a JVM."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


class AppRunTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / 'mounted app'
        (self.app / 'bin').mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name('linux-appimage-apprun.sh'), self.app / 'AppRun')
        self.image = self.root / 'Metrolist space % " $ ` \\.AppImage'
        self.image.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$TEST_LAUNCH_LOG"\n')
        self.image.chmod(0o755)
        shutil.copyfile(self.image, self.app / 'bin/Metrolist')
        (self.app / 'bin/Metrolist').chmod(0o755)
        (self.app / 'Metrolist.desktop').write_text(
            '[Desktop Entry]\nType=Application\nName=Metrolist\nExec=Metrolist %u\n'
            'Icon=Metrolist\nStartupWMClass=Metrolist\nTerminal=false\n'
            'MimeType=x-scheme-handler/metrolist;x-scheme-handler/metrolist-auth;\n'
        )
        (self.app / 'Metrolist.png').write_bytes(b'icon')
        self.data = self.root / 'user data'
        self.log = self.root / 'launched'
        self.env = {
            **os.environ, 'HOME': str(self.root / 'home'), 'APPIMAGE': str(self.image),
            'XDG_DATA_HOME': str(self.data), 'XDG_DATA_DIRS': str(self.root / 'system data'),
            'TEST_LAUNCH_LOG': str(self.log),
        }
        self.env.pop('APPDIR', None)

    def launch(self):
        result = subprocess.run(['sh', str(self.app / 'AppRun'), 'argument with spaces'],
                                env=self.env, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('argument with spaces\n', self.log.read_text())
        return result

    def test_pin_relaunches_after_mount_is_removed_and_keeps_uri_arguments(self):
        self.launch()
        entry = self.data / 'applications/Metrolist.desktop'
        self.assertTrue(entry.is_file())
        self.assertEqual(b'icon', (self.data / 'icons/hicolor/512x512/apps/Metrolist.png').read_bytes())
        self.assertNotIn(str(self.app), entry.read_text())
        self.assertIn('StartupWMClass=Metrolist\n', entry.read_text())
        shutil.rmtree(self.app)
        self.log.unlink()
        if not shutil.which('gio'):
            self.skipTest('gio is needed to exercise desktop Exec escaping')
        result = subprocess.run(['gio', 'launch', str(entry), 'metrolist://test/'],
                                env=self.env, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        deadline = time.monotonic() + 2
        while not self.log.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertEqual('metrolist://test/\n', self.log.read_text())

    def test_respects_system_package_desktop_entry(self):
        directory = Path(self.env['XDG_DATA_DIRS']) / 'applications'
        directory.mkdir(parents=True)
        (directory / 'Metrolist.desktop').write_text('system entry')
        self.launch()
        self.assertFalse(self.data.exists())

    def test_extracted_packages_do_not_install_an_appimage_entry(self):
        del self.env['APPIMAGE']
        self.launch()
        self.assertFalse(self.data.exists())

    def test_relative_data_home_uses_home_and_relaunch_updates_moved_image(self):
        self.env['XDG_DATA_HOME'] = 'relative'
        self.launch()
        entry = Path(self.env['HOME']) / '.local/share/applications/Metrolist.desktop'
        self.assertTrue(entry.is_file())
        moved = self.root / 'moved.AppImage'
        self.image.rename(moved)
        self.env['APPIMAGE'] = str(moved)
        self.launch()
        self.assertIn(f'Exec=env "{moved}" %u\n', entry.read_text())

    def test_failed_registration_does_not_block_launch(self):
        self.data.write_text('not a directory')
        self.assertIn('could not install', self.launch().stderr)

    def test_rejects_line_breaks_in_desktop_values(self):
        for suffix in ('\nExec=bad', '\rExec=bad'):
            with self.subTest(suffix=suffix):
                self.env['APPIMAGE'] = str(self.image) + suffix
                self.assertIn('could not install', self.launch().stderr)
                self.assertFalse(self.data.exists())


if __name__ == '__main__':
    unittest.main()
