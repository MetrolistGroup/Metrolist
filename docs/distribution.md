# Distribution packaging

## Generate recipes

`release.yml` generates recipes after stable publication. The asset names and checksums come from the published release, not a latest-version placeholder. Run locally with an authenticated GitHub CLI and an existing stable `tag`:

```sh
mkdir -p build
gh release view "$tag" --repo MetrolistGroup/Metrolist \
  --json tagName,isDraft,isPrerelease,url,publishedAt,body,assets > build/release.json
gh release download "$tag" --repo MetrolistGroup/Metrolist \
  --dir build/distribution-assets --pattern SHA256SUMS \
  --pattern 'metrolist-installer-windows-*.exe' \
  --pattern 'metrolist-desktop-linux-*.AppImage' \
  --pattern 'metrolist-desktop-macos-*.dmg'
python3 scripts/generate_distribution.py \
  --assets build/distribution-assets --release build/release.json \
  --output build/distribution
```

The workflow keeps `distribution-recipes` for 30 days. Cleanup removes only intermediate `release-*` artifacts. See [release packages](install/README.md#get-the-package) for filenames.

## WinGet

`packaging/winget/` defines version, locale and installer manifests under `manifests/m/MetrolistGroup/Metrolist/VERSION/`. WinGet uses the release-bound MetrolistManager executable, not the portable ZIP. Its silent options and per-user uninstall identity must match that pinned manager.

Run `winget validate --manifest PATH` on Windows, then test clean install, upgrade and uninstall before submission. Do not request elevation or purge user data.

After each stable release, the `winget` job in `release.yml` runs [winget-releaser](https://github.com/vedantmgoyal9/winget-releaser), which copies the previous version's manifests from `microsoft/winget-pkgs`, points them at the new installer and opens the upstream PR from the `MetrolistGroup/winget-pkgs` fork. It needs the `WINGET_TOKEN` secret, a classic token with `public_repo` and `workflow` scopes, and skips without it. `fork-user: MetrolistGroup` points it at the org fork. Keep these templates in step with the published manifests, because the generator still renders them for review.

## Homebrew

[MetrolistGroup/homebrew-tap](https://github.com/MetrolistGroup/homebrew-tap) publishes the `metrolist` cask and formula for macOS and Linux. The cask installs the DMG on macOS and the AppImage on Linux. The formula adds a `metrolist` command. On Linux it unpacks the release AppImage, and on macOS it installs the app from the DMG. Asset names and `SHA256SUMS` entries for the DMGs and AppImages must stay stable for it. `packaging/homebrew/metrolist.rb.in` remains the generator's cask template; keep it in step with the tap. Preserve Gatekeeper and user data.

Nothing polls for releases. After publication, the `homebrew` job in `release.yml` calls the tap's reusable `update.yml`, which points both at the new release using its `SHA256SUMS` and pushes with a write deploy key, passed in as the `HOMEBREW_TAP_DEPLOY_KEY` secret. The push starts the tap's `test.yml`, which runs `brew style`, `brew audit` and an install of each on macOS and Linux. The tap stores the same key as `DEPLOY_KEY`, so you can rerun `update.yml` there by hand.

Run `brew style --cask PATH` and `brew audit --cask --online TOKEN` in the tap. Test both architectures and follow [unsigned-app installation](install/macos.md#install-and-verify).

## Scoop

[MetrolistGroup/scoop-bucket](https://github.com/MetrolistGroup/scoop-bucket) publishes `bucket/metrolist.json` for Windows x64 and ARM64. It installs the portable ZIPs, `Metrolist-Desktop-windows.zip` and `metrolist-desktop-windows-arm64-VERSION.zip`, from the release tag and adds a Start Menu shortcut. Keep those asset names and `SHA256SUMS` entries stable.

After publication, the `scoop` job in `release.yml` calls the bucket's reusable `update.yml`. It runs Scoop's `checkver` against the new release, reads hashes from `SHA256SUMS`, installs the manifest on a Windows runner and pushes only after the install works, using the write deploy key passed in as `SCOOP_BUCKET_DEPLOY_KEY`. The bucket stores the same key as `DEPLOY_KEY` for manual reruns.

## Arch Linux

The source recipe and verification live in `aur/`. The generator writes `aur/release.json` with binary asset URLs and hashes for a binary recipe. It does not update `pkgver()` or publish to AUR. See the [Arch package notes](../packaging/aur/README.md).

## Nix

`flake.nix` uses `appimageTools` for Linux x86_64/aarch64 and pins nixpkgs. On aarch64 and x86_64 macOS, `packaging/nix/darwin.nix` unpacks the release DMG into `Applications/Metrolist.app` and adds a `bin/metrolist` launcher. It copies the bundle unchanged, so its ad-hoc signature stays valid. Generated output includes `nix/packaging/release.json` with fixed-output hashes for the AppImages and DMGs. After each stable release, the `nix` job in `release.yml` copies the generated `packaging/release.json` to main and pushes it with the workflow token, so `nix run github:MetrolistGroup/Metrolist` follows the new release with no polling. The FHS sandbox uses the host's ALSA configuration. Do not add `alsa-plugins` to `extraPkgs`, because its `/etc/alsa` hides the host's PipeWire configuration.

The sandbox includes `glib-networking` and adds its GIO modules to `GIO_EXTRA_MODULES` for WebKitGTK TLS. It preserves any existing module paths.

Nix and Arch install `Metrolist.desktop`, matching the AppImage and native AppImage desktop files, `StartupWMClass=Metrolist` and the Linux window's `Metrolist` app ID.

```sh
nix build path:./build/distribution/nix
nix run path:./build/distribution/nix
```

The root flake reads the committed `packaging/release.json`, so `nix run github:MetrolistGroup/Metrolist` installs the latest stable release. After each stable release, copy the generated `release.json` to `packaging/release.json` and commit it. Missing metadata fails with generation instructions rather than a fake hash. Verify login, Secret Service, audio and desktop integration on both Linux architectures, and launch, login and Keychain restoration on both macOS architectures.

## Flatpak

Metrolist is distributed as a Flatpak only through [flatpark](https://github.com/flatpark/flatpark), which repackages the release AppImage as `com.metrolist.desktop` and diffs its permissions against [our source manifest](../flatpak/com.metrolist.desktop.yaml). Keep that manifest's path, app ID and permissions current. Metrolist is not submitted to Flathub.

The `flatpark` job in `release.yml` runs [flatpark/publish-action](https://github.com/flatpark/publish-action) after publication, so flatpark picks up the release within minutes instead of at its twice-daily sweep. It needs no secret.

The source manifest builds the committed `main` branch of the local checkout with Gradle and network access (`make flatpak-bundle`). Its Temurin 25 JDK stays unstripped because jlink rejects modified JDK libraries. It uses GNOME 51, matching flatpark.

Permissions cover Wayland, X11, IPC, DRI, PulseAudio, network, Secret Service, the app's MPRIS name, the tray watcher and tray item name (`org.kde.StatusNotifierItem-2-1`, the app runs as PID 2 in the sandbox), Downloads and Music. Both JVM and GraalVM native-image builds use the Wayland socket automatically through Nucleus Tao when the X11 socket is disabled. The manifest grants neither the whole home directory nor the session bus. Inside the sandbox the Wayland scale probes cannot reach the compositor and fall back to environment variables, and the download folder chooser sees only Downloads and Music. Omarchy detection requires its OS identity or version tool to be visible inside the sandbox; `OMARCHY_PATH` alone does not trigger rejection. Check login, credentials, local files, downloads, audio, tray, MPRIS and GPU rendering in the sandbox.

## Optional external proposals

Generation needs no extra secret. External proposals need `PACKAGING_PR_TOKEN` plus the destination repository variable as `owner/repo`:

| Variable | Destination |
| --- | --- |
| `NIX_REPOSITORY` | Generated flake |

The token needs Contents/Pull requests write permission and source visibility access. The script refuses writes while the source repository is private. No AUR publishing token is consumed.

`scripts/propose_distribution.py` creates or updates `build/metrolist-VERSION` and opens a draft PR against the destination's default branch. It never force-pushes or merges.

## Verification

```sh
python3 scripts/test_distribution.py
python3 scripts/test_build_appimage.py
python3 .github/tests/test_release_workflows.py
python3 .github/tests/test_repository_checks.py
bash -n scripts/build_appimage.sh
shellcheck scripts/build_appimage.sh
actionlint .github/workflows/ci.yml .github/workflows/release.yml .github/workflows/verify-repository-checks.yml
```

`scripts/build_appimage.sh` accepts a `GRADLE` executable override and rejects a successful build without a nonempty AppImage.
