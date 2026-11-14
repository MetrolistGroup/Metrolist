# Install on macOS

## Download

Choose the ARM64 DMG for Apple Silicon or x64 for Intel from [the download channels](README.md#get-the-package). Packaging runs on macOS 15; compatibility with older systems needs platform verification. The package includes Java.

## Install and verify

1. [Verify the checksum](README.md#verify-a-download).
2. Open the DMG and drag Metrolist to Applications.
3. Eject the image and open Metrolist.
4. If macOS blocks the unsigned app, use its per-app Open Anyway option only after verifying the download. Follow [Apple's instructions](https://support.apple.com/en-us/102445).
5. Check playback, login and credential restoration after restart.

Packages are unsigned and unnotarized. Do not disable Gatekeeper globally.

## Update and uninstall

Back up and quit before replacing the app in Applications. Deleting the app leaves profile data, Keychain credentials and music outside it. Sign out before removing credentials and preserve backups before clearing data.

## Common problems

For architecture or launch failure, confirm the DMG matches the processor and collect the [desktop log](../desktop.md#logs-and-profiling). For credential problems, check [Keychain recovery](../desktop.md#credentials-and-settings-files) before deleting files.
