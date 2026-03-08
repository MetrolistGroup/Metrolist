#!/usr/bin/env python3
"""Exercise AppImage tool selection and fail-closed downloads without network/builds."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class AppImageBuildTest(unittest.TestCase):
    def test_pr_assembles_arm64_and_release_packages_it(self):
        workflows = Path(__file__).resolve().parents[1] / ".github/workflows"
        verify = (workflows / "reusable-desktop-verify.yml").read_text()
        release = (workflows / "reusable-desktop-linux.yml").read_text()
        self.assertIn("./gradlew :desktopApp:assemble --no-daemon", verify)
        self.assertIn(":desktopApp:compileKotlinJvm :shared:desktopTest", verify)
        self.assertIn("tasks+=(:desktopApp:jvmTest :desktopApp:ktlintCheck)", verify)
        self.assertNotIn("./scripts/build_appimage.sh", verify)
        self.assertIn("./scripts/build_appimage.sh", release)
        self.assertIn("Expected aarch64 AppImage", release)
        builder = (Path(__file__).with_name("build_appimage.sh")).read_text()
        self.assertNotIn(":desktopApp:clean", builder)
        self.assertNotIn("--no-build-cache", builder)
        self.assertIn("--no-configuration-cache", builder)
        for workflow in ["reusable-desktop-macos.yml", "reusable-desktop-windows.yml"]:
            package = (workflows / workflow).read_text()
            self.assertNotIn(":desktopApp:clean", package)
            self.assertNotIn("--no-build-cache", package)
            self.assertIn("--no-configuration-cache", package)
            self.assertIn("persist-configuration-cache: 'false'", package)

    def test_webview_cache_is_shared_but_pull_requests_cannot_write(self):
        github = Path(__file__).resolve().parents[1] / ".github"
        action = (github / "actions/prepare-linux-webview/action.yml").read_text()
        read_only = action.split("    - name: Restore WebView compilation (read-only)\n", 1)[1].split("    - name:", 1)[0]
        writable = action.split("    - name: Cache WebView compilation\n", 1)[1]
        self.assertIn("if: inputs.cache-read-only == 'true'", read_only)
        self.assertIn("uses: actions/cache/restore@", read_only)
        self.assertIn("if: inputs.cache-read-only != 'true'", writable)
        self.assertIn("uses: actions/cache@", writable)
        for cache in [read_only, writable]:
            self.assertIn("~/.cargo/registry", cache)
            self.assertIn("desktopApp/build/linux-webview/target", cache)
            self.assertIn("steps.toolchain.outputs.libraries", cache)
            self.assertNotIn("restore-keys:", cache)
        self.assertEqual(read_only.split("key: ")[1].strip(), writable.split("key: ")[1].strip())
        for workflow in ["reusable-desktop-verify.yml", "reusable-desktop-linux.yml"]:
            text = (github / "workflows" / workflow).read_text()
            preparation = text.split("      - name: Prepare ARM64 WebView build\n", 1)[1].split("      - name:", 1)[0]
            self.assertIn("if: matrix.arch == 'arm64'", preparation)
            self.assertIn("uses: ./.github/actions/prepare-linux-webview", preparation)
            self.assertIn("cache-read-only: ${{ inputs.cache-read-only }}", preparation)

    def run_builder(self, architecture, cached=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            shutil.copyfile(Path(__file__).with_name("build_appimage.sh"), root / "scripts/build_appimage.sh")
            tools = root / "tools"
            tools.mkdir()
            if cached:
                (tools / "appimagetool.AppImage").write_text("corrupted cache")
            commands = root / "commands"
            commands.mkdir()
            scripts = {
                "uname": '#!/bin/sh\nprintf "%s\\n" "$TEST_ARCH"\n',
                "curl": '''#!/usr/bin/env python3
import json
from pathlib import Path
import sys
Path("download.json").write_text(json.dumps(sys.argv[1:]))
Path(sys.argv[sys.argv.index("--output") + 1]).write_bytes(b"corrupted download")
''',
            }
            for name, content in scripts.items():
                command = commands / name
                command.write_text(content)
                command.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text("#!/bin/sh\ntouch gradle-ran\n")
            gradle.chmod(0o755)
            result = subprocess.run(
                ["bash", str(root / "scripts/build_appimage.sh")],
                cwd=root,
                env={**os.environ, "PATH": f"{commands}:{os.environ['PATH']}", "TEST_ARCH": architecture},
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertNotEqual(result.returncode, 0, "Untrusted downloads must fail closed")
            self.assertFalse((root / "gradle-ran").exists())
            download = root / "download.json"
            return result, json.loads(download.read_text()) if download.exists() else None, (tools / "appimagetool.AppImage").exists()

    def test_cached_tool_and_gradle_override_work_outside_repository(self):
        for produces_output in (True, False):
            with self.subTest(produces_output=produces_output), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "scripts").mkdir()
                shutil.copyfile(Path(__file__).with_name("build_appimage.sh"), root / "scripts/build_appimage.sh")
                (root / "tools").mkdir()
                tool = root / "tools/appimagetool.AppImage"
                tool.write_text("verified test tool")
                tool.chmod(0o644)
                commands = root / "commands"
                commands.mkdir()
                for name, content in {
                    "sha256sum": "#!/bin/sh\ncat >/dev/null\n",
                    "uname": "#!/bin/sh\necho x86_64\n",
                }.items():
                    command = commands / name
                    command.write_text(content)
                    command.chmod(0o755)
                gradle = root / "serialized-gradle"
                gradle.write_text("#!/bin/sh\npwd > gradle-cwd\nprintf '%s\\n' \"$@\" > gradle-args\n" +
                                  ("mkdir -p dist\nprintf image > dist/Metrolist.AppImage\n" if produces_output else ""))
                gradle.chmod(0o755)
                result = subprocess.run(
                    ["bash", str(root / "scripts/build_appimage.sh"), "--offline"],
                    cwd=commands, capture_output=True, text=True,
                    env={**os.environ, "PATH": f"{commands}:{os.environ['PATH']}", "GRADLE": str(gradle)},
                    timeout=10,
                )
                self.assertEqual(result.returncode == 0, produces_output, result.stderr)
                self.assertEqual((root / "gradle-cwd").read_text().strip(), str(root))
                self.assertIn("--offline", (root / "gradle-args").read_text().splitlines())
                self.assertTrue(os.access(tool, os.X_OK))
                if produces_output:
                    self.assertTrue(os.access(root / "dist/Metrolist.AppImage", os.X_OK))
                else:
                    self.assertIn("AppImage build produced no output", result.stderr)

    def test_native_mode_preserves_jvm_artifact_and_clears_source_epoch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for directory in ('scripts','tools','commands','dist','native app'):
                (root/directory).mkdir()
            shutil.copyfile(Path(__file__).with_name('build_appimage.sh'),root/'scripts/build_appimage.sh')
            (root/'dist/Metrolist.AppImage').write_text('JVM artifact')
            (root/'native app/AppRun').write_text('#!/bin/sh\n');(root/'native app/AppRun').chmod(0o755)
            tool=root/'tools/appimagetool.AppImage'
            tool.write_text('#!/bin/sh\ntest -z "${SOURCE_DATE_EPOCH+x}" || exit 2\nprintf native > "$3"\n')
            tool.chmod(0o755)
            for name,body in {'sha256sum':'cat >/dev/null','uname':'echo x86_64','gradle':'exit 99'}.items():
                path=root/'commands'/name;path.write_text('#!/bin/sh\n'+body+'\n');path.chmod(0o755)
            output=root/'dist/Native.AppImage'
            result=subprocess.run(['bash',str(root/'scripts/build_appimage.sh'),'--native-appdir',str(root/'native app'),str(output)],capture_output=True,text=True,env={**os.environ,'PATH':str(root/'commands')+':'+os.environ['PATH'],'SOURCE_DATE_EPOCH':'1','GRADLE':str(root/'commands/gradle')})
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(output.read_text(),'native')
            self.assertEqual((root/'dist/Metrolist.AppImage').read_text(),'JVM artifact')

    def test_selects_native_asset_and_rejects_untrusted_download(self):
        for architecture, asset in [("x86_64", "x86_64"), ("amd64", "x86_64"), ("aarch64", "aarch64"), ("arm64", "aarch64")]:
            with self.subTest(architecture=architecture):
                _, download, installed = self.run_builder(architecture)
                self.assertIn(f"https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-{asset}.AppImage", download)
                self.assertFalse(installed)

    def test_discards_corrupted_cache_before_downloading(self):
        _, download, installed = self.run_builder("aarch64", cached=True)
        self.assertIsNotNone(download)
        self.assertFalse(installed)

    def test_rejects_unsupported_host_before_touching_cache(self):
        result, download, installed = self.run_builder("riscv64", cached=True)
        self.assertIn("Unsupported Linux architecture: riscv64", result.stderr)
        self.assertIsNone(download)
        self.assertTrue(installed)


if __name__ == "__main__":
    unittest.main()
