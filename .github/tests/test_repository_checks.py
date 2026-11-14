"""Ensure edits to repository checks and their tooling inputs trigger verification."""

from pathlib import Path
import re
import sys
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows/verify-repository-checks.yml'
sys.path.insert(0, str(WORKFLOW.parents[1] / 'scripts'))
from ci_paths import classify


class RepositoryChecksTest(unittest.TestCase):
    def test_arch_cache_belongs_to_builder_and_only_main_saves(self):
        workflow = (WORKFLOW.parent / 'verify-aur-package.yml').read_text()
        self.assertIn('GRADLE_USER_HOME: /home/builder/.gradle', workflow)
        self.assertIn('GRADLE_USER_HOME="$GRADLE_USER_HOME"', workflow)
        restore = workflow.split('- name: Restore builder Gradle cache', 1)[1].split('- name:', 1)[0]
        save = workflow.split('- name: Save builder Gradle cache', 1)[1]
        self.assertIn('uses: actions/cache/restore@', restore)
        self.assertIn('uses: actions/cache/save@', save)
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/main'", save)
        for cache in (restore, save):
            self.assertIn('/home/builder/.gradle/caches', cache)
            self.assertIn('/home/builder/.gradle/wrapper', cache)
            self.assertNotIn('configuration-cache', cache)
        self.assertIn('runner.os', restore)
        self.assertIn('runner.arch', restore)
        self.assertIn("'gradle/libs.versions.toml'", restore)
        self.assertLess(workflow.index('Give builder ownership'), workflow.index('Build local Arch package'))

    def test_flatpak_gating_fails_open_and_keeps_translation_checks(self):
        workflow = WORKFLOW.read_text()
        checks, flatpak = workflow.split('\n  flatpak:', 1)
        self.assertIn('fetch-depth: 0', checks)
        self.assertIn('run: node .github/scripts/flatpak-paths.cjs', checks)
        self.assertIn('python3 scripts/check_translation_mechanics.py', checks)
        self.assertIn('needs: checks', flatpak)
        self.assertIn("if: always() && needs.checks.result != 'skipped' && needs.checks.outputs.flatpak != 'false'", flatpak)
        self.assertIn('flatpak-builder --show-manifest', flatpak)
        self.assertIn('make --dry-run flatpak-deps flatpak-build flatpak-install flatpak-bundle', flatpak)

    def test_android_generator_changes_trigger_its_check(self):
        workflow = (WORKFLOW.parent / 'build-android.yml').read_text()
        for event in ('push', 'pull_request'):
            block = re.search(rf'(?ms)^  {event}:\n(.*?)(?=^  \w|^\w|\Z)', workflow)[1]
            self.assertNotIn("paths:", block)
            for path in ('androidApp/build.gradle.kts', 'scripts/test_generate_android_strings.py'):
                self.assertTrue(classify([path])["android"], path)
        reusable = (WORKFLOW.parent / 'reusable-android-build.yml').read_text()
        self.assertIn('run: python3 scripts/test_generate_android_strings.py', reusable)

    def test_dmg_packaging_changes_trigger_desktop_checks(self):
        workflow = (WORKFLOW.parent / 'build-desktop.yml').read_text()
        for event in ('push', 'pull_request'):
            block = re.search(rf'(?ms)^  {event}:\n(.*?)(?=^  \w|^\w|\Z)', workflow)[1]
            self.assertNotIn("paths:", block)
            for path in ('desktopApp/packaging-integrity.gradle.kts', 'scripts/test_dmg_packaging.py'):
                self.assertTrue(classify([path])["desktop"], path)
        reusable = (WORKFLOW.parent / 'reusable-desktop-verify.yml').read_text()
        self.assertIn('python3 scripts/test_dmg_packaging.py', reusable)

    def test_checks_and_tooling_inputs_trigger_both_events(self):
        workflow = WORKFLOW.read_text()
        commands = set(re.findall(r'^          python3 (\S+)\s*$', workflow, re.MULTILINE))
        self.assertIn('scripts/check_translation_mechanics.py', commands)
        self.assertIn('scripts/test_translation_mechanics.py', commands)
        self.assertIn('scripts/test_build_ios.py', commands)
        self.assertIn('scripts/test_metromem.py', commands)
        self.assertIn('python3 -m unittest discover -s .github/tests', workflow)
        inputs = commands | {
            'scripts/ios_version.sh',
            'scripts/build_ios.sh',
            'scripts/build_appimage.sh',
            'scripts/generate_distribution.py',
            'scripts/propose_distribution.py',
            'packaging/winget/MetrolistGroup.Metrolist.yaml.in',
            'packaging/homebrew/metrolist.rb.in',
            'packaging/nix/package.nix',
            'packaging/flatpak/com.metrolist.desktop.json.in',
            'flake.nix',
            'shared/src/commonMain/composeResources/values/strings.xml',
            'shared/src/commonMain/composeResources/values-b+hi+Latn/strings.xml',
            'scripts/metromem',
            'desktopApp/packaging-launchers.gradle.kts',
            '.github/actions/upload-desktop-sentry/verify_metadata.py',
            '.github/scripts/llm-triage.cjs',
            '.github/scripts/llm-triage.test.cjs',
            '.githooks/pre-commit',
            '.githooks/commit-msg',
        }
        for event in ('push', 'pull_request'):
            block = re.search(rf'(?ms)^  {event}:\n(.*?)(?=^  \w|^\w|\Z)', workflow)[1]
            self.assertNotIn("paths:", block)
            for path in inputs:
                with self.subTest(event=event, path=path):
                    self.assertTrue(classify([path])["repository"], path)


if __name__ == '__main__':
    unittest.main()
