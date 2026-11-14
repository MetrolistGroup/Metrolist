"""Check stable notes extraction, preflight failures and draft publication offline."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

from test_release_workflows import step_script

ROOT = Path(__file__).resolve().parents[2]
PARSER = ROOT / '.github/scripts/parse_changelog.sh'


class ChangelogTest(unittest.TestCase):
    def test_extracts_real_14_entry_with_default_path_and_optional_v(self):
        for version in ('14.0.0', 'v14.0.0'):
            with self.subTest(version=version):
                result = subprocess.run(['bash', str(PARSER), version], cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue(result.stdout.startswith('# First public multiplatform release\n'))
                self.assertIn('## Known limitations\n', result.stdout)
                self.assertIn('Full changelog: https://github.com/MetrolistGroup/Metrolist/commits/v14.0.0\n', result.stdout)
                self.assertNotIn('---v', result.stdout)
                self.assertNotIn('KMP status update', result.stdout)

    def test_exact_version_and_entry_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            changelog = Path(directory) / 'changelog.md'
            changelog.write_text('---v14x0x0\nWrong version\n---v14.0.0\nMastodon\n\n---v13.7.0\nTODO\n')
            result = subprocess.run(['bash', str(PARSER), '14.0.0', str(changelog)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('Mastodon\n', result.stdout)
            changelog.write_text('---v14x0x0\nWrong version\n')
            result = subprocess.run(['bash', str(PARSER), '14.0.0', str(changelog)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('not found', result.stderr)
            self.assertEqual('', result.stdout)

    def test_rejects_empty_entries_and_todo_markers_without_output(self):
        for entry, error in (
            ('\n \t\n', 'empty changelog entry'),
            ('TODO: finish\n', 'TODO marker'),
            ('# Notes\n<!-- todo -->\n', 'TODO marker'),
            ('- Todo_fix\n', 'TODO marker'),
        ):
            with self.subTest(entry=entry), tempfile.TemporaryDirectory() as directory:
                changelog = Path(directory) / 'changelog.md'
                changelog.write_text(f'---v14.0.0\n{entry}---v13.7.0\nOld notes\n')
                result = subprocess.run(['bash', str(PARSER), '14.0.0', str(changelog)], capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(error, result.stderr)
                self.assertEqual('', result.stdout)

    def test_missing_arguments_and_file(self):
        for arguments, error in (([], 'version argument required'), (['14.0.0', 'missing.md'], 'file not found')):
            with self.subTest(arguments=arguments), tempfile.TemporaryDirectory() as directory:
                result = subprocess.run(['bash', str(PARSER), *arguments], cwd=directory, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(error, result.stderr)


class StableReleaseNotesTest(unittest.TestCase):
    def fixture(self, root, notes='Finished notes\n', version_code='987'):
        (root / '.github').mkdir()
        (root / '.github/scripts').symlink_to(PARSER.parent, target_is_directory=True)
        (root / 'docs').mkdir()
        (root / 'docs/changelog.md').write_text(f'---v14.0.0\n{notes}\n---v13.7.0\nOld notes\n')
        (root / 'androidApp').mkdir()
        (root / 'androidApp/build.gradle.kts').write_text(f'    versionCode = {version_code}\n')
        fastlane = root / f'fastlane/metadata/android/en-US/changelogs/{version_code}.txt'
        fastlane.parent.mkdir(parents=True)
        fastlane.write_text('Store notes\n')
        return fastlane

    def test_prepare_uses_build_version_code_and_rejects_unfinished_metadata(self):
        script = step_script('release.yml', 'Validate release notes')
        for case, error in (
            ('valid', None),
            ('missing entry', 'not found'),
            ('todo', 'TODO marker'),
            ('empty entry', 'empty changelog entry'),
            ('missing store notes', 'Missing or empty fastlane changelog'),
            ('empty store notes', 'Missing or empty fastlane changelog'),
            ('invalid version code', 'Could not read Android versionCode'),
        ):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                fastlane = self.fixture(root)
                if case == 'missing entry':
                    (root / 'docs/changelog.md').write_text('---v13.7.0\nOld notes\n')
                elif case == 'todo':
                    (root / 'docs/changelog.md').write_text('---v14.0.0\nTODO\n')
                elif case == 'empty entry':
                    (root / 'docs/changelog.md').write_text('---v14.0.0\n\n')
                elif case == 'missing store notes':
                    fastlane.rename(fastlane.with_name('154.txt'))
                elif case == 'empty store notes':
                    fastlane.write_text('')
                elif case == 'invalid version code':
                    (root / 'androidApp/build.gradle.kts').write_text('versionCode = releaseCode\n')
                result = subprocess.run(['bash', '-c', script], cwd=root, capture_output=True, text=True, env={
                    **os.environ, 'APP_VERSION': '14.0.0', 'RUNNER_TEMP': str(root),
                })
                self.assertEqual(result.returncode == 0, error is None, result.stderr)
                if error:
                    self.assertIn(error, result.stderr)
                else:
                    self.assertEqual('Finished notes\n', (root / 'release-notes.md').read_text())

    def test_checks_precede_all_stable_packaging_and_leave_tester_notes_separate(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertLess(workflow.index('- name: Validate release notes'), workflow.index('- name: Require prior verification'))
        for job in ('android', 'windows', 'linux', 'macos', 'ios', 'windows-manager'):
            block = re.search(rf'(?ms)^  {job}:\n(.*?)(?=^  \S|\Z)', workflow)[1]
            self.assertIn('    needs: prepare\n', block)

    def test_real_fastlane_note_matches_android_code_and_store_limit(self):
        build = (ROOT / 'androidApp/build.gradle.kts').read_text()
        code = re.search(r'^\s*versionCode\s*=\s*(\d+)\s*$', build, re.MULTILINE)[1]
        notes = (ROOT / f'fastlane/metadata/android/en-US/changelogs/{code}.txt').read_text()
        self.assertTrue(notes.strip())
        self.assertLessEqual(len(notes), 500)

    def test_publication_uses_curated_notes_for_new_and_existing_drafts(self):
        script = step_script('release.yml', 'Create or update draft release')
        for state in ('missing', 'draft', 'published', 'published-missing-asset'):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root)
                (root / 'release-assets').mkdir()
                (root / 'release-assets/Metrolist.apk').write_bytes(b'apk')
                gh = root / 'gh'
                gh.write_text(textwrap.dedent('''\
                    #!/bin/bash
                    printf '%s\n' "$*" >> "$GH_CALLS"
                    if [[ "$1 $2" == 'release view' ]]; then
                      if [[ " $* " == *' --json isDraft '* ]]; then
                        if [[ "$STATE" == draft ]]; then echo true; else echo false; fi
                      elif [[ " $* " == *' --json assets '* ]]; then
                        if [[ "$STATE" != published-missing-asset ]]; then echo Metrolist.apk; fi
                      else
                        [[ "$STATE" != missing ]]
                      fi
                    else
                      while (( $# )); do
                        if [[ "$1" == --notes-file ]]; then cp "$2" "$GH_BODY"; break; fi
                        shift
                      done
                    fi
                    '''))
                gh.chmod(0o755)
                result = subprocess.run(['bash', '-c', script], cwd=root, capture_output=True, text=True, env={
                    **os.environ, 'PATH': f'{root}:{os.environ["PATH"]}', 'STATE': state,
                    'GH_CALLS': str(root / 'calls'), 'GH_BODY': str(root / 'body'),
                    'APP_VERSION': '14.0.0', 'GITHUB_REF_NAME': 'v14.0.0',
                    'GITHUB_REPOSITORY': 'test/repo', 'RUNNER_TEMP': str(root),
                })
                self.assertEqual(result.returncode == 0, state != 'published-missing-asset', result.stderr)
                calls = (root / 'calls').read_text().splitlines()
                mutations = [call for call in calls if not call.startswith('release view ')]
                if state.startswith('published'):
                    self.assertEqual([], mutations)
                    self.assertFalse((root / 'body').exists())
                    if state == 'published-missing-asset':
                        self.assertIn('refusing to mutate a live release', result.stderr)
                else:
                    self.assertEqual('Finished notes\n', (root / 'body').read_text())
                    command = 'create' if state == 'missing' else 'edit'
                    self.assertTrue(mutations[0].startswith(f'release {command} v14.0.0 '))
                    self.assertIn('--title 14.0.0 --notes-file ', mutations[0])
                    self.assertNotIn('--generate-notes', '\n'.join(calls))
                    self.assertIn('--draft=false', mutations[-1])


if __name__ == '__main__':
    unittest.main()
