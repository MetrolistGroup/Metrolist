"""Check nightly build routing and publication without GitHub or signing credentials."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

from test_release_workflows import ROOT, step_script

WORKFLOW = ROOT / '.github/workflows/nightly.yml'


class NightlyWorkflowTest(unittest.TestCase):
    def test_triggers_permissions_and_build_dependencies(self):
        workflow = WORKFLOW.read_text()
        events = workflow.split('on:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(['push', 'workflow_dispatch'], re.findall(r'^  (\w+):', events, re.MULTILINE))
        self.assertIn('branches: [main]', events)
        self.assertEqual(
            ['docs/**', '**/*.md', 'fastlane/**', 'assets/**'],
            re.findall(r"^      - '([^']+)'$", events, re.MULTILINE),
        )
        self.assertIn('concurrency:\n  group: nightly\n  cancel-in-progress: true', workflow)
        self.assertIn('permissions:\n  contents: read', workflow)
        jobs = dict(re.findall(r'(?ms)^  ([\w-]+):\n(.*?)(?=^  [\w-]+:|\Z)', workflow.split('jobs:\n', 1)[1]))
        self.assertEqual({'version', 'android', 'linux', 'windows', 'publish'}, jobs.keys())
        self.assertIn('needs: [version, android, linux, windows]', jobs['publish'])
        self.assertIn("if: github.ref == 'refs/heads/main'", jobs['version'])
        self.assertNotIn('    if:', jobs['publish'])
        self.assertIn('contents: write', jobs['publish'])
        self.assertNotIn('secrets.', jobs['publish'])
        for job in ('android', 'linux', 'windows'):
            self.assertIn('needs: version', jobs[job])
            self.assertIn('app-version: ${{ needs.version.outputs.value }}', jobs[job])
            self.assertIn('contents: read', jobs[job])
            self.assertNotIn('contents: write', jobs[job])
        android = jobs['android']
        self.assertIn('uses: ./.github/workflows/reusable-android-build.yml', android)
        for option in ('variant: release', 'require-signing: true', 'upload-artifact: true', 'artifact-name: nightly-android'):
            self.assertIn(option, android)
        for secret in ('KEYSTORE_PATH', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD'):
            self.assertIn(f'{secret}: ${{{{ secrets.{secret}', android)
        for job in ('linux', 'windows'):
            self.assertIn('uses: ./.github/workflows/reusable-desktop-native.yml', jobs[job])
            self.assertIn(f'platform: {job}-x64', jobs[job])
        downloads = re.findall(r'(?ms)uses: actions/download-artifact@[^\n]+\n        with:\n          name: ([^\n]+)', jobs['publish'])
        self.assertEqual(['nightly-android', 'desktop-native-linux-x64', 'desktop-native-windows-x64'], downloads)
        self.assertIn('fetch-depth: 0', jobs['publish'])
        self.assertIn('persist-credentials: false', jobs['publish'])
        for action in re.findall(r'uses: (actions/[^\s]+)', workflow):
            self.assertRegex(action, r'@[0-9a-f]{40}$')

    def test_version_adds_commit_build_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for declared in ('14.0.0', '14.1.0-rc1', '14.0.0+old'):
                (root / 'gradle.properties').write_text(f'org.gradle.caching=true\nappVersion={declared}\n')
                output = root / 'output'
                output.write_text('')
                result = self.run_step('Add the commit as build metadata', root, GITHUB_OUTPUT=str(output))
                self.assertEqual(0, result.returncode, result.stderr)
                value = output.read_text().strip().removeprefix('value=')
                self.assertEqual(declared.split('+')[0] + '+abcdef0', value)

    def run_step(self, step, directory, **env):
        return subprocess.run(
            ['bash', '-c', step_script('nightly.yml', step)], cwd=directory,
            env={**os.environ, 'GITHUB_SHA': 'abcdef0123456789', 'GITHUB_RUN_NUMBER': '42',
                 'GITHUB_REPOSITORY': 'test/repo', 'GH_REPO': 'test/repo',
                 'RUNNER_TEMP': str(directory), 'APP_VERSION': '14.0.0+abcdef0', **env},
            capture_output=True, text=True,
        )

    def test_exactly_three_named_assets_exclude_mapping_and_evidence(self):
        for invalid in (None, 'missing', 'extra', 'empty'):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for name, content in {
                    'android/Metrolist.apk': b'signed apk',
                    'android/Metrolist-KMP-mapping.txt': b'mapping',
                    'linux/native.AppImage': b'linux',
                    'windows/native.zip': b'windows',
                }.items():
                    path = root / 'artifacts' / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(content)
                if invalid == 'missing':
                    (root / 'artifacts/linux/native.AppImage').unlink()
                elif invalid == 'extra':
                    (root / 'artifacts/windows/extra.zip').write_bytes(b'extra')
                elif invalid == 'empty':
                    (root / 'artifacts/android/Metrolist.apk').write_bytes(b'')
                result = self.run_step('Prepare nightly assets', root)
                if invalid:
                    self.assertNotEqual(0, result.returncode)
                    self.assertFalse((root / 'nightly-assets').exists())
                else:
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual({
                        'Metrolist.apk': b'signed apk',
                        'metrolist-desktop-linux-x64-14.0.0+abcdef0.AppImage': b'linux',
                        'Metrolist-Desktop-windows.zip': b'windows',
                    }, {p.name: p.read_bytes() for p in (root / 'nightly-assets').iterdir()})

    def git(self, directory, *args):
        return subprocess.check_output(['git', *args], cwd=directory, text=True).strip()

    def test_notes_use_previous_tag_first_parent_and_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.git(root, 'init', '-q', '-b', 'main')
            self.git(root, 'config', 'user.email', 'nightly@example.invalid')
            self.git(root, 'config', 'user.name', 'Nightly test')
            self.git(root, 'commit', '-q', '--allow-empty', '-m', 'previous nightly')
            self.git(root, 'tag', '-a', 'nightly', '-m', 'previous nightly')
            for index in range(52):
                self.git(root, 'commit', '-q', '--allow-empty', '-m', f'main change {index}')
            self.git(root, 'checkout', '-q', '-b', 'feature')
            self.git(root, 'commit', '-q', '--allow-empty', '-m', 'side branch detail')
            self.git(root, 'checkout', '-q', 'main')
            self.git(root, 'merge', '-q', '--no-ff', 'feature', '-m', 'merge feature')
            sha = self.git(root, 'rev-parse', 'HEAD')
            result = self.run_step('Write nightly notes', root, GITHUB_SHA=sha)
            self.assertEqual(0, result.returncode, result.stderr)
            notes = (root / 'nightly-notes.md').read_text()
            self.assertTrue(notes.startswith(f'Version 14.0.0+abcdef0, built from [{sha}](https://github.com/test/repo/commit/{sha})'))
            self.assertEqual(50, len(re.findall(r'^\* ', notes, re.MULTILINE)))
            self.assertIn('merge feature', notes)
            self.assertNotIn('side branch detail', notes)
            self.assertNotIn('* previous nightly', notes)
            self.git(root, 'tag', '-f', 'nightly', sha)
            self.assertEqual(0, self.run_step('Write nightly notes', root, GITHUB_SHA=sha).returncode)
            self.assertNotIn('* ', (root / 'nightly-notes.md').read_text())
            self.git(root, 'tag', '-d', 'nightly')
            self.assertEqual(0, self.run_step('Write nightly notes', root, GITHUB_SHA=sha).returncode)
            self.assertIn('merge feature', (root / 'nightly-notes.md').read_text())

    def test_publication_replaces_old_assets_and_never_marks_latest(self):
        for existing in (False, True):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.git(root, 'init', '-q')
                self.git(root, 'config', 'user.email', 'nightly@example.invalid')
                self.git(root, 'config', 'user.name', 'Nightly test')
                self.git(root, 'commit', '-q', '--allow-empty', '-m', 'source')
                if existing:
                    self.git(root, 'tag', 'nightly')
                assets = ['Metrolist.apk',
                          'metrolist-desktop-linux-x64-14.0.0+abcdef0.AppImage',
                          'Metrolist-Desktop-windows.zip']
                (root / 'nightly-assets').mkdir()
                for name in assets:
                    (root / 'nightly-assets' / name).write_bytes(b'package')
                (root / 'nightly-notes.md').write_text('Built from source\n')
                (root / 'state.json').write_text(json.dumps({
                    'exists': existing, 'assets': ['legacy.apk', 'obsolete.zip'] if existing else [],
                    'calls': [], 'draft': False,
                }))
                gh = root / 'gh'
                gh.write_text(textwrap.dedent('''\
                    #!/usr/bin/env python3
                    import json
                    from pathlib import Path
                    import sys

                    args = sys.argv[1:]
                    state = json.loads(Path('state.json').read_text())
                    state['calls'].append(args)
                    status = 0
                    if args[:2] == ['release', 'view']:
                        if not state['exists']:
                            status = 1
                        else:
                            print('\\n'.join(state['assets']))
                    elif args[:2] in (['release', 'create'], ['release', 'edit']):
                        assert '--prerelease' in args and '--latest=false' in args
                        state['exists'] = True
                        state['draft'] = '--draft=false' not in args
                        if not state['draft']:
                            assert len(state['assets']) == 3
                            assert '--notes-file' in args
                    elif args[:2] == ['release', 'delete-asset']:
                        assert state['draft']
                        state['assets'].remove(args[3])
                    elif args[:2] == ['release', 'upload']:
                        assert state['draft']
                        state['assets'] = [Path(p).name for p in args[3:] if not p.startswith('--')]
                    else:
                        assert args[:2] == ['api', '--method'], args
                    Path('state.json').write_text(json.dumps(state))
                    sys.exit(status)
                    '''))
                gh.chmod(0o755)
                result = self.run_step('Publish nightly prerelease', root, PATH=f'{root}:{os.environ["PATH"]}')
                self.assertEqual(0, result.returncode, result.stderr)
                state = json.loads((root / 'state.json').read_text())
                self.assertFalse(state['draft'])
                self.assertEqual(set(assets), set(state['assets']))
                calls = state['calls']
                if existing:
                    self.assertIn('PATCH', calls[0])
                    self.assertIn('force=true', calls[0])
                    self.assertIn('sha=abcdef0123456789', calls[0])
                    self.assertEqual(['legacy.apk', 'obsolete.zip'],
                                     [call[3] for call in calls if call[:2] == ['release', 'delete-asset']])
                else:
                    self.assertIn('POST', calls[0])
                    self.assertIn('ref=refs/tags/nightly', calls[0])
                    create = next(call for call in calls if call[:2] == ['release', 'create'])
                    self.assertIn('--verify-tag', create)
                self.assertIn('--draft=false', calls[-1])


if __name__ == '__main__':
    unittest.main()
