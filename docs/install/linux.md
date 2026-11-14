# Install on Linux

## Requirements and download

Release AppImages target x64 and ARM64 on glibc-based distributions. The packaging workflow builds on Ubuntu 24.04; that does not establish compatibility with every older distribution. Packages include Java and FFmpeg.

Choose x64 for `uname -m` output `x86_64`, or ARM64 for `aarch64`. Get the matching package through [the download channels](README.md#get-the-package).

Use X11 or XWayland. Login needs GTK3/WebKitGTK 4.1. Saved credentials need Secret Service, libsecret, `secret-tool` and `dbus-send`. Audio needs ALSA integration with your audio server. See [Linux setup](../desktop.md#linux-login) and [audio setup](../desktop.md#linux-audio-setup). Startup rejects Omarchy.

## Install and verify

1. [Verify the checksum](README.md#verify-a-download).
2. Move the AppImage to a directory you own and keep its filename for the commands below.
3. Mark it executable and run it:

```sh
chmod u+x -- ./Metrolist.AppImage
./Metrolist.AppImage
```

Substitute the downloaded filename. Check playback, sign-in and retained credentials after restart.

## Update and uninstall

Back up, quit, then replace the AppImage with a verified download of the same architecture. Deleting an AppImage leaves profile data and music outside it. Sign out first to remove saved sessions, and preserve backups before clearing data.

## Common problems

For silent playback, check [ALSA routing](../desktop.md#linux-audio-setup). For blank login, check WebKitGTK and XWayland. For forgotten sign-in, check the unlocked Secret Service collection and [credential recovery](../desktop.md#credentials-and-settings-files). Use the [application log](../desktop.md#logs-and-profiling) to report remaining failures.
