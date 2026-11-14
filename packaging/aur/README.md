# Arch package

Metrolist publishes no AUR packages itself. Arch users get the community-maintained [`metrolist-desktop-bin`](https://aur.archlinux.org/packages/metrolist-desktop-bin), which wraps the x86_64 release AppImage. This directory keeps recipes a maintainer can adopt: `packaging/aur/metrolist-bin/` repackages the release AppImages for x86_64 and aarch64, and the repository's `aur/` directory holds the `metrolist-git` source recipe, desktop file and build check.

The distribution generator copies this file and emits `aur/release.json` with the stable version, public release URLs and verified Linux AppImage SHA-256 hashes. Use those values for `pkgver` and `sha256sums_*` in `metrolist-bin` on each release, and reset `pkgrel` to 1. The source recipe's `pkgver()` follows `appVersion` and Git history, so stable asset updates must not replace it.

Nothing publishes to AUR automatically. Regenerate `.SRCINFO` with `makepkg --printsrcinfo`, then copy `PKGBUILD` and `.SRCINFO` (plus `metrolist.desktop` for `metrolist-git`) into the AUR checkout and push. Generate the `metrolist-git` `.SRCINFO` outside this repository, because inside it the recipe switches to the local tree.
