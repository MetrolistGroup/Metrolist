# Arch package

The `metrolist-git` source recipe, its desktop file and its build check live in the repository's `aur/` directory. This directory holds no recipe.

The distribution generator copies this file and emits `aur/release.json` with the stable version, public release URLs and verified Linux AppImage SHA-256 hashes. A binary recipe can consume that metadata. The source recipe's `pkgver()` follows Git history, so stable asset updates must not replace it.

The generator does not publish to AUR. Generate `.SRCINFO` with `makepkg --printsrcinfo` in the recipe checkout before publishing.
