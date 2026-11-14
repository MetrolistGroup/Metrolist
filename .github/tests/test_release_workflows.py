"""Exercise release asset validation and retry source checks without network access."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


def step_script(workflow, step):
    text = (ROOT / '.github/workflows' / workflow).read_text()
    body = text.split(f'- name: {step}\n', 1)[1].split('        run: |\n', 1)[1]
    lines = []
    for line in body.splitlines():
        if line and not line.startswith('          '):
            break
        lines.append(line[10:])
    return '\n'.join(lines)


class ReleaseWorkflowsTest(unittest.TestCase):
    def test_nuget_cache_tracks_sdk_and_dependencies_without_writing_on_pull_requests(self):
        text = (ROOT / '.github/workflows/reusable-desktop-windows.yml').read_text()
        writable = text.split('      - name: Cache NuGet packages\n', 1)[1].split('      - name:', 1)[0]
        read_only = text.split('      - name: Restore NuGet packages (read-only)\n', 1)[1].split('      - name:', 1)[0]
        self.assertIn('if: ${{ !inputs.cache-read-only }}', writable)
        self.assertIn('uses: actions/cache@', writable)
        self.assertIn('if: inputs.cache-read-only', read_only)
        self.assertIn('uses: actions/cache/restore@', read_only)
        self.assertEqual(writable.split('key: ')[1].strip(), read_only.split('key: ')[1].strip())
        for cache in (writable, read_only):
            self.assertIn('path: ~/.nuget/packages', cache)
            self.assertIn('runner.os', cache)
            self.assertIn('runner.arch', cache)
            self.assertIn("hashFiles('metro-wv2-helper/global.json', 'metro-wv2-helper/**/*.csproj')", cache)
            self.assertNotIn('restore-keys:', cache)
        self.assertIn('dotnet run --project tests/LoginCompletion.Tests.csproj', text)

    def test_failed_publication_retains_packages_for_retry(self):
        for workflow, job, step in (
            ('release.yml', 'cleanup-artifacts', 'Delete artifacts from this run'),
        ):
            text = (ROOT / '.github/workflows' / workflow).read_text()
            condition = re.search(rf'(?ms)^  {job}:\n.*?^    if: ([^\n]+)$', text)[1]
            script = step_script(workflow, step)
            for failed_result in ('failure', 'cancelled', 'skipped'):
                with self.subTest(workflow=workflow, result=failed_result), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    package = root / 'package'
                    package.write_bytes(b'successful build output')
                    gh = root / 'gh'
                    gh.write_text(textwrap.dedent('''\
                        #!/bin/bash
                        if [[ " $* " == *" --method DELETE "* || "$1 $2" == 'release delete' ]]; then
                          rm -f "$PACKAGE"
                        elif [[ "$1 $2" == 'release view' ]]; then
                          test -f "$PACKAGE"
                        else
                          echo 123
                        fi
                        '''))
                    gh.chmod(0o755)
                    for publication in (failed_result, 'success'):
                        guard = condition.replace('always()', 'true').replace(
                            'needs.prepare.outputs.should-release', "'true'"
                        ).replace('needs.publish.result', repr(publication))
                        result = subprocess.run(
                            ['bash', '-c', f'if [[ {guard} ]]; then\n{script}\nfi'],
                            cwd=root, capture_output=True, text=True, env={
                                **os.environ, 'PATH': f'{root}:{os.environ["PATH"]}',
                                'PACKAGE': str(package), 'GITHUB_REPOSITORY': 'test/repo',
                                'GITHUB_RUN_ID': '123', 'STAGING_RELEASE_TAG': 'tester-package-staging',
                            },
                        )
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertEqual(publication != 'success', package.exists())
                        if package.exists():
                            self.assertEqual(b'successful build output', package.read_bytes())

    def test_stable_release_requires_and_checksums_ipa(self):
        script = step_script('release.yml', 'Validate and checksum release assets')
        assets = [
            'Metrolist.apk',
            'Metrolist-KMP-mapping.txt',
            'Metrolist-Desktop-Windows.zip',
            'Metrolist-Desktop-Windows-arm64.zip',
            'metrolist-installer-windows-x64-14.0.0.exe',
            'metrolist-desktop-linux-x64-14.0.0.AppImage',
            'metrolist-desktop-linux-arm64-14.0.0.AppImage',
            'metrolist-desktop-macos-arm64-14.0.0.dmg',
            'metrolist-desktop-macos-x64-14.0.0.dmg',
        ]
        for include_ipa in (False, True):
            with self.subTest(include_ipa=include_ipa), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'release-assets'
                output.mkdir()
                (Path(directory) / 'scripts').symlink_to(ROOT / 'scripts')
                for name in assets + (['Metrolist.ipa'] if include_ipa else []):
                    (output / name).write_bytes(b'package')
                result = subprocess.run(
                    ['bash', '-c', script], cwd=directory,
                    env={**os.environ, 'APP_VERSION': '14.0.0', 'GITHUB_REPOSITORY': 'MetrolistGroup/Metrolist'},
                    capture_output=True, text=True,
                )
                self.assertEqual(result.returncode == 0, include_ipa, result.stderr)
                if include_ipa:
                    sums = (output / 'SHA256SUMS').read_text()
                    self.assertIn('Metrolist.ipa', sums)
                    self.assertIn('Metrolist-with-Google-Cast.apk', sums)
                    self.assertIn('metrolist-altstore.json', sums)
                else:
                    self.assertIn('Missing release asset: release-assets/Metrolist.ipa', result.stderr)

if __name__ == '__main__':
    unittest.main()
