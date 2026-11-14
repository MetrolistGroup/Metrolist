#!/usr/bin/env python3
"""Install the latest Metrolist iOS release with the Sideloader CLI."""

from __future__ import annotations

import argparse
import ctypes.util
import hashlib
import http.client
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Final, Literal, Sequence, TypedDict, cast


REPOSITORY: Final[str] = "MetrolistGroup/Metrolist"
SIDELOADER_REPOSITORY: Final[str] = "Dadoum/Sideloader"
WINDOWS_DEVICE_LIBRARIES_REPOSITORY: Final[str] = "libimobiledevice-win32/imobiledevice-net"
WINDOWS_ITUNES: Final[str] = "https://www.apple.com/itunes/download/win64"
USER_AGENT: Final[str] = "Metrolist-iOS-Installer/1.0"
SIDELOADER_RELEASE: Final[str] = "1.0-pre4"
WINDOWS_DEVICE_LIBRARIES_RELEASE: Final[str] = "v1.3.17"
PINNED_ASSET_SHA256: Final[dict[str, str]] = {
    "sideloader-cli-aarch64-linux-gnu.zip": "3bd61364aee655a44a99837e2f8046d4d9f2455b401a8c32aa909b5de9e6de03",
    "sideloader-cli-arm64-apple-macos.zip": "7cc8e17dec05a89a5a1d982d401357151953c47f907589eebd8f5102357b9b90",
    "sideloader-cli-x86_64-apple-darwin.zip": "23b38b94b579ec4321d77189a46490098901cc01db8d84219c11ffabb0daba02",
    "sideloader-cli-x86_64-linux-gnu.zip": "e193c02d661a5d797af4b44186c8655d078a0bfde419bdeab934c513d1a7b737",
    "sideloader-cli-x86_64-windows-msvc.zip": "c60b7d92c6a5dcc312cb6eb2fe1a855326722aa0b3aebf6827689241dbc0f5a9",
    "libimobiledevice.1.2.1-r1122-win-x64.zip": "d7cb57a71270848c35c3f01006701535aadf6dfb52325863ea368c94a34a2cab",
}


class ReleaseAsset(TypedDict):
    name: str
    browser_download_url: str
    size: int


class GitHubRelease(TypedDict):
    tag_name: str
    assets: list[ReleaseAsset]


class InstallError(RuntimeError):
    """An expected setup, download, or installation failure."""


def run_command(
    arguments: Sequence[str],
    *,
    capture_output: bool = False,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            list(arguments),
            text=True,
            capture_output=capture_output,
            check=False,
        )
    except OSError as error:
        raise InstallError(f"Could not start {Path(arguments[0]).name}: {error}") from error
    if check and result.returncode != 0:
        raise InstallError(f"{Path(arguments[0]).name} exited with code {result.returncode}.")
    return result


def request_bytes(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return response.read()
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError) as error:
        raise InstallError(f"Download failed from {url}: {error}") from error


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            with destination.open("wb") as output:
                shutil.copyfileobj(response, output)
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError) as error:
        destination.unlink(missing_ok=True)
        raise InstallError(f"Download failed from {url}: {error}") from error


def latest_release() -> GitHubRelease:
    url = f"https://api.github.com/repos/{REPOSITORY}/releases/latest"
    try:
        payload = json.loads(request_bytes(url))
    except json.JSONDecodeError as error:
        raise InstallError("GitHub returned invalid release data.") from error
    if not isinstance(payload, dict):
        raise InstallError("GitHub returned invalid release data.")
    tag = payload.get("tag_name")
    assets = payload.get("assets")
    if not isinstance(tag, str) or not isinstance(assets, list):
        raise InstallError("GitHub returned incomplete release metadata.")
    if not all(
        isinstance(asset, dict)
        and isinstance(asset.get("name"), str)
        and isinstance(asset.get("browser_download_url"), str)
        and isinstance(asset.get("size"), int)
        for asset in assets
    ):
        raise InstallError("GitHub returned invalid release asset metadata.")
    return cast(GitHubRelease, payload)


def release_asset_url(
    release: GitHubRelease,
    asset_name: Literal["Metrolist.ipa", "SHA256SUMS"],
) -> str:
    for asset in release["assets"]:
        if asset["name"] == asset_name:
            url = asset["browser_download_url"]
            try:
                parsed = urllib.parse.urlparse(url)
            except ValueError as error:
                raise InstallError(
                    f"Release asset {asset_name} has an invalid download URL."
                ) from error
            if parsed.scheme != "https" or parsed.hostname != "github.com":
                raise InstallError(f"Release asset {asset_name} has an unexpected download URL.")
            return url
    raise InstallError(
        f"Release {release['tag_name']} does not contain {asset_name}. "
        "Publish the iOS IPA and SHA256SUMS in the latest release."
    )


def sideloader_asset_name() -> str:
    machine = platform.machine().lower()
    if sys.platform.startswith("linux"):
        if machine in {"x86_64", "amd64"}:
            architecture = "x86_64"
        elif machine in {"aarch64", "arm64"}:
            architecture = "aarch64"
        else:
            raise InstallError(f"Dadoum Sideloader does not publish a Linux CLI for {machine}.")
        return f"sideloader-cli-{architecture}-linux-gnu.zip"
    if sys.platform == "darwin":
        if machine in {"arm64", "aarch64"}:
            return "sideloader-cli-arm64-apple-macos.zip"
        if machine in {"x86_64", "amd64"}:
            return "sideloader-cli-x86_64-apple-darwin.zip"
        raise InstallError(f"Dadoum Sideloader does not publish a macOS CLI for {machine}.")
    if os.name == "nt" and machine in {"x86_64", "amd64"}:
        return "sideloader-cli-x86_64-windows-msvc.zip"
    raise InstallError(f"Dadoum Sideloader does not publish a CLI for {sys.platform} on {machine}.")


def github_release_asset(
    repository: str,
    asset_name: str,
    *,
    release_tag: str | None = None,
    releases_endpoint: bool = False,
) -> tuple[str, str, int]:
    endpoint = f"https://api.github.com/repos/{repository}/releases"
    if release_tag is not None:
        endpoint += f"/tags/{urllib.parse.quote(release_tag, safe='')}"
    elif releases_endpoint:
        endpoint += "?per_page=20"
    else:
        endpoint += "/latest"
    try:
        releases = json.loads(request_bytes(endpoint))
    except json.JSONDecodeError as error:
        raise InstallError("GitHub returned invalid release data.") from error
    candidates = releases if isinstance(releases, list) else [releases]
    for release in candidates:
        if not isinstance(release, dict):
            continue
        tag = release.get("tag_name")
        assets = release.get("assets")
        if not isinstance(tag, str) or not isinstance(assets, list):
            continue
        for asset in assets:
            if not isinstance(asset, dict) or asset.get("name") != asset_name:
                continue
            url = asset.get("browser_download_url")
            size = asset.get("size")
            if not isinstance(url, str) or not isinstance(size, int):
                raise InstallError("GitHub returned invalid release asset metadata.")
            try:
                parsed = urllib.parse.urlparse(url)
            except ValueError as error:
                raise InstallError("GitHub returned an invalid release asset URL.") from error
            if parsed.scheme != "https" or parsed.hostname != "github.com":
                raise InstallError("GitHub returned a release asset with an unexpected URL.")
            return tag, url, size
    raise InstallError(f"No {asset_name} asset was found in releases for {repository}.")


def latest_sideloader_asset() -> tuple[str, str, str, int]:
    asset_name = sideloader_asset_name()
    tag, url, size = github_release_asset(
        SIDELOADER_REPOSITORY,
        asset_name,
        release_tag=SIDELOADER_RELEASE,
    )
    if tag != SIDELOADER_RELEASE:
        raise InstallError(f"Review and pin the Sideloader {tag} release before using it.")
    return tag, asset_name, url, size


def latest_windows_device_libraries_asset() -> tuple[str, str, str, int]:
    name = "libimobiledevice.1.2.1-r1122-win-x64.zip"
    tag, url, size = github_release_asset(
        WINDOWS_DEVICE_LIBRARIES_REPOSITORY,
        name,
        release_tag=WINDOWS_DEVICE_LIBRARIES_RELEASE,
    )
    return tag, name, url, size


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_verified_release_ipa(release: GitHubRelease, destination: Path) -> str:
    ipa_url = release_asset_url(release, "Metrolist.ipa")
    sums_url = release_asset_url(release, "SHA256SUMS")
    destination.parent.mkdir(parents=True, exist_ok=True)
    sums_path = destination.with_name("SHA256SUMS")
    download(sums_url, sums_path)
    download(ipa_url, destination)
    expected: str | None = None
    try:
        checksum_lines = sums_path.read_text(encoding="utf-8").splitlines()
    except UnicodeError as error:
        destination.unlink(missing_ok=True)
        sums_path.unlink(missing_ok=True)
        raise InstallError("SHA256SUMS is not valid UTF-8 text.") from error
    for line in checksum_lines:
        match = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+)$", line)
        if match and Path(match.group(2)).name == destination.name:
            expected = match.group(1).lower()
            break
    if expected is None:
        destination.unlink(missing_ok=True)
        sums_path.unlink(missing_ok=True)
        raise InstallError("SHA256SUMS does not contain a checksum for Metrolist.ipa.")
    actual = sha256_file(destination)
    if actual != expected:
        destination.unlink(missing_ok=True)
        sums_path.unlink(missing_ok=True)
        raise InstallError("IPA checksum mismatch; the downloaded file was rejected.")
    sums_path.unlink(missing_ok=True)
    return ipa_url


def missing_linux_dependencies() -> tuple[str, ...]:
    requirements = {
        "libimobiledevice": ("imobiledevice-1.0", "imobiledevice"),
        "OpenSSL": ("ssl",),
    }
    missing = [
        name
        for name, library_names in requirements.items()
        if not any(ctypes.util.find_library(library_name) for library_name in library_names)
    ]
    if not shutil.which("usbmuxd") and not any(
        path.is_file() for path in (Path("/usr/sbin/usbmuxd"), Path("/usr/bin/usbmuxd"))
    ):
        missing.append("usbmuxd")
    return tuple(missing)


def require_compatible_libplist(version: str) -> None:
    match = re.match(r"^(\d+)\.(\d+)(?:\.(\d+))?", version)
    if not match or (int(match.group(1)), int(match.group(2))) > (2, 2):
        raise InstallError("Sideloader 1.0-pre4 requires libplist 2.2.x or older; upgrade Sideloader before using newer libplist.")


def linux_libplist_version() -> str:
    pkg_config = shutil.which("pkg-config")
    if not pkg_config:
        raise InstallError("Install pkg-config and a compatible libplist 2.2.x before running Sideloader.")
    result = run_command([pkg_config, "--modversion", "libplist-2.0"], capture_output=True, check=False)
    if result.returncode != 0:
        raise InstallError("Install a compatible libplist 2.2.x before running Sideloader.")
    return result.stdout.strip()


def require_linux_libplist() -> None:
    require_compatible_libplist(linux_libplist_version())


def run_linux_package_manager(arguments: Sequence[str]) -> None:
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        run_command(arguments)
        return
    sudo = shutil.which("sudo")
    if not sudo:
        raise InstallError("Install system dependencies as root or install sudo, then rerun this script.")
    run_command([sudo, *arguments])


def install_linux_dependencies() -> None:
    require_linux_libplist()
    if not missing_linux_dependencies():
        return
    try:
        release = platform.freedesktop_os_release()
    except OSError as error:
        raise InstallError("Could not identify the Linux distribution to install dependencies.") from error
    distribution_ids = {release.get("ID", "").lower(), *release.get("ID_LIKE", "").lower().split()}

    if distribution_ids.intersection({"arch", "manjaro", "endeavouros"}) and shutil.which("pacman"):
        run_linux_package_manager(
            ["pacman", "-S", "--needed", "libimobiledevice", "openssl", "usbmuxd"]
        )
    elif distribution_ids.intersection({"debian", "ubuntu", "linuxmint", "pop"}) and shutil.which("apt-get"):
        run_linux_package_manager(["apt-get", "update"])
        run_linux_package_manager(
            [
                "apt-get",
                "install",
                "--yes",
                "libimobiledevice-dev",
                "libssl-dev",
                "usbmuxd",
            ]
        )
    elif distribution_ids.intersection({"fedora", "rhel", "centos"}) and shutil.which("dnf"):
        run_linux_package_manager(
            ["dnf", "install", "--assumeyes", "libimobiledevice", "openssl-libs", "usbmuxd"]
        )
    else:
        missing = ", ".join(missing_linux_dependencies())
        raise InstallError(f"Unsupported Linux package manager or distribution. Install {missing} manually.")

    missing = missing_linux_dependencies()
    if missing:
        raise InstallError(f"Linux dependencies are still missing after setup: {', '.join(missing)}.")


def install_macos_dependencies() -> None:
    brew = shutil.which("brew")
    if not brew:
        raise InstallError(
            "Install Homebrew from https://brew.sh, then rerun this script to install Sideloader dependencies."
        )
    version_result = run_command([brew, "list", "--versions", "libplist"], capture_output=True, check=False)
    version_match = re.search(r"\blibplist\s+(\S+)", version_result.stdout)
    if not version_match:
        raise InstallError("Install a compatible libplist 2.2.x with Homebrew before running Sideloader.")
    require_compatible_libplist(version_match.group(1))
    packages = ("libimobiledevice", "openssl@3")
    run_command([brew, "install", *packages])
    library_dirs: list[str] = []
    for package in packages:
        result = run_command([brew, "--prefix", package], capture_output=True)
        prefix = Path(result.stdout.strip())
        for library_dir in (prefix / "lib", prefix / "libexec" / "lib"):
            if library_dir.is_dir() and str(library_dir) not in library_dirs:
                library_dirs.append(str(library_dir))
    existing = os.environ.get("DYLD_LIBRARY_PATH")
    if existing:
        library_dirs.append(existing)
    os.environ["DYLD_LIBRARY_PATH"] = os.pathsep.join(library_dirs)


def windows_device_support_installed() -> bool:
    roots = (
        Path(os.environ.get("ProgramFiles", r"C:\Program Files")),
        Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")),
    )
    return any(
        (root / "Common Files" / "Apple" / "Mobile Device Support" / "AppleMobileDeviceService.exe").is_file()
        for root in roots
    )


def install_windows_device_support(download_dir: Path) -> None:
    if windows_device_support_installed():
        return
    installer = download_dir / "iTunesSetup.exe"
    print("Downloading Apple's iTunes installer for the iOS device driver...")
    download(WINDOWS_ITUNES, installer)
    print("Starting Apple's installer. Complete its setup wizard; Sideloader setup will resume afterward.")
    run_command([str(installer)], check=False)
    if not windows_device_support_installed():
        raise InstallError("Apple Mobile Device Support was not found after the iTunes setup.")


def install_windows_device_libraries(install_dir: Path) -> None:
    tag, archive_name, archive_url, expected_size = latest_windows_device_libraries_asset()
    archive_path = install_dir / archive_name
    print(f"Downloading Windows device libraries {tag}...")
    download(archive_url, archive_path)
    if archive_path.stat().st_size != expected_size:
        archive_path.unlink(missing_ok=True)
        raise InstallError("The Windows device-library archive size does not match GitHub metadata.")
    if sha256_file(archive_path) != PINNED_ASSET_SHA256[archive_name]:
        archive_path.unlink(missing_ok=True)
        raise InstallError("The Windows device-library checksum does not match the reviewed release.")
    required = {
        "imobiledevice.dll",
        "plist.dll",
        "usbmuxd.dll",
        "libssl-1_1-x64.dll",
        "libcrypto-1_1-x64.dll",
    }
    found: set[str] = set()
    try:
        with zipfile.ZipFile(archive_path) as archive:
            if archive.testzip() is not None:
                raise InstallError("The Windows device-library archive failed its ZIP integrity check.")
            for member in archive.infolist():
                name = Path(member.filename).name
                if member.is_dir() or not name.lower().endswith(".dll"):
                    continue
                destination = install_dir / name
                with archive.open(member) as source, destination.open("wb") as output:
                    shutil.copyfileobj(source, output)
                found.add(name.lower())
    except zipfile.BadZipFile as error:
        raise InstallError("The Windows device-library download is not a valid ZIP archive.") from error
    except OSError as error:
        raise InstallError(f"Could not install Windows device libraries: {error}") from error
    finally:
        archive_path.unlink(missing_ok=True)
    missing = required - found
    if missing:
        raise InstallError(f"The Windows device-library archive is missing: {', '.join(sorted(missing))}.")


def install_sideloader(install_dir: Path) -> Path:
    tag, archive_name, archive_url, expected_size = latest_sideloader_asset()
    if sys.platform.startswith("linux"):
        install_linux_dependencies()
    elif sys.platform == "darwin":
        install_macos_dependencies()
    elif os.name == "nt":
        install_windows_device_support(install_dir)

    install_dir.mkdir(parents=True, exist_ok=True)
    archive_path = install_dir / archive_name
    print(f"Downloading Dadoum Sideloader {tag}...")
    download(archive_url, archive_path)
    if archive_path.stat().st_size != expected_size:
        archive_path.unlink(missing_ok=True)
        raise InstallError("The Sideloader archive size does not match GitHub release metadata.")
    if sha256_file(archive_path) != PINNED_ASSET_SHA256[archive_name]:
        archive_path.unlink(missing_ok=True)
        raise InstallError("The Sideloader archive checksum does not match the reviewed release.")

    binary_name = archive_name.removesuffix(".zip")
    executable_name = binary_name + (".exe" if os.name == "nt" else "")
    executable = install_dir / executable_name
    temporary_executable = install_dir / f"{executable_name}.tmp"
    try:
        with zipfile.ZipFile(archive_path) as archive:
            if archive.testzip() is not None:
                raise InstallError("The Sideloader archive failed its ZIP integrity check.")
            binary_member = next(
                (member for member in archive.infolist() if Path(member.filename).name == executable_name),
                None,
            )
            if binary_member is None:
                raise InstallError(f"The Sideloader archive does not contain {executable_name}.")
            with archive.open(binary_member) as source, temporary_executable.open("wb") as destination:
                shutil.copyfileobj(source, destination)
        if os.name != "nt":
            temporary_executable.chmod(0o755)
        temporary_executable.replace(executable)
    except zipfile.BadZipFile as error:
        temporary_executable.unlink(missing_ok=True)
        raise InstallError("The downloaded Sideloader file is not a valid ZIP archive.") from error
    except OSError as error:
        temporary_executable.unlink(missing_ok=True)
        raise InstallError(f"Could not install the Sideloader binary: {error}") from error
    finally:
        archive_path.unlink(missing_ok=True)
    if os.name == "nt":
        install_windows_device_libraries(install_dir)
    return executable


def main() -> int:
    argparse.ArgumentParser(
        description="Install the latest Metrolist iOS release with Dadoum Sideloader."
    ).parse_args()
    if not sys.platform.startswith("linux") and sys.platform != "darwin" and os.name != "nt":
        raise InstallError("Run this script on Linux, macOS, or Windows.")

    with tempfile.TemporaryDirectory(prefix="metrolist-ios-") as temporary:
        work_dir = Path(temporary)
        tool_dir = work_dir / "tools"
        sideloader = install_sideloader(tool_dir)

        release = latest_release()
        print(f"Downloading Metrolist {release['tag_name']} and verifying SHA-256...")
        ipa_path = work_dir / "Metrolist.ipa"
        download_verified_release_ipa(release, ipa_path)
        print("Sideloader will prompt for your Apple ID and 2FA, then sign and install Metrolist.")
        run_command([str(sideloader), "install", str(ipa_path), "-i"])
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (InstallError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
