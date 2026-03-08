"""Select CI platforms from a Git diff. Unknown paths run every check."""

import argparse
import os
from pathlib import Path
import re
import subprocess


PLATFORMS = ("android", "desktop", "ios", "repository")
ALL_CHECKS = (*PLATFORMS, "arch")
WORKFLOWS = {
    "android": ("build-android.yml", "reusable-android-build.yml"),
    "desktop": ("build-desktop-native.yml", "build-desktop.yml", "reusable-desktop-", "reusable-windows-manager.yml"),
    "ios": ("build-ios.yml", "reusable-ios-build.yml"),
    "repository": ("verify-repository-checks.yml",),
    "arch": ("verify-aur-package.yml",),
}


def classify(paths):
    selected = set()
    shared_only = True
    for path in paths:
        if path.startswith(("shared/src/commonMain/", "shared/src/commonTest/", "docs/")):
            selected.update(("desktop", "repository"))
            continue
        shared_only = False
        if path.startswith(".github/workflows/"):
            name = path.removeprefix(".github/workflows/")
            platform = next((key for key, names in WORKFLOWS.items() if name.startswith(names)), None)
            selected.update((platform,) if platform else ALL_CHECKS)
            selected.add("repository")
        elif path.endswith((".gradle", ".gradle.kts")) or path.startswith(("gradle/", "buildSrc/", "build-logic/")) or path in (
            "gradle.properties", "gradlew", "gradlew.bat", ".editorconfig", ".gitmodules",
            ".github/scripts/ci_paths.py", ".github/scripts/release_verification.py",
        ):
            selected.update(ALL_CHECKS)
        elif path.startswith(("androidApp/", "opusDecoder/", "shared/src/android")) or path in (
            "scripts/collect_android_apks.py", "scripts/test_collect_android_apks.py", "scripts/test_generate_android_strings.py",
        ):
            selected.add("android")
        elif path.startswith(("iosApp/", "shared/src/ios", "shared/src/apple")) or path in (
            "scripts/build_ios.sh", "scripts/test_build_ios.py", "scripts/ios_version.sh",
        ):
            selected.update(("ios", "repository"))
        elif path.startswith(("desktopApp/", "metro-wv2-helper/", "shared/src/desktop")) or path.startswith((
            "scripts/desktop-native-image/", "scripts/graalvm-pins.json", "scripts/build_appimage", "scripts/test_build_appimage", "scripts/build_linux_webview",
            "scripts/build_windows_", "scripts/desktop_smoke", "scripts/test_desktop_smoke", "scripts/test_dmg_packaging",
        )) or path == "scripts/gen_icons.py":
            selected.update(("desktop", "repository"))
        elif path.startswith(("shared/src/jvmShared", "shared/src/jvmTest", "listenTogetherProto/", "metroproto")):
            selected.update(("android", "desktop"))
        elif path.startswith("aur/"):
            selected.update(("arch", "repository"))
        elif path.startswith(("scripts/", ".github/tests/", ".github/scripts/", ".githooks/", "packaging/", "flatpak/", "fastlane/")) or path in (
            "Makefile", "flake.nix", "README.md", "AGENTS.md", "CLAUDE.md", ".gitignore",
        ):
            selected.add("repository")
        else:
            selected.update(ALL_CHECKS)
    return {**{name: name in selected for name in ALL_CHECKS}, "shared-only": shared_only and bool(selected)}


def changed_paths(base, head):
    # Include deleted paths and both sides of renames so platform moves cannot skip a check.
    data = subprocess.check_output(["git", "diff", "--no-renames", "--name-only", "-z", base, head])
    return data.decode().strip("\0").split("\0") if data else []


def properties_without_version(text):
    return "\n".join(line for line in text.splitlines() if not re.match(r"^(appVersion|versionCode)=", line))


def metadata_only(base, head):
    paths = changed_paths(base, head)
    # An empty commit is not release metadata; stopping there keeps the gate on that commit's own runs.
    if not paths:
        return False
    for path in paths:
        if path == "gradle.properties":
            before, after = (subprocess.check_output(["git", "show", f"{sha}:{path}"], text=True) for sha in (base, head))
            if properties_without_version(before) != properties_without_version(after):
                return False
        elif path == "docs/changelog.md" or re.fullmatch(r"fastlane/metadata/android/[^/]+/changelogs/[^/]+\.txt", path):
            continue
        else:
            return False
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("DIFF_BASE", ""))
    parser.add_argument("--head", default=os.environ.get("DIFF_HEAD", "HEAD"))
    args = parser.parse_args()
    try:
        if not args.base or set(args.base) == {"0"}:
            raise ValueError("No diff base")
        paths = changed_paths(args.base, args.head)
        if "gradle.properties" in paths and metadata_only(args.base, args.head):
            print("Version and release metadata only; the release gate checks the source revision.")
            result = {**dict.fromkeys(ALL_CHECKS, False), "shared-only": False}
        else:
            result = classify(paths)
    except (ValueError, subprocess.CalledProcessError):
        print("Cannot read the diff; running every check.")
        result = {**dict.fromkeys(ALL_CHECKS, True), "shared-only": False}
    output = "".join(f"{key}={str(value).lower()}\n" for key, value in result.items())
    print(output, end="")
    if os.environ.get("GITHUB_OUTPUT"):
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as stream:
            stream.write(output)


if __name__ == "__main__":
    main()
