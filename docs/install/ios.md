# Install on iOS and iPadOS

## Requirements and download

Use iOS or iPadOS 17 or later. Get `Metrolist.ipa` through [the project channels](README.md#get-the-package) and [verify its checksum](README.md#verify-a-download). The workflow produces an unsigned IPA; sign it for your device before installation.

## Sideloading

Follow your tool's setup and refresh instructions. Its OS support and signing limits can change:

- [AltStore Classic](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows), with [macOS setup](https://faq.altstore.io/altstore-classic/how-to-install-altstore-macos).
- [SideStore](https://docs.sidestore.io/docs/installation/prerequisites).
- [Sideloadly](https://sideloadly.io/faq.html).
- [TrollStore](https://github.com/opa334/TrollStore), only where its supported OS versions overlap this app's minimum.

### Automated installation

On Linux, macOS or Windows with Python 3.10 or later, run `python3 scripts/install_ios.py` from a checkout of Metrolist (or `py scripts/install_ios.py` on Windows). The repository's latest GitHub release must be public and include `Metrolist.ipa` and `SHA256SUMS`.

The script uses a reviewed, checksum-pinned Dadoum [Sideloader CLI](https://github.com/Dadoum/Sideloader) release on Linux x64/ARM64, macOS x64/Apple silicon, and Windows x64. Windows ARM64 is not supported. The tool checksums are updated with each reviewed CLI release. It also verifies the IPA checksum, then asks for the Apple ID, password and 2FA code in the terminal before signing and installing the app. The script does not read or store Apple credentials.

On Linux, the script installs missing device libraries with `pacman`, `apt-get` or `dnf`; the package manager may ask for administrator authentication. On macOS, install [Homebrew](https://brew.sh) first; the script uses it to install Sideloader's libraries. On Windows, the script downloads the required device-library DLLs and starts Apple's web iTunes installer if the Apple Mobile Device driver is missing. Complete that installer if prompted; Sideloader setup then resumes. Keep the iPhone connected, accept its Trust prompt, and enable Developer Mode when iOS requests it.

Use the same signing account and resulting bundle ID for updates. Check the expiry shown by the tool and refresh before it expires. Refresh renews signing; download a new IPA separately to update Metrolist. Keep pairing files private.

To receive updates in AltStore or SideStore, add the source `https://github.com/MetrolistGroup/Metrolist/releases/latest/download/metrolist-altstore.json`. Each stable release regenerates it. Metrolist also shows a notice once per new stable release and opens its release page; it does not install anything.

## Signing and entitlements

The source bundle ID is `com.metrolist.MetrolistApp`. Tools may rewrite it. The Xcode project defines one app target without extensions. No app group is required.

`iosApp/Configuration/iosApp.entitlements` requests Associated Domains for `applinks:metrolist.cc`. `Info.plist` declares background audio and `metrolist`/`metrolist-auth` URL schemes. A signing profile may omit Associated Domains; do not assume HTTPS invitations work after re-signing. See [invitation delivery](../listen-together.md#invitation-links).

For provisioning errors, record the entitlement named by the tool. Do not add app groups or remove frameworks to bypass signing failures.

## Verify, update and uninstall

Open the app, check its version, play audio, lock the device and test system controls. Verify sign-in after reopening. Check one manual signing refresh before relying on background refresh.

Export a [backup](README.md#before-updating-or-uninstalling), then install the newer verified IPA through the same tool/account/ID. Deleting the app removes local data. Follow the tool's uninstall instructions, including removal through TrollStore when applicable.

If installation succeeds but startup fails, report the app version, OS, device and signing tool. For expiry, pairing or provisioning failures, use the tool's support guide and retain its error log.
