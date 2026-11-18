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
        self.assertEqual({'version', 'notes', 'android', 'linux', 'windows', 'publish'}, jobs.keys())
        self.assertIn('needs: [version, notes, android, linux, windows]', jobs['publish'])
        self.assertIn("if: github.ref == 'refs/heads/main'", jobs['version'])
        self.assertNotIn('    if:', jobs['publish'])
        self.assertIn('contents: write', jobs['publish'])
        self.assertNotIn('secrets.', jobs['publish'])
        # The model key stays in a read-only job.
        self.assertIn('secrets.OPENROUTER_API_KEY', jobs['notes'])
        self.assertIn('contents: read', jobs['notes'])
        self.assertNotIn('contents: write', jobs['notes'])
        self.assertNotIn('git/refs', workflow)
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
        self.assertEqual(['nightly-notes', 'nightly-android', 'desktop-native-linux-x64', 'desktop-native-windows-x64'], downloads)
        self.assertIn('fetch-depth: 0', jobs['notes'])
        self.assertIn('persist-credentials: false', jobs['notes'])
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
                        'Metrolist-Desktop-linux-x64.AppImage': b'linux',
                        'Metrolist-Desktop-windows.zip': b'windows',
                    }, {p.name: p.read_bytes() for p in (root / 'nightly-assets').iterdir()})

    def git(self, directory, *args):
        return subprocess.check_output(['git', *args], cwd=directory, text=True).strip()

    def fake_bin(self, root, previous, release, completion):
        """Fake gh (nightly body and latest release) and curl (OpenRouter reply, or failure when None)."""
        bin_dir = root / 'bin'
        bin_dir.mkdir(exist_ok=True)
        (root / 'requests.txt').write_text('')
        gh = bin_dir / 'gh'
        gh.write_text(textwrap.dedent(f'''\
            #!/usr/bin/env bash
            if [[ "$3" == nightly ]]; then
              [[ -n "{previous}" ]] || exit 1
              printf '%s\\n' "{previous}"
            else
              [[ -n "{release}" ]] || exit 1
              printf '%s\\n' "{release}"
            fi
            '''))
        curl = bin_dir / 'curl'
        reply = 'exit 22' if completion is None else f"printf '%s' {json.dumps(json.dumps({'choices': [{'message': {'content': completion}}]}))}"
        curl.write_text(f'#!/usr/bin/env bash\ncat >> {root}/requests.txt\necho >> {root}/requests.txt\n{reply}\n')
        for tool in (gh, curl):
            tool.chmod(0o755)
        return {'PATH': f'{bin_dir}:{os.environ["PATH"]}'}

    def test_notes_cover_previous_nightly_and_latest_release(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.git(root, 'init', '-q', '-b', 'main')
            self.git(root, 'config', 'user.email', 'nightly@example.invalid')
            self.git(root, 'config', 'user.name', 'Nightly test')
            env = {'GIT_COMMITTER_DATE': '2026-01-01T00:00:00Z'}
            subprocess.check_call(['git', 'commit', '-q', '--allow-empty', '-m', 'before release'], cwd=root, env={**os.environ, **env})
            self.git(root, 'commit', '-q', '--allow-empty', '-m', 'after release')
            previous = self.git(root, 'rev-parse', 'HEAD')
            for index in range(52):
                self.git(root, 'commit', '-q', '--allow-empty', '-m', f'main change {index}')
            self.git(root, 'checkout', '-q', '-b', 'feature')
            self.git(root, 'commit', '-q', '--allow-empty', '-m', 'side branch detail')
            self.git(root, 'checkout', '-q', 'main')
            self.git(root, 'merge', '-q', '--no-ff', 'feature', '-m', 'merge feature')
            sha = self.git(root, 'rev-parse', 'HEAD')
            release = 'v1.0.0\t2026-06-01T00:00:00Z'
            notes_file = root / 'nightly-notes.md'

            def notes(previous_sha, release_info, completion=None, key=''):
                bins = self.fake_bin(root, previous_sha and f'{previous_sha}', release_info, completion)
                result = self.run_step('Write nightly notes', root, GITHUB_SHA=sha, OPENROUTER_API_KEY=key,
                                       OPENROUTER_MODEL='test/model', PROMPT='prompt', **bins)
                self.assertEqual(0, result.returncode, result.stderr)
                return notes_file.read_text()

            text = notes(previous, release)
            self.assertTrue(text.startswith(f'Version 14.0.0+abcdef0, built from [{sha}](https://github.com/test/repo/commit/{sha})'))
            since_nightly, since_release = text.split('## Changes since previous nightly\n', 1)[1].split('## Changes since v1.0.0\n')
            self.assertEqual(50, len(re.findall(r'^\* ', since_nightly, re.MULTILINE)))
            self.assertIn('merge feature', since_nightly)
            self.assertNotIn('side branch detail', text)
            self.assertNotIn('after release', since_nightly)
            self.assertIn('Only the latest 50', since_release)
            self.assertNotIn('Summarised', text)
            self.assertEqual('', (root / 'requests.txt').read_text())

            # A previous build that is no longer in main's history is ignored.
            text = notes('f' * 40, release)
            self.assertNotIn('previous nightly', text)
            self.assertIn('## Changes since v1.0.0', text)
            self.assertIn('No changes.', notes(sha, ''))
            self.assertNotIn('## ', notes('', ''))

            text = notes(previous, release, completion='### Fixed\n\n* Playback resumes. (abc1234)', key='secret')
            self.assertEqual(2, text.count('### Fixed'))
            self.assertNotIn('* main change', text)
            self.assertIn('Summarised from commit messages by test/model.', text)
            request = json.loads((root / 'requests.txt').read_text().splitlines()[0])
            self.assertEqual('test/model', request['model'])
            self.assertEqual({'effort': 'low'}, request['reasoning'])
            self.assertEqual('prompt', request['messages'][0]['content'])
            self.assertIn('* merge feature', request['messages'][1]['content'])

            # A failed request falls back to the commit list.
            text = notes(previous, release, completion=None, key='secret')
            self.assertIn('* merge feature', text)
            self.assertNotIn('Summarised', text)

    def test_publication_replaces_old_assets_and_never_marks_latest(self):
        for existing in (False, True):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.git(root, 'init', '-q')
                self.git(root, 'config', 'user.email', 'nightly@example.invalid')
                self.git(root, 'config', 'user.name', 'Nightly test')
                self.git(root, 'commit', '-q', '--allow-empty', '-m', 'source')
                assets = ['Metrolist.apk',
                          'Metrolist-Desktop-linux-x64.AppImage',
                          'Metrolist-Desktop-windows.zip']
                (root / 'nightly-assets').mkdir()
                for name in assets:
                    (root / 'nightly-assets' / name).write_bytes(b'package')
                (root / 'artifacts/notes').mkdir(parents=True)
                (root / 'artifacts/notes/nightly-notes.md').write_text('Built from source\n')
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
                        raise AssertionError(args)
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
                    self.assertEqual(['legacy.apk', 'obsolete.zip'],
                                     [call[3] for call in calls if call[:2] == ['release', 'delete-asset']])
                else:
                    create = next(call for call in calls if call[:2] == ['release', 'create'])
                    self.assertIn('abcdef0123456789', create)
                self.assertIn('--target', calls[-1])
                self.assertIn('abcdef0123456789', calls[-1])
                self.assertIn('artifacts/notes/nightly-notes.md', calls[-1])
                self.assertIn('--draft=false', calls[-1])


if __name__ == '__main__':
    unittest.main()
