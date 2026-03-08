# Crash recovery

## Database downgrades

The version guard rejects a successfully read newer database version. Missing, corrupt, locked or unreadable databases continue into normal initialization/recovery. The probe never repairs or replaces a database.

Android legacy migration recovery can preserve the unreadable original under `files/legacy-library-recovery/` before continuing with an empty library. Acknowledging a downgrade report cannot open an incompatible database; a compatible app is required.

## Reports

Crash storage is independent of Room and settings. Android uses `files/crashes`, desktop uses the XDG data directory's `metrolist/crashes`, and iOS uses Application Support's `metrolist/crashes`.

Reports record timestamp, app version, platform/device and stack trace. The store keeps the newest 50 and limits stack traces to 256 KiB of characters. It redacts credentials, authorization headers, cookie tails and signed URL queries before atomic publication. Invalid records are ignored. Files stay private until explicit sharing or upload.

Crash UI and export labels use base English resources so maintainers can read reports across locales. Keep the [non-translatable crash keys](strings.md#translation-mechanics) separate from generic translated actions.

## Android handoff

`App.attachBaseContext` installs handling before locale and startup. A pending fatal report bypasses normal initialization. Startup failures save the first report, stop remaining setup and cancel waiting database readers.

The launcher waits for focus and opens private `CrashActivity` in `:crash`. Recovery acknowledges the report before rendering and terminates the damaged main process. The separate public logs entry forwards no caller extras. Recovery avoids ordinary application initialization and can check/install updates independently.

Background uncaught failures may be unable to open an activity immediately. The next launcher start handles the pending report. The recovery process does not install another recovery handler.

## Desktop handoff

The default handler covers uncaught thread, coroutine and AWT errors. It saves the report and offers Reload or Let it crash in Swing. Reload acknowledges and relaunches normally. Let it crash, closing or Escape keeps the report pending and launches a fresh recovery JVM with `--crash-report <id>`.

Headless operation or prompt failure takes the recovery path. The damaged process halts without Compose/database cleanup. The recovery window does not initialize Compose, Room, Koin or settings. Restart strips recovery arguments and preserves the launcher path/JVM options needed for the installation.

## Settings recovery

Desktop validates main and pending properties snapshots. It preserves corrupt bytes in unique `.corrupt-<timestamp>-<unique>.bak` files, then uses a valid pending snapshot, a valid main file or defaults. It never accepts partial parser output.

If preserving or reading an original fails, keep it and block replacement for that process. Skip migrations that would overwrite an unpreserved file. Encrypted backups retain encrypted bytes. POSIX files use owner-only permissions; Windows retains profile access controls.

Settings writers publish complete snapshots through synced temporary files and atomic replacement where supported. Preserve pending writes on failure. `Properties.store(OutputStream)` and `load(InputStream)` handle Windows paths and Unicode; do not repair them with raw-text substitutions.

Recovery logs file names and exception types, never keys, values, decrypted data or exception messages. Backups can contain credentials and must stay private. See [desktop credentials](desktop.md#credentials-and-settings-files).

## iOS limits

Kotlin and Objective-C exception handlers save a redacted report and pending marker. On the next launch, normal initialization waits until the report list closes. Closing acknowledges it; clearing logs does not delete the library.

iOS cannot safely present UI in a terminating process or restart itself. Native signals, Swift traps, watchdog/OOM termination and failures before entry require OS/Sentry diagnostics. In-app updating is unavailable.

## Verification

Run affected [platform checks](checks.md#platform-checks). Use disposable profiles, preserving database files and sidecars. Check startup failure, pending-report restart, downgrade rejection, corrupt settings, failed backup creation and export redaction. Verify native process handoff and Windows file locking on their platforms; backend tests cannot establish those behaviors.
