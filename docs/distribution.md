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

## Homebrew

`packaging/homebrew/metrolist.rb.in` selects a checksummed DMG by architecture and installs `Metrolist.app`. Output belongs at `Casks/metrolist.rb` in the configured tap. Preserve Gatekeeper and user data.

Run `brew style --cask PATH` and `brew audit --cask --online TOKEN` in the tap. Test both architectures and follow [unsigned-app installation](install/macos.md#install-and-verify).

## Arch Linux

The source recipe and verification live in `aur/`. The generator writes `aur/release.json` with binary asset URLs and hashes for a binary recipe. It does not update `pkgver()` or publish to AUR. See the [Arch package notes](../packaging/aur/README.md).

## Nix

`flake.nix` uses `appimageTools` for Linux x86_64/aarch64 and pins nixpkgs. Generated output includes `nix/packaging/release.json` with fixed-output hashes.

```sh
nix build path:./build/distribution/nix
nix run path:./build/distribution/nix
```

To use the root flake, copy generated `release.json` to `packaging/release.json`. Missing metadata fails with generation instructions rather than a fake hash. Verify login, Secret Service, audio and desktop integration on both architectures.

## Flatpak and Flathub

The [local source manifest](../flatpak/com.metrolist.desktop.yaml) builds the checkout with Gradle and network access. The [release template](../packaging/flatpak/com.metrolist.desktop.json.in) extracts checksum-pinned AppImages without Gradle or build-time network. Read each manifest's runtime pin; they are separate build paths.

After generation:

```sh
flatpak-builder --show-manifest build/distribution/flatpak/com.metrolist.desktop.json
flatpak-builder-lint manifest build/distribution/flatpak/com.metrolist.desktop.json
appstreamcli validate --no-net build/distribution/flatpak/com.metrolist.desktop.metainfo.xml
desktop-file-validate build/distribution/flatpak/com.metrolist.desktop.desktop
flatpak-builder --user --install-deps-from=flathub --force-clean \
  build/flatpak-release build/distribution/flatpak/com.metrolist.desktop.json
```

Permissions cover X11, IPC, DRI, PulseAudio, network, Secret Service, the app's MPRIS name, Downloads and Music. Neither manifest grants the whole home directory or session bus. Check login, credentials, local files, downloads, audio and GPU rendering in the sandbox.

Metainfo uses release date/notes. `packaging/flatpak/screenshots.json` accepts objects with public HTTPS `url` and `caption`. The first image is default. An empty list does not generate substitute screenshots.

Flathub submission needs public source/assets, desktop screenshots, ID ownership confirmation and a complete offline source build, or an approved binary-build exception. The local network-enabled manifest does not satisfy the [source-build requirement](https://docs.flathub.org/docs/for-app-authors/requirements#building-from-source). Do not change the installed app ID without a data migration plan.

## Optional external proposals

Generation needs no extra secret. External proposals need `PACKAGING_PR_TOKEN` plus the destination repository variable as `owner/repo`:

| Variable | Destination |
| --- | --- |
| `WINGET_REPOSITORY` | WinGet staging repository or fork |
| `HOMEBREW_REPOSITORY` | Homebrew tap |
| `NIX_REPOSITORY` | Generated flake |
| `FLATPAK_REPOSITORY` | Flatpak packaging |

The token needs Contents/Pull requests write permission and source visibility access. The script refuses writes while the source repository is private. Flatpak also requires screenshots and `FLATPAK_BINARY_EXCEPTION=true` after explicit Flathub approval. No AUR publishing token is consumed.

`scripts/propose_distribution.py` creates or updates `build/metrolist-VERSION` and opens a draft PR against the destination's default branch. It never force-pushes or merges. WinGet staging PRs need a separate upstream submission.

## Verification

```sh
python3 scripts/test_distribution.py
python3 scripts/test_build_appimage.py
python3 .github/tests/test_release_workflows.py
python3 .github/tests/test_repository_checks.py
bash -n scripts/build_appimage.sh
shellcheck scripts/build_appimage.sh packaging/flatpak/com.metrolist.desktop
actionlint .github/workflows/release.yml .github/workflows/verify-repository-checks.yml
```

`scripts/build_appimage.sh` accepts a `GRADLE` executable override and rejects a successful build without a nonempty AppImage.
