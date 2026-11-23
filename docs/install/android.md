# Install on Android

## Requirements and download

Use Android 7 or later, API 24. Download the universal APK through [the project channels](README.md#get-the-package). The release app ID is `com.metrolist.music`, so it updates the original 13.x app in place; debug uses `com.metrolist.music.debug`.

Metrolist is also listed on [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/MetrolistGroup/Metrolist/), [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.metrolist.music) and [OpenAPK](https://www.openapk.net/metrolist/com.metrolist.music/). The [nightly prerelease](https://github.com/MetrolistGroup/Metrolist/releases/tag/nightly) ships `Metrolist.apk` built from `main` and may be unstable.

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

The in-app updater, which the IzzyOnDroid build leaves out, installs through the Android package installer by default. In Settings > Updates, **Install updates with** can switch to root or [Shizuku](https://shizuku.rikka.app/). Both stream the verified APK into `pm install -i com.metrolist.music`, so no prompt appears, and choosing either runs `pm set-installer` to record Metrolist as its own installer. On Android 12 and newer the package installer also updates Metrolist without a prompt once it may install apps, using `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, unless another app has claimed update ownership on Android 14 and newer. **Install updates automatically** downloads each update found by the startup or hourly check and installs it once playback is paused or stopped. After a failed automatic install it waits for the next launch. It needs one of those prompt-free paths. Installing closes Metrolist, which does not reopen by itself.

Uninstalling removes private settings, library and downloads. Exported shared-storage files can remain. Preserve those files and backups before deleting data.

## Common problems

For parsing or installation failure, check the package checksum, Android requirement, app ID and signing key. For startup failure, record the exact build and device and use [crash recovery](../crash-recovery.md). Do not clear storage as a first step when a backup is still possible.
