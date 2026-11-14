# Install on Android

## Requirements and download

Use Android 7 or later, API 24. Download the universal APK through [the project channels](README.md#get-the-package). The release app ID is `com.metrolist.music`, so it updates the original 13.x app in place; debug uses `com.metrolist.music.debug`.

## Install and verify

1. [Verify the APK checksum](README.md#verify-a-download).
2. Open the APK and allow installation from that browser or file manager when Android prompts.
3. Install, then remove that installation permission if no longer needed.
4. Complete onboarding and check the version in settings.
5. Play audio, lock the screen and check system controls. If signing in, verify the account after reopening.

## Android Auto

Complete onboarding first; playback services remain disabled until it finishes. Sideloaded media apps may also need Android Auto's developer **Unknown sources** setting, separate from Android's APK installation permission. Follow [Google's car-app testing instructions](https://developer.android.com/training/cars/testing#unknown-sources), then check Android Auto's Customize launcher list.

If the app is missing, report the phone/OS, Android Auto and app versions, package source and launcher-list state. If it appears but cannot browse, collect logs and see [service binding](../android-startup.md#service-binding).

## Update and uninstall

[Export a backup](README.md#before-updating-or-uninstalling). Install an APK with the same app ID and signing key over the existing app. Debug and release builds do not update each other.

Uninstalling removes private settings, library and downloads. Exported shared-storage files can remain. Preserve those files and backups before deleting data.

## Common problems

For parsing or installation failure, check the package checksum, Android requirement, app ID and signing key. For startup failure, record the exact build and device and use [crash recovery](../crash-recovery.md). Do not clear storage as a first step when a backup is still possible.
