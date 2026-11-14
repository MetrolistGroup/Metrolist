# Android startup and playback binding

## Startup order

`App` installs crash handling before locale and normal startup. Pending fatal reports bypass normal initialization; see [crash recovery](crash-recovery.md). Each process initializes the Compose resource reader with its application context.

Backup restore finishes before infrastructure and database readers initialize. Keep legacy migration recovery and SQLite identity checks synchronous until a replacement preserves service binding. An onboarding flag cannot detect a database replaced by a legacy installation.

`App.infrastructureReady` permits platform/session objects after DI, locale, migration checks and notification channels. `App.startup.await()` also waits for liked songs, saved accounts and database-dependent setup. Controllers, browsing, downloads, widgets and activity content must wait for completion.

Saved account restoration runs on I/O. Apply its result only while the originating credentials and visitor data still match. Login, logout and account changes invalidate pending work.

Samsung's quick-panel "Play music" chooser sends `android.intent.action.MUSIC_PLAYER` in [decompiled SystemUI](https://github.com/488315/android_samsung_frameworks_base/blob/d7c13fe69a1ad46f8dff0254f1abd32596fef5cd/packages/SystemUI/src/com/android/systemui/media/PlayLastSongHelper.java#L109-L119). `MainActivity` accepts this action with `CATEGORY_DEFAULT` and opens the app without requesting playback.

## Service binding

Browsing creates `PlayerConnection` without `MusicService`. It restores paused metadata and a queue summary. The preview has no live occurrence keys; connect before exposing queue edits. Dismissal clears saved playback without starting the service.

Playback commands request one `BIND_AUTO_CREATE` connection and preserve their order until queue restoration finishes. Android Auto, Media3, room recovery and authenticated Device Connect peers can also require binding. A saved queue alone never starts playback.

Keep legacy browser connection authorization immediate. Those browsers can block the main thread waiting for a result; waiting on main-thread startup deadlocks. Modern connections retain their startup gate. Preserve pending widget and notification actions.

Read Listen Together guest-lock state without constructing its network manager. Create the manager for room screens, invitations or saved sessions. Position synchronization reads the player on demand rather than forwarding an idle UI clock.

## Background playback

Keep playback ownership in the service. Screen, activity and rendering visibility must not stop audio. Use the common player factory so primary, preloaded and replacement players retain noisy-audio handling.

Android Auto projection disconnection pauses local playback and invalidates pending automotive playback requests. Initial disconnected state and unavailable provider results do not pause. Cast and Device Connect remote playback retain their own ownership.

Audio-focus recovery belongs to the interrupted player, playback instance and queue request. It cannot resume a paused replacement. See [audio recovery](audio-processing.md#silence-and-recovery).

When the task is removed, `stopMusicOnTaskClear` stops playback immediately after its final checkpoint. Otherwise, an idle service stops after one minute without playback demand. Returning to the activity, resuming, preparing a track, an established Cast connection, a pending or active room, or a connected Device Connect peer cancels that shutdown. Queue restoration finishes before the lifetime observer starts. `PlayerConnection` releases its application binding when the service requests shutdown and binds again for the next command. Paused metadata remains available in the connection.

Crossfade gain updates suspend while playback is paused or suppressed and continue the same fade on resume. Progress sampling covers requested buffering for output recovery, but stops for a suppressed ready player. The separate Android video clock starts only for requested playback and ends when the player becomes idle or reaches EOF. Listen Together hosts publish paused state changes without a periodic position timer; guests wait for readiness changes and keep the three-attempt recovery limit. Device Connect clocks tick during playback and use local playback events and completed loads to publish changes while paused. Required connection keepalives and bounded reconnect attempts remain active for established sessions.

Paused network recovery waits for playback or connectivity events. A connection change can restore the stream while keeping it paused; a failed paused attempt waits for another event instead of retrying indefinitely. Advancing playback retains its stall deadline and retry backoff. The animated-artwork watchdog samples only while playback is requested and its surface is attached. Pausing or detaching resets its stall deadline and stops its timer.

Pausing these timers must not change crossfade durations, gain curves, animated-cover fallback transitions or reduced-motion rules.

## Background power checks

Idle work has no periodic timer:

- The AI translation shimmer runs only during translation with visible lyrics, a started lifecycle and a visible window. Reduced-motion and disabled-animation settings apply.
- Focus outlines invalidate drawing on focus and press events.
- Mini-player artwork progress interpolates only with visible, resumed UI and advancing playback. The wavy phase stops when its playback or visibility gate closes.
- Track artwork loading and prefetch cancel outside the resumed screen.
- Startup applies the saved volume once. App volume commands persist changes.
- System transport controls update commands and glyphs from session events, including below API 33.
- Storage audits and tracked-playlist reconciliation run at startup with freshness checks.
- Download workers drain requested work and stop their foreground service when no runnable downloads remain.
- Automatic caching uses playback targets and deferred captures. Network monitoring uses connectivity callbacks.

Media3 owns playback wake locks; native checks must confirm their release after pause and service shutdown. Playback CPU measurements do not establish idle behavior; measure each separately on the same device, settings, queue and network.

Use the installed package name, such as `com.metrolist.music`, in these commands:

```bash
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats com.metrolist.music > batterystats-before.txt
adb shell dumpsys activity services com.metrolist.music > services-before.txt
adb shell dumpsys power > power-before.txt
# Reproduce one scenario for at least 15 minutes, then collect the same snapshots.
adb shell dumpsys batterystats com.metrolist.music > batterystats-after.txt
adb shell dumpsys activity services com.metrolist.music > services-after.txt
adb shell dumpsys power > power-after.txt
adb bugreport background-power.zip
```

Measure cold browsing without playback, pause with the task retained, pause then remove the task, and remove the task while playing then pause through the notification. Repeat with stop-on-task-clear off and on. Also pause midway through a crossfade, load a video paused, finish a video, and background an active translation. Reopen and resume each case to check preserved artwork, volume and fade progress. Without a room, Cast connection, peer or external controller, the removed paused task should release MusicService after the one-minute grace period. A retained external binding may keep the service object alive.

Capture a Perfetto system trace with CPU scheduling, CPU frequency, process stats, power and Android view tracing, or the device's System Tracing app. Compare thread CPU time, wakeups, network traffic and wake-lock duration over the same interval. Paused crossfades must have no 16 ms gain updates, paused or finished video must have no 50 ms clock loop, and paused room/device sessions must have no periodic playback sampling. Disconnect the network during playback, pause, wait, then reconnect. Recovery must stay idle offline and restore the paused stream on the connection event. Paused or detached animated artwork must have no watchdog ticks. Repeat while playing to verify audio continuity, buffering recovery, offload and notification controls below and above API 33. Keep room keepalives and explicit downloads separate from the idle baseline. Backend checks do not establish battery savings or OEM service behavior.

## Deferred work

Keep credential decryption, file/font validation and large decoding off the main thread. Downloads restore on I/O before queue mutations. Successful storage and tracked-playlist reconciliation use their existing freshness intervals; failures do not advance them.

Cast discovery runs while the device picker is open and its activity is started. Stop discovery when hidden without disconnecting established playback. Cancel supported NSD resolutions and reject callbacks from an old discovery generation.

Preference writes update memory immediately and persist on one ordered worker. `Settings.flush()` waits for earlier writes and propagates persistence failures. Backup and restart use that boundary; crash handoff uses a bounded best-effort flush.

## Verification

Run Android [platform checks](checks.md#platform-checks). On a device, check cold browsing without service creation, playback before queue restoration finishes, process recreation, deep links, Android Auto browsing and projection disconnection, background playback and recovery startup. Use disposable data for backup and downgrade checks. Backend tests do not establish OS lifecycle behavior.

On Samsung One UI with no resumable media session, tap the quick-panel "Play music" button. Metrolist must appear once in the chooser and open when selected, both from a cold start and with the app running. Repeat after changing the app icon.
