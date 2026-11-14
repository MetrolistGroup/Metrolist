"""Test platform selection and release verification without hosted runners."""

from contextlib import chdir
import os
from pathlib import Path
import re
import json
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / ".github/scripts"))
import ci_paths
import release_verification as release


class PathSelectionTest(unittest.TestCase):
    def test_common_sources_resources_and_docs_use_linux_only(self):
        result = ci_paths.classify([
            "shared/src/commonMain/kotlin/Player.kt", "shared/src/commonTest/kotlin/Test.kt",
            "shared/src/commonMain/composeResources/values-pl/strings.xml", "docs/checks.md",
        ])
        self.assertEqual(result, {"android": False, "desktop": True, "ios": False, "repository": True, "arch": False, "shared-only": True})

    def test_platform_sources_and_workflows_select_only_their_platform(self):
        for key, paths in {
            "android": ["androidApp/src/Test.kt", "shared/src/androidMain/Test.kt", ".github/workflows/reusable-android-build.yml"],
            "desktop": ["desktopApp/src/Test.kt", "shared/src/desktopMain/Test.kt", ".github/workflows/reusable-desktop-windows.yml"],
            "ios": ["iosApp/Test.swift", "shared/src/iosMain/Test.kt", ".github/workflows/reusable-ios-build.yml"],
        }.items():
            for path in paths:
                with self.subTest(path=path):
                    result = ci_paths.classify([path])
                    self.assertEqual([p for p in ("android", "desktop", "ios") if result[p]], [key])
                    self.assertFalse(result["shared-only"])

    def test_shared_jvm_sources_cover_both_jvm_platforms(self):
        result = ci_paths.classify(["shared/src/jvmSharedMain/Http.kt"])
        self.assertTrue(result["android"] and result["desktop"])
        self.assertFalse(result["ios"])

    def test_global_inputs_and_unknown_paths_select_every_platform(self):
        for path in ("gradlew", "gradlew.bat", "gradle.properties", "gradle/libs.versions.toml", "shared/build.gradle.kts",
                     "buildSrc/src/main/kotlin/Task.kt", ".github/actions/prepare-ci-gradle/action.yml",
                     ".github/workflows/release.yml", "new-module/src/New.kt"):
            with self.subTest(path=path):
                result = ci_paths.classify([path])
                self.assertTrue(all(result[p] for p in ci_paths.PLATFORMS))

    def test_mixed_common_and_android_changes_preserve_android_tests(self):
        result = ci_paths.classify(["docs/checks.md", "androidApp/src/Player.kt"])
        self.assertTrue(result["android"] and result["desktop"])
        self.assertFalse(result["shared-only"])

    def test_diff_failure_runs_every_check(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            result = subprocess.run([sys.executable, str(ROOT / ".github/scripts/ci_paths.py"), "--base", "missing"],
                                    capture_output=True, env={**os.environ, "GITHUB_OUTPUT": str(output)})
            self.assertEqual(result.returncode, 0)
            self.assertIn("ios=true", output.read_text())

    def test_shared_only_main_push_has_one_desktop_matrix_entry(self):
        text = (ROOT / ".github/workflows/reusable-desktop-verify.yml").read_text()
        expression = re.search(r"fromJSON\((.*?)\) }}", text, re.S)[1]
        for shared_only in (False, True):
            for event in ("push", "pull_request"):
                guard = expression.replace("inputs.shared-only", repr(shared_only)).replace("github.event_name", repr(event))
                guard = guard.replace("&&", "and").replace("||", "or")
                matrix = json.loads(eval("(" + guard + ")", {"__builtins__": {}}))
                self.assertEqual(len(matrix), 1 if shared_only or event == "pull_request" else 2)
                self.assertEqual(matrix[0]["arch"], "x64")


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.run = dict(id=12, head_sha=self.sha, head_branch="main", event="push", path=".github/workflows/build-android.yml",
                        run_number=7, run_attempt=1, status="completed", conclusion="success", html_url="https://github.com/test/run/12")
        self.job = dict(name="Android / APK", conclusion="success", check_run_url="https://api.github.com/repos/test/repo/check-runs/42",
                        steps=[{"name": "Test Android string generation", "conclusion": "success"}])
        self.check = dict(head_sha=self.sha, status="completed", conclusion="success", app={"slug": "github-actions"})

    def api(self, path):
        if "/check-runs/" in path:
            return [self.check]
        if "/jobs?" in path:
            return [{"jobs": [self.job]}]
        return [{"workflow_runs": [self.run]}]

    def verify(self):
        return release.verify_run("test/repo", self.sha, "build-android.yml", "Android / APK", self.api)

    def test_accepts_a_successful_trusted_check(self):
        self.assertEqual(self.verify(), self.run["html_url"])

    def test_rejects_wrong_revision_branch_event_and_workflow(self):
        for key, value in (("head_sha", "b" * 40), ("head_branch", "feature"), ("event", "pull_request"),
                           ("path", ".github/workflows/fake.yml"), ("status", "in_progress"), ("conclusion", "failure")):
            with self.subTest(key=key), patch.dict(self.run, {key: value}):
                with self.assertRaises(ValueError):
                    self.verify()

    def test_rejects_skipped_job_even_if_the_workflow_succeeded(self):
        self.job["conclusion"] = "skipped"
        with self.assertRaisesRegex(ValueError, "required job"):
            self.verify()

    def test_rejects_check_from_another_revision_or_app(self):
        for change in ({"head_sha": "b" * 40}, {"app": {"slug": "fake"}}, {"conclusion": "skipped"}):
            with patch.dict(self.check, change), self.assertRaises(ValueError):
                self.verify()

    def test_latest_run_must_pass_across_all_api_pages(self):
        failed = {**self.run, "run_number": 8, "conclusion": "failure"}
        with self.assertRaisesRegex(ValueError, "latest run"):
            release.verify_run("test/repo", self.sha, "build-android.yml", "Android / APK",
                               lambda _: [{"workflow_runs": [self.run]}, {"workflow_runs": [failed]}])

    def test_no_runs_and_api_errors_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "no trusted"):
            release.verify_run("test/repo", self.sha, "build-android.yml", "Android / APK", lambda _: [{"workflow_runs": []}])
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "", "secret")):
            with self.assertRaisesRegex(ValueError, "permissions"):
                release.api("test/path")

    def test_non_applicable_workflow_can_be_absent_but_cannot_fail(self):
        self.assertIsNone(release.verify_run("test/repo", self.sha, "build-android.yml", "Android / APK",
                                            lambda _: [{"workflow_runs": []}], required=False))
        self.job["conclusion"] = "skipped"
        self.assertIsNone(release.verify_run("test/repo", self.sha, "build-android.yml", "Android / APK", self.api, required=False))
        self.run["conclusion"] = "failure"
        with self.assertRaises(ValueError):
            release.verify_run("test/repo", self.sha, "build-android.yml", "Android / APK", self.api, required=False)

    def test_installer_verification_remains_available_without_serializing_releases(self):
        text = (ROOT / ".github/workflows/release.yml").read_text().split("  windows-manager:\n", 1)[1].split("  publish:", 1)[0]
        self.assertIn("needs: prepare", text)
        self.assertNotIn("- windows", text)
        workflow = (ROOT / ".github/workflows/reusable-windows-manager.yml").read_text()
        for step in ("Test manager", "Validate portable payload", "Install, repair and uninstall end to end"):
            block = workflow.split(f"- name: {step}\n", 1)[1].split("- name:", 1)[0]
            self.assertIn("if: inputs.run-verification", block)
        self.assertIn("18d2846bcc95847f08ea969d707222c1566d9f65", workflow)

    def test_caches_keep_credentials_out_and_pull_requests_read_only(self):
        workflows = ROOT / ".github/workflows"
        for name in ("android-build", "desktop-linux", "desktop-macos", "desktop-windows", "ios-build"):
            text = (workflows / f"reusable-{name}.yml").read_text()
            self.assertIn("persist-configuration-cache: 'false'", text)
            self.assertIn("cache-read-only: ${{ inputs.cache-read-only }}", text)
        rust = (ROOT / ".github/actions/cache-rust-toolchain/action.yml").read_text()
        self.assertIn("if: inputs.cache-read-only == 'true'", rust)
        self.assertIn("uses: actions/cache/restore@", rust)
        self.assertIn("runner.os", rust)
        self.assertIn("runner.arch", rust)
        webview = (ROOT / ".github/actions/prepare-linux-webview/action.yml").read_text()
        self.assertIn("steps.toolchain.outputs.os", webview)
        self.assertIn("steps.toolchain.outputs.libraries", webview)
        runtime = (ROOT / "desktopApp/build.gradle.kts").read_text().split("tasks.withType<AbstractJLinkTask>()", 1)[1].split("compose.desktop", 1)[0]
        self.assertIn('withPropertyName("runtimeJdk").withPathSensitivity(PathSensitivity.RELATIVE)', runtime)
        self.assertIn('inputs.property("baseCdsJvmArgs", cdsOptions)', runtime)
        self.assertIn("outputs.cacheIf { true }", runtime)
        desktop = (workflows / "reusable-desktop-verify.yml").read_text()
        self.assertIn("dotnet run --project tests/LoginCompletion.Tests.csproj", desktop)

    def test_api_retries_transient_errors_and_never_accepts_missing_evidence(self):
        responses = [subprocess.CompletedProcess([], 1, "", "HTTP 429 rate limit"),
                     subprocess.CompletedProcess([], 0, '[{"workflow_runs": []}]', "")]
        with patch.object(subprocess, "run", side_effect=responses) as call, patch.object(release.time, "sleep") as sleep:
            self.assertEqual(release.api("test/path"), [{"workflow_runs": []}])
            self.assertEqual(call.call_count, 2)
            sleep.assert_called_once_with(2)
        with patch.object(subprocess, "run", return_value=responses[0]), patch.object(release.time, "sleep"):
            with self.assertRaisesRegex(release.ApiError, "rate limits"):
                release.api("test/path")
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "invalid JSON", "")):
            with self.assertRaises(release.ApiError):
                release.api("test/path")

    def test_workflow_file_pins_survive_display_name_changes(self):
        for workflow, job in release.CHECKS.values():
            self.assertTrue((ROOT / ".github/workflows" / workflow).is_file(), f"Update CHECKS for renamed workflow {workflow}")
        # Workflow display names do not enter the API path or trust check.
        with patch.dict(self.run, {"name": "Renamed Android verification"}):
            self.assertEqual(self.verify(), self.run["html_url"])

    def test_bump_chain_requires_the_source_change_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args], text=True).strip()
            def commit(message):
                git("add", ".")
                git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", message)
                return git("rev-parse", "HEAD")
            git("init", "-q")
            root = Path(directory)
            (root / "gradle.properties").write_text("appVersion=1.0.0\norg.gradle.caching=true\n")
            commit("base")
            (root / "androidApp").mkdir()
            (root / "androidApp/Player.kt").write_text("code")
            source = commit("fix")
            (root / "gradle.properties").write_text("appVersion=1.0.1\norg.gradle.caching=true\n")
            commit("version")
            (root / "docs").mkdir()
            (root / "docs/changelog.md").write_text("release notes")
            head = commit("notes")
            with chdir(directory):
                self.assertEqual(release.source_commit(head), source)
                selector = subprocess.run([sys.executable, str(ROOT / ".github/scripts/ci_paths.py"), "--base", source, "--head", head], capture_output=True, text=True)
                self.assertEqual(selector.returncode, 0)
                self.assertIn("android=false", selector.stdout)
                self.assertIn("ios=false", selector.stdout)
                (root / "gradle.properties").write_text("appVersion=1.0.1\norg.gradle.caching=false\n")
                config = commit("build configuration")
                self.assertFalse(release.metadata_only(head, config))
                self.assertEqual(release.source_commit(config), config)
                git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-q", "--allow-empty", "-m", "empty")
                empty = git("rev-parse", "HEAD")
                self.assertFalse(release.metadata_only(config, empty))
                self.assertEqual(release.source_commit(empty), empty)
                git("reset", "-q", "--hard", config)
                gh = root / "gh"
                gh.write_text(textwrap.dedent(f'''\
                    #!{sys.executable}
                    import json, os, sys
                    path = sys.argv[-1]
                    if os.environ.get('API_ERROR'):
                        sys.exit(1)
                    if '/workflows/' in path:
                        runs = []
                        if 'build-android.yml' in path and 'head_sha={source}' in path:
                            runs = [dict(id=12, head_sha='{source}', head_branch='main', event='push',
                                         path='.github/workflows/build-android.yml', run_number=7,
                                         status='completed', conclusion='failure' if os.environ.get('CHECK_FAILED') else 'success',
                                         html_url='https://github.com/test/run/12')]
                        print(json.dumps([dict(workflow_runs=runs)]))
                    elif '/jobs?' in path:
                        print(json.dumps([dict(jobs=[dict(name='Android / APK', conclusion='success',
                            check_run_url='https://api.github.com/repos/test/repo/check-runs/42',
                            steps=[dict(name='Test Android string generation', conclusion='success')])])]))
                    else:
                        print(json.dumps([dict(head_sha='{source}', status='completed', conclusion='success', app=dict(slug='github-actions'))]))
                    '''))
                gh.chmod(0o755)
                for flag in (None, "CHECK_FAILED", "API_ERROR"):
                    env = {**os.environ, "GITHUB_SHA": head, "GITHUB_REPOSITORY": "test/repo", "PATH": f"{root}:{os.environ['PATH']}"}
                    if flag:
                        env[flag] = "1"
                    result = subprocess.run([sys.executable, str(ROOT / ".github/scripts/release_verification.py")], capture_output=True, text=True, env=env)
                    self.assertEqual(result.returncode, 0 if flag is None else 1, result.stderr)
                    self.assertIn(f"reused from {source}" if flag is None else "Release blocked before packaging", result.stdout + result.stderr)

    def test_release_callers_disable_verification_only_behind_gate(self):
        for workflow in ("release.yml",):
            text = (ROOT / ".github/workflows" / workflow).read_text()
            self.assertIn("run: python3 .github/scripts/release_verification.py", text)
            self.assertIn("checks: read", text)
            self.assertEqual(text.count("run-verification: false"), 6 if workflow == "release.yml" else 5)
            self.assertIn("fetch-depth: 0", text)
        for name in ("android-build", "desktop-linux", "desktop-macos", "desktop-windows", "ios-build"):
            text = (ROOT / f".github/workflows/reusable-{name}.yml").read_text()
            self.assertIn("      run-verification:", text)
            self.assertIn("default: true", text.split("run-verification:", 1)[1].split("type:", 1)[0])


if __name__ == "__main__":
    unittest.main()
