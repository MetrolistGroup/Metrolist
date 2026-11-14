# Arch package

Two AUR packages exist. [`metrolist-bin`](https://aur.archlinux.org/packages/metrolist-bin) repackages the release AppImages for x86_64 and aarch64; its recipe is in `packaging/aur/metrolist-bin/`. [`metrolist-git`](https://aur.archlinux.org/packages/metrolist-git) builds `main` from source; its recipe, desktop file and build check are in the repository's `aur/` directory.

The distribution generator copies this file and emits `aur/release.json` with the stable version, public release URLs and verified Linux AppImage SHA-256 hashes. Use those values for `pkgver` and `sha256sums_*` in `metrolist-bin` on each release, and reset `pkgrel` to 1. The source recipe's `pkgver()` follows `appVersion` and Git history, so stable asset updates must not replace it.

Nothing publishes to AUR automatically. Regenerate `.SRCINFO` with `makepkg --printsrcinfo`, then copy `PKGBUILD` and `.SRCINFO` (plus `metrolist.desktop` for `metrolist-git`) into the AUR checkout and push. Generate the `metrolist-git` `.SRCINFO` outside this repository, because inside it the recipe switches to the local tree.
