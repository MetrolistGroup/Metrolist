# Playback errors and timers

## Error policy

`Settings.autoSkipNextOnError` defaults to false. All players try bounded recovery first. A terminal failure pauses on the failed occurrence and keeps retry controls available, including for local or downloaded audio.

When enabled, automatic advance requires active playback intent, network availability and an eligible failure. Repeat-one, pause, offline state and network/timeout errors stop instead. `PlaybackFailureAutoSkip.kt` limits consecutive skips to three; 30 seconds of healthy media progress rearms the budget.

Room guests and Device Connect followers do not change the host queue after a local failure. Failed preloads and renewals preserve the current song. Android publishes a terminal media-session error only when playback stops so an allowed advance retains its notification.

Android and desktop begin bounded SABR recovery when the producer fails, while buffered audio can still play. Replacements warm up muted and follow the current position before handoff. A token-backed session that remains attestation pending after its first response fails early and excludes that profile through the same recovery policy.

On Android, an offload AudioTrack that stops advancing its playback head after entering offload sleep is switched to PCM after two seconds. The 12-second playback watchdog remains the fallback for other output stalls.

## Android track transitions

When crossfade is disabled, or same-album gapless is enabled for consecutive tracks on one album, Android adds the resolved next audio source to the active ExoPlayer playlist before the current track ends. ExoPlayer handles the boundary without replacing the player or its audio session. Per-stream metadata selects both normalization attenuation and boost for queued PCM. Player volume applies only user, sleep timer and crossfade gains. Offload requires gapless support and stays disabled when either queued track needs normalization.

After a native handover, Android removes finished playlist items before queue promotion. The active player holds only the current source and, while preloading, the next source.

Cancellation after a native handover commits the playing track's identity, resource, reporter and queue occurrence together. Both normal promotion and cancellation preserve current queue title and artist edits. If that occurrence is no longer the intended next item, Android restores the previous track.

Other crossfades keep separate players and their gain ramps. Same-album gapless transitions use the playlist path and do not ramp, even when crossfade is enabled. Video, live streams and tracks restored at a nonzero position keep the separate-player preload path. Queue edits, repeat-one and end-of-song sleep timers cancel the queued source.

On Android, `MusicService.seekTo` cancels an active crossfade and leaves an unstarted preload queued.

## Seek diagnostics

Export the playback log immediately after a jump. Android's 500-event buffer records `Android seek requested.` separately from `Android native position discontinuity.`. Compare source, player ID, queue request and playback instance with preparation and recovery events.

Lyrics taps include line indices, timestamps and offsets. Media-session seeks identify the command. Warm replacements remain muted until the outgoing player releases, while intentional crossfades keep their gain ramps.

Android keeps silent preloads out of system audio-effect sessions. A secondary player opens its effects session when overlap starts or when it becomes the primary player. Canceling a silent preload sends no effect-session broadcasts.

Seek-bar release requires a changed value and the same song and duration as the gesture. Track/duration changes cancel pending gestures. Unknown-duration and live tracks cannot use the shared scrubber.

## SponsorBlock

The shared observer sends a four-character SHA-256 prefix to the [SponsorBlock API](https://wiki.sponsor.ajay.app/w/API_Docs#GET_/api/skipSegments/:sha256HashPrefix) and matches video IDs locally. Invalid ranges and non-skip actions are ignored. Network failure does not interrupt playback. [SponsorBlock data](https://sponsor.ajay.app/) uses [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).

## Sleep timer

The shared dialog accepts one minute through 23 hours and 59 minutes. Saved defaults include duration, finish-current-song and fade. Reset uses those defaults or 15 minutes with both options off. Closing the dialog keeps an active timer running; canceling stops it and restores gain.

Finish current song arms an end-of-song stop when the countdown expires and cancels crossfade. Fade applies to the final song. Unknown duration keeps full gain until a duration or end event is available. Desktop uses a wall-clock countdown, including suspension time.

## Verification

Run affected [platform checks](checks.md#platform-checks). Check both error-setting values, repeat modes, pause, local audio, synchronized playback and retry. For timers, check cancellation, unknown duration, crossfade and background/suspend behavior on each affected platform.
