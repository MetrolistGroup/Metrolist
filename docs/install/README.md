# Install Metrolist KMP

Choose your platform: [Android](android.md), [iOS and iPadOS](ios.md), [Windows](windows.md), [macOS](macos.md), or [Linux](linux.md).

## Get the package

Use [Metrolist releases](https://github.com/MetrolistGroup/Metrolist/releases) for downloads. The table describes workflow outputs, not a guarantee that every package is published.

The [release workflow](../../.github/workflows/release.yml) defines these packages. `VERSION` below means the version in the download's filename.

| Platform | Release asset |
| --- | --- |
| Android | `Metrolist.apk` |
| iOS | `Metrolist.ipa` |
| Windows x64 | `Metrolist-Desktop-windows.zip`, `metrolist-installer-windows-x64-VERSION.exe` |
| Windows ARM64 | `metrolist-desktop-windows-arm64-VERSION.zip` |
| macOS | `metrolist-desktop-macos-arm64-VERSION.dmg`, `metrolist-desktop-macos-x64-VERSION.dmg` |
| Linux | `metrolist-desktop-linux-x64-VERSION.AppImage`, `metrolist-desktop-linux-arm64-VERSION.AppImage` |

The workflows do not publish MSI, DEB, RPM or Flatpak files. DEB/RPM Gradle targets and a local Flatpak build exist, but they are not release downloads. See the [desktop reference](../desktop.md#native-packages) for local packaging.

## Verify a download

Download `SHA256SUMS` from the same release. Calculate the package's SHA-256 digest before opening it. Enter the full path when prompted.

On Linux:

```sh
printf 'Downloaded file path: '
read -r package
sha256sum -- "$package"
```

On macOS:

```sh
printf 'Downloaded file path: '
read -r package
shasum -a 256 -- "$package"
```

On Windows, use PowerShell:

```powershell
$package = Read-Host 'Downloaded file path'
Get-FileHash -Algorithm SHA256 -LiteralPath $package
```

Compare all 64 hexadecimal characters with the matching entry in the checksum file. Letter case does not matter.

A mismatch means you should download the file again. Do not install a mismatched file. If the publisher supplies no checksum, you cannot perform this comparison. A checksum confirms that the file matches the publisher's copy; it is not a code-signing certificate.

For Android or iOS, you can verify the APK or IPA on a computer before transferring it unchanged to the device. After installation, check the version in Metrolist's settings, open a song and confirm audible playback. Platform guides add checks for login and signing.

## Before updating or uninstalling

Export a backup from Metrolist's settings and keep it outside the installation directory or mobile app storage. Keep downloaded music separately if you need it. Do not assume a backup includes every downloaded audio file.

Use the same platform, architecture and installation method for updates. Avoid downgrading a database created by a newer version; see [downgrade safety](../crash-recovery.md). Uninstalling a mobile app deletes its local data. Removing a desktop application can leave settings, credentials, caches and downloaded music behind.

If installation or startup still fails, include the package filename, app version, OS version, CPU architecture and exact error in a report to the [issue tracker](https://github.com/MetrolistGroup/Metrolist/issues). For desktop failures, collect the [application log](../desktop.md#logs-and-profiling). Remove account details and tokens before sharing logs.
