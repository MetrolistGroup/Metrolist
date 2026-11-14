#!/usr/bin/env python3
"""Check release provenance, recipe rendering and publishing gates without network."""

import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from generate_distribution import generate, release_assets, REPOSITORY
from propose_distribution import propose, run


class DistributionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.assets = self.root / 'assets'
        self.assets.mkdir()
        self.output = self.root / 'output'
        self.version = '14.0.0'
        names = release_assets(self.version).values()
        checksums = []
        for name in names:
            content = name.encode()
            (self.assets / name).write_bytes(content)
            checksums.append(f'{hashlib.sha256(content).hexdigest()}  {name}')
        (self.assets / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n')
        self.release = {
            'tagName': 'v14.0.0', 'isDraft': False, 'isPrerelease': False,
            'url': f'https://github.com/{REPOSITORY}/releases/tag/v14.0.0',
            'publishedAt': '2026-10-31T12:00:00Z',
            'body': 'Fix playback & <login>.\n\nPreserve playlists.',
            'assets': [{'name': name} for name in names],
        }

    def generate(self, screenshots=()):
        generate(self.assets, self.release, self.output, screenshots)

    def test_rendered_recipes_use_verified_assets_and_actual_notes(self):
        self.generate([{'url': 'https://example.org/desktop.png', 'caption': 'Music library'}])
        installer = next((self.output / 'winget').rglob('*.installer.yaml')).read_text()
        self.assertIn('--silent --launch=false', installer)
        self.assertIn('metrolist-installer-windows-x64-14.0.0.exe', installer)
        self.assertNotIn('Metrolist-Desktop-windows.zip', installer)
        data = json.loads((self.output / 'nix/packaging/release.json').read_text())
        self.assertEqual(set(data['linux']), {'x86_64-linux', 'aarch64-linux'})
        for asset in data['linux'].values():
            self.assertEqual(asset['sha256'], hashlib.sha256((self.assets / asset['name']).read_bytes()).hexdigest())
            self.assertTrue(asset['hash'].startswith('sha256-'))
        flatpak = json.loads((self.output / 'flatpak/com.metrolist.desktop.json').read_text())
        self.assertNotIn('build-options', flatpak)
        sources = flatpak['modules'][0]['sources']
        self.assertEqual([source['only-arches'] for source in sources if 'url' in source], [['x86_64'], ['aarch64']])
        self.assertNotIn('--own-name=org.mpris.MediaPlayer2.*', flatpak['finish-args'])
        tree = ET.parse(self.output / 'flatpak/com.metrolist.desktop.metainfo.xml')
        self.assertEqual(tree.find('./releases/release/description/p').text, 'Fix playback & <login>.')
        self.assertEqual(tree.find('./releases/release').get('date'), '2026-10-31')
        self.assertEqual(tree.find('./screenshots/screenshot').get('type'), 'default')
        self.assertIn('not approved for Flathub', (self.output / 'flatpak/SUBMISSION-NOTES.txt').read_text())
        self.assertFalse((self.output / 'aur/PKGBUILD').exists())
        if shutil.which('ruby'):
            subprocess.run(['ruby', '-c', str(self.output / 'homebrew/Casks/metrolist.rb')], check=True, capture_output=True)

    def test_rejects_unpublished_wrong_repository_and_nonstable_releases(self):
        for key, value in [('tagName', 'v14.0.0-rc7'), ('tagName', '../14.0.0'),
                           ('isDraft', True), ('isPrerelease', True),
                           ('url', 'https://github.com/other/repo/releases/tag/v14.0.0'),
                           ('body', ''), ('publishedAt', '2026-10-31')]:
            with self.subTest(key=key, value=value), patch.dict(self.release, {key: value}):
                with self.assertRaises(ValueError):
                    self.generate()
                self.assertFalse(self.output.exists())

    def test_rejects_checksum_mismatch_missing_asset_and_duplicate_sum(self):
        name = next(iter(release_assets(self.version).values()))
        file = self.assets / name
        for content in (b'corrupt', b'', None):
            if content is None:
                file.unlink()
            else:
                file.write_bytes(content)
            with self.assertRaises(ValueError):
                self.generate()
            self.assertFalse(self.output.exists())
        sums = self.assets / 'SHA256SUMS'
        sums.write_text(sums.read_text() * 2)
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.generate()

    def test_does_not_replace_existing_output(self):
        self.generate()
        sentinel = self.output / 'keep'
        sentinel.write_text('user work')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.generate()
        self.assertEqual(sentinel.read_text(), 'user work')

    def test_invalid_screenshot_does_not_leave_partial_output(self):
        with self.assertRaises(ValueError):
            self.generate([{'url': 'file:///private.png', 'caption': 'Library'}])
        self.assertFalse(self.output.exists())

    def test_missing_token_or_repository_does_not_call_external_tools(self):
        with patch('propose_distribution.run') as run:
            with patch.dict(os.environ, {'GH_TOKEN': ''}):
                propose('homebrew', self.output, 'owner/tap', self.version)
            with patch.dict(os.environ, {'GH_TOKEN': 'test'}):
                propose('homebrew', self.output, '', self.version)
            run.assert_not_called()

    def test_private_source_cannot_be_published(self):
        with patch.dict(os.environ, {'GH_TOKEN': 'test'}), patch('propose_distribution.run', return_value='true') as run:
            with self.assertRaisesRegex(ValueError, 'private'):
                propose('nix', self.output, 'owner/packages', self.version)
            self.assertEqual(run.call_count, 1)

    def test_proposals_use_review_branch_and_retries_do_not_duplicate_prs(self):
        self.generate()
        for state in ("", "OPEN", "MERGED", "CLOSED"):
            calls = []

            def fake_run(*args, cwd=None):
                calls.append(args)
                if args[:3] == ("gh", "repo", "view"):
                    return "false"
                if args[:3] == ("gh", "pr", "list"):
                    return state
                if "clone" in args:
                    Path(args[-1]).mkdir()
                    return ""
                if "symbolic-ref" in args:
                    return "origin/main"
                if "ls-remote" in args:
                    return "abc refs/heads/build/metrolist-14.0.0" if state == "OPEN" else ""
                if "diff" in args:
                    return "Casks/metrolist.rb"
                if args[:3] == ("gh", "pr", "create"):
                    self.assertIn("--draft", args)
                    self.assertEqual(args[args.index("--base") + 1], "main")
                    body = Path(args[args.index("--body-file") + 1]).read_text()
                    self.assertIn("14.0.0", body)
                return ""

            with self.subTest(state=state), patch.dict(os.environ, {"GH_TOKEN": "test"}), patch('propose_distribution.run', side_effect=fake_run):
                propose('homebrew', self.output, 'owner/tap', self.version)
            pushes = [args for args in calls if 'push' in args]
            creations = [args for args in calls if args[:3] == ('gh', 'pr', 'create')]
            self.assertEqual(len(pushes), int(state in ('', 'OPEN')))
            self.assertEqual(len(creations), int(state == ''))
            for args in pushes:
                self.assertEqual(args[-2:], ('origin', 'build/metrolist-14.0.0'))
                self.assertFalse(any(arg.startswith('--force') for arg in args))

    def test_sparse_checkout_can_create_and_update_a_review_branch(self):
        self.generate()
        remote = self.root / "remote.git"
        seed = self.root / "seed"
        run("git", "init", "--bare", "--initial-branch=main", str(remote))
        run("git", "init", "--initial-branch=main", str(seed))
        (seed / "unrelated").write_text("keep this file")
        run("git", "add", ".", cwd=seed)
        run("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "commit", "-m", "test: seed packaging target", cwd=seed)
        run("git", "remote", "add", "origin", remote.as_uri(), cwd=seed)
        run("git", "push", "origin", "main", cwd=seed)
        created = []
        state = ""

        def local_run(*args, cwd=None):
            if args[:3] == ("gh", "repo", "view"):
                return "false"
            if args[:3] == ("gh", "pr", "list"):
                return state
            if args[:3] == ("gh", "pr", "create"):
                created.append(args)
                return ""
            if "clone" in args:
                args = tuple(remote.as_uri() if arg == "https://github.com/owner/tap.git" else arg for arg in args)
            if "commit" in args:
                self.assertFalse((cwd / "unrelated").exists())
            return run(*args, cwd=cwd)

        with patch.dict(os.environ, {"GH_TOKEN": "test"}), patch('propose_distribution.run', side_effect=local_run):
            propose('homebrew', self.output, 'owner/tap', self.version)
            state = "OPEN"
            cask = self.output / "homebrew/Casks/metrolist.rb"
            cask.write_text(cask.read_text() + "# Updated recipe\n")
            propose('homebrew', self.output, 'owner/tap', self.version)
        self.assertEqual(len(created), 1)
        branch = "build/metrolist-14.0.0"
        self.assertIn("# Updated recipe", run("git", "show", f"{branch}:Casks/metrolist.rb", cwd=remote))
        self.assertEqual(run("git", "show", f"{branch}:unrelated", cwd=remote), "keep this file")
        self.assertEqual(run("git", "rev-list", "--count", f"main..{branch}", cwd=remote), "2")

    def test_flatpak_requires_exception_and_screenshots(self):
        self.generate()
        with patch.dict(os.environ, {'GH_TOKEN': 'test', 'FLATPAK_BINARY_EXCEPTION': ''}), patch('propose_distribution.run', return_value='false') as run:
            propose('flatpak', self.output, 'owner/flatpak', self.version)
            self.assertEqual(run.call_count, 1)
        with patch.dict(os.environ, {'GH_TOKEN': 'test', 'FLATPAK_BINARY_EXCEPTION': 'true'}), patch('propose_distribution.run', return_value='false'):
            with self.assertRaisesRegex(ValueError, 'screenshots'):
                propose('flatpak', self.output, 'owner/flatpak', self.version)


if __name__ == '__main__':
    unittest.main()
