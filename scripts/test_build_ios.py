#!/usr/bin/env python3
"""Check iOS build configurations without invoking Xcode or touching build outputs."""
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tempfile


def main():
    repository = Path(__file__).resolve().parents[1]
    callers = []
    for workflow in (repository / ".github/workflows").glob("*.yml"):
        text = "\n".join(line for line in workflow.read_text().splitlines() if not line.lstrip().startswith("#"))
        for job in re.split(r"(?m)^  [\w-]+:\s*$", text):
            if "    uses: ./.github/workflows/reusable-ios-build.yml" not in job:
                continue
            callers.append(workflow.name)
            for secret in ("LASTFM_API_KEY", "LASTFM_SECRET", "LYPRIO_BASE_URL", "LYPRIO_HMAC_KEY"):
                assert f"      {secret}: ${{{{ secrets.{secret} }}}}" in job, (workflow.name, secret)
            if workflow.name == "release.yml":
                assert "      build-ipa: true" in job
    assert callers, "No iOS workflow callers checked"

    verify = (repository / ".github/workflows/build-ios.yml").read_text()
    assert "      upload-artifact: ${{ github.event_name == 'workflow_dispatch' }}" in verify
    assert "  compile:" not in verify, "Linux cannot compile the iOS targets"
    assert "uses: ./.github/actions/ci-changes" in verify
    assert "needs.changes.outputs.platform == 'true'" in verify
    assert "    needs: changes" in verify

    xcode_project = (repository / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text()
    xcconfig_path = repository / "iosApp/Configuration/Config.xcconfig"
    xcconfig = xcconfig_path.read_text()
    inherited_settings = {
        "PRODUCT_NAME": "Metrolist",
        "PRODUCT_BUNDLE_IDENTIFIER": "com.metrolist.MetrolistApp",
        "CODE_SIGN_ENTITLEMENTS": "Configuration/iosApp.entitlements",
        "SWIFT_VERSION": "5.9",
        "IPHONEOS_DEPLOYMENT_TARGET": "17.0",
        "SDKROOT": "iphoneos",
        "TARGETED_DEVICE_FAMILY": "1,2",
    }
    assert xcode_project.count("baseConfigurationReference = 0418C23F19355BB18E625B53 /* Config.xcconfig */;") == 4
    framework_phase = xcode_project.split('name = "Compile Kotlin Multiplatform Framework";', 1)[1].split("/* End PBXShellScriptBuildPhase section */", 1)[0]
    assert "./gradlew :shared:embedAndSignAppleFrameworkForXcode" in framework_phase
    assert "sourceRevision" not in framework_phase, "The Xcode archive must use the real commit revision"
    assert "bash scripts/ios_version.sh" in framework_phase
    assert '"$(DERIVED_FILE_DIR)/MetrolistVersion.h"' in framework_phase
    assert "INFOPLIST_PREPROCESS = YES" in xcconfig
    assert "INFOPLIST_PREFIX_HEADER = $(DERIVED_FILE_DIR)/MetrolistVersion.h" in xcconfig
    for setting, value in inherited_settings.items():
        assert f"{setting} = {value}" in xcconfig, setting
        assert f"{setting} =" not in xcode_project, setting
    entitlements = plistlib.loads((repository / "iosApp/Configuration/iosApp.entitlements").read_bytes())
    assert entitlements["com.apple.developer.associated-domains"] == ["applinks:metrolist.cc"]
    build_script = (repository / "scripts/build_ios.sh").read_text()
    for check in ("CFBundleIdentifier", "CFBundleShortVersionString", "CFBundleVersion", "lipo", "vtool", "unzip -tq"):
        assert check in build_script, f"IPA check missing: {check}"

    reusable = (repository / ".github/workflows/reusable-ios-build.yml").read_text()
    reusable_purged = "\n".join(line for line in reusable.splitlines() if not line.lstrip().startswith("#"))
    assert 'if: inputs.staging-release-tag != \'\'' in reusable_purged
    assert 'gh release upload "$STAGING_RELEASE_TAG" dist/Metrolist.ipa' in reusable_purged
    assert "if: inputs.build-ipa || inputs.upload-artifact || inputs.staging-release-tag != ''" in reusable_purged
    assert "Build iOS Simulator" not in reusable_purged
    test_job = reusable_purged.split("  build-ios:\n", 1)[1].split("  package-ios:\n", 1)[0]
    package_job = reusable_purged.split("  package-ios:\n", 1)[1]
    assert "    needs:" not in package_job, "Packaging must run alongside backend tests"
    assert "    if: inputs.build-ipa || inputs.upload-artifact || inputs.staging-release-tag != ''" in package_job
    assert "      - name: Validate Compose resources" in test_job
    assert "      - name: Validate build script" in test_job
    assert "      - name: Validate Swift Syntax" in test_job
    assert "      - name: Run iOS backend tests" in test_job
    assert "      - name: Build iOS IPA" not in test_job
    assert "      - name: Build iOS IPA" in package_job
    assert "      - name: Validate tester release credentials" in package_job
    for job, scope, kind in ((test_job, "ios-test", "test"), (package_job, "ios-package", "package")):
        assert f"          cache-scope: {scope}" in job
        native_cache = job.split("      - name: Cache Kotlin Native\n", 1)[1].split("      - name:", 1)[0]
        native_restore = job.split("      - name: Restore Kotlin Native (read-only)\n", 1)[1].split("      - name:", 1)[0]
        assert "if: ${{ !inputs.cache-read-only }}" in native_cache
        assert "uses: actions/cache@" in native_cache
        assert "if: inputs.cache-read-only" in native_restore
        assert "uses: actions/cache/restore@" in native_restore
        for cache in (native_cache, native_restore):
            assert "path: ~/.konan" in cache
            assert "steps.native-toolchain.outputs.xcode" in cache
            assert f"-{kind}" in cache
            key, prefix = cache.split("key: ", 1)[1].split("\n          restore-keys: |\n")
            assert "hashFiles('**/*.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties', 'shared/src/iosMain/cinterop/**')" in key
            prefixes = [line.strip() for line in prefix.splitlines() if line.strip()]
            assert len(prefixes) == 2
            assert key.startswith(prefixes[0]), "Fallbacks must match OS, architecture, Kotlin, Xcode and task graph"
            assert prefixes[0].endswith(f"-{kind}-")
            assert prefixes[1] == prefixes[0].removesuffix("-"), "Retain the previous toolchain-only cache for migration"
        assert native_cache.split("key: ")[1].strip() == native_restore.split("key: ")[1].strip()
    tests = test_job.split("      - name: Run iOS backend tests\n", 1)[1].split("      - name:", 1)[0]
    package = package_job.split("      - name: Build iOS IPA\n", 1)[1].split("      - name:", 1)[0]
    assert "-Pmetrolist.sourceRevision=" in tests
    assert "sourceRevision" not in package, "Published apps must retain commit provenance"
    gradle_props = (repository / "gradle.properties").read_text()
    assert "org.gradle.jvmargs=-Xmx4g" in gradle_props, "Local Gradle daemons must remain bounded"
    assert "kotlin.compiler.execution.strategy=in-process" in gradle_props, "Kotlin must share the bounded daemon heap"
    assert "org.gradle.workers.max=4" in gradle_props, "Local builds must cap workers sharing the daemon heap"
    assert "MaxRAMPercentage" not in gradle_props, "Runner-sized heaps must not apply to local builds"
    assert '-Dorg.gradle.jvmargs="-XX:MaxRAMPercentage=60.0' in reusable_purged, (
        "iOS release linking needs a runner-sized Gradle heap"
    )
    assert "-Dkotlin.daemon.jvm.options=-XX:MaxRAMPercentage=60.0" in reusable_purged, (
        "iOS release linking needs a runner-sized Kotlin heap"
    )
    assert "Raise iOS build daemon heap" not in reusable_purged, "iOS heap-cap sed hack must not return"
    assert 'ORG_GRADLE_PROJECT_org.gradle.workers.max: "2"' in reusable_purged, "iOS worker bound required"
    assert "--no-daemon" not in reusable_purged, "iOS build must reuse the Gradle daemon"

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "scripts").mkdir()
        (root / "bin").mkdir()
        (root / "gradle.properties").write_text("appVersion=14.0.0-a16\n")
        script = root / "scripts/build_ios.sh"
        shutil.copy2(Path(__file__).with_name("build_ios.sh"), script)
        shutil.copy2(Path(__file__).with_name("ios_version.sh"), root / "scripts/ios_version.sh")
        xcode = root / "bin/xcodebuild"
        xcode.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$BUILD_TEST_ARGS"\nexit 77\n')
        xcode.chmod(0o755)
        args_file = root / "arguments"
        env = dict(os.environ, PATH=f'{root / "bin"}:{os.environ["PATH"]}', BUILD_TEST_ARGS=str(args_file))
        for version in ("14.0.0-a16", "14.0.0-rc.test-42", "14.0.0"):
            (root / "gradle.properties").write_text(f"appVersion={version}\n")
            header = root / "version.h"
            subprocess.run(["bash", str(root / "scripts/ios_version.sh"), str(header)], check=True)
            assert header.read_text() == "#define METROLIST_VERSION 14.0.0\n"
            plist = subprocess.run(
                ["cpp", "-P", "-traditional-cpp", "-include", str(header),
                 str(repository / "iosApp/Configuration/Info.plist")],
                check=True, capture_output=True,
            )
            info = plistlib.loads(plist.stdout.strip())
            assert info["CFBundleShortVersionString"] == "14.0.0"
            assert info["CFBundleVersion"] == "14.0.0"
            for option, configuration, command in (
                ("--ipa", "Release", "archive"),
                ("--release", "Release", "archive"),
                ("--sim", "Debug", "build"),
                ("--device", "Debug", "build"),
            ):
                result = subprocess.run(["bash", str(script), option], env=env, capture_output=True, text=True)
                assert result.returncode == 77, (option, result.stdout, result.stderr)
                args = args_file.read_text().splitlines()
                assert args[0] == command, (option, args)
                assert args[args.index("-configuration") + 1] == configuration, (option, args)
                assert "MARKETING_VERSION=14.0.0" in args, (option, args)
                assert "CURRENT_PROJECT_VERSION=14.0.0" in args, (option, args)
                if option in ("--ipa", "--release"):
                    assert "ARCHS=arm64" in args, (option, args)
                    assert "COMPILER_INDEX_STORE_ENABLE=NO" in args, (option, args)
        (root / "gradle.properties").write_text("appVersion=invalid\n")
        assert subprocess.run(["bash", str(root / "scripts/ios_version.sh")], capture_output=True).returncode != 0
        xcode.unlink()
        for name in ("dirname", "mkdir"):
            (root / "bin" / name).symlink_to(shutil.which(name))
        env["PATH"] = str(root / "bin")
        for ci in ("", "true"):
            env["CI"] = ci
            result = subprocess.run([shutil.which("bash"), str(script), "--sim"], env=env, capture_output=True, text=True)
            assert result.returncode != 0
            assert "requires macOS" in result.stderr
    print("iOS build configuration checks passed")


if __name__ == "__main__":
    main()
