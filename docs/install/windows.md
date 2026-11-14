# Install on Windows

## Requirements and download

Use Windows 10 or later on x64. Choose the stable installer when available, or the portable ZIP, through [the download channels](README.md#get-the-package). The package includes Java. Login requires Microsoft Edge WebView2 Runtime.

## Install and verify

1. [Verify the checksum](README.md#verify-a-download).
2. Run the installer, or extract the complete portable ZIP to a writable directory.
3. Launch Metrolist and check playback and login.
4. Restart and confirm that saved credentials return.

Keep the extracted runtime and helper files with the executable. A portable ZIP does not register an installed application or Start Menu entry.

## Update and uninstall

Back up first. Registered installations can use the stable installer update in settings. Portable installations replace their extracted directory after quitting. See [desktop updates](../desktop.md#updates-and-restore).

Uninstall a registered copy through Windows Settings, Apps. For portable copies, delete the extracted directory and shortcuts. Profile data and music outside it can remain. Sign out first if removing saved credentials.

## Common problems

Confirm the source and checksum before allowing a blocked download. Do not disable Windows security globally. For failed login, check WebView2 Runtime and the bundled helper. Include the [desktop log](../desktop.md#logs-and-profiling) in a failure report.
