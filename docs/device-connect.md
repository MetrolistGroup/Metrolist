# Device Connect

## Availability

Device Connect uses authenticated encrypted JSON under `shared/src/commonMain/kotlin/com/metrolist/shared/deviceconnect/`. It does not use Listen Together protobufs. The audible target owns playback; controllers send commands and display state.

Android and desktop expose it in Integrations and the player device picker. It defaults off. Disabling stops discovery, listeners, sessions and retries without deleting paired devices. iOS does not expose it because its cryptography is unimplemented.

Connection and pairing changes update the open settings page. Activate a paired device's row to forget it, including with a TV remote. Choose the playback target in the player device picker; pairing alone does not move audio.

A failed start retains the enable preference and offers retry. Discovery failures remain distinct from empty results. Retries back off from 30 seconds to 10 minutes. Desktop uses the system hostname unless a custom device name is set.

## Direct connections

Connect by IP address accepts the listener's current ephemeral port with `IPv4:port` or `[IPv6]:port`. It supports private IPv4, shared IPv4 space `100.64.0.0/10` and local IPv6. Public addresses and hostnames are blocked. Multicast discovery is unnecessary, but the firewall/VPN must permit peer traffic. Re-enter the endpoint after losing a manual connection.

The same identity handshake, pairing proofs and encryption apply. A default playback device is used only when its authenticated connection is available and there is no active session. Unavailable defaults and local-file queues play locally. Cast and room ownership take precedence. Discovery alone never moves playback.

## Trust and playback ownership

Pairing is mutual and non-transitive. Each new pair needs approval, even within one account. Code-capable peers compare and approve the same code on both devices within a monotonic five-minute deadline. The account fingerprint is a discovery filter, not authorization.

Before transferring to a new target, release existing controllers. They retain paused queues and must connect directly to that target. Release messages apply only to their matching session.

Controllers stop local audio, video and preloads. Only the audible target records history/scrobbles and publishes external presence. Received state uses `setControllerPlayback` without loading media. Null exits controller mode paused and is a no-op on the target. Disconnect and rejected pull-back cannot start local audio.

Unconfirmed transfer failure restores the saved position without counting waiting time as playback. Keep speed and pitch consistent during transfer. Controllers retain their own local volume for later local playback; transfer preserves the new target's volume. Finite volume commands range from 0 to 1 and never reload audio.

## Compatibility and queues

Peers need playback version 4. Version 3 cannot join playback sessions. Authenticated optional capabilities `supportsPlaybackClock` and `supportsPlaybackPitch` default false. Never infer them from unauthenticated state.

Send `PITCH` only to a capable target. Snapshots and scalar updates may carry pitch from 0.5 to 2; absence preserves the receiver's pitch. Speed changes preserve pitch, and pitch does not affect the clock. Older peers ignore optional JSON fields without a version bump.

Capable peers can send scalar `UPDATE` against an accepted `queueRevision` and nonempty `queueStateId`. Cache misses request full `STATE`. New queues still require occurrence, shareability, scalar and transport-size validation. Preserve duplicate occurrences and revision checks for indexed commands.

Legacy peers receive full state on track/scrobble-session changes and one-second progress. Clock-capable peers use immediate changes and less frequent corrections. Clocks honor speed, pause, buffering and duration. Retain scrobble sessions for transfer.

`REORDER` sends a revision-checked permutation containing every queue index once. The target preserves the current occurrence, position and shuffle identity even if playback advances in transit. Android media-session bulk edits are rejected while following another device.

Radio replacement can set `preserveCurrentTrack`. A matching ready seed retains its instance and position; otherwise it starts at zero. Older targets ignore the flag and may reload using the supplied position. Queue replacement requires complete snapshots. Partial queues need negotiated continuation ownership, cancellation and acknowledgement before use.

## Transport and account identity

Admission allows two unauthenticated incoming connections per source and four total. Preserve capacity for authenticated/outgoing peers. Discovery retains alternate endpoints and does not replace a live authenticated endpoint with advertisements.

Outgoing queues are bounded. Coalesce only adjacent redundant clock samples; preserve state, seek and command order. Assign encrypted sequence numbers and chunk under the send mutex. Disconnect stalled or overloaded peers. Local queue acceptance is not proof of remote delivery.

Normalize personal sync IDs `||account-id` to `account-id` and channel IDs `channel-id||account-id` to `channel-id`. Use one helper for login and pairing. Cache identity by exact cookie and data-sync ID; invalidate on logout or credential changes.

Unresolved account IDs cannot pair. Exchange the `resolved` flag without an account proof so peers can show identity recovery instead of a false account mismatch. Obtain fresh account identity by signing in and selecting the same channel. Never guess a channel or allow unresolved identities. Genuine mismatches stay blocked.

## Verification

Run affected [platform checks](checks.md#platform-checks), including Device Connect protocol, crypto, admission, outbox, chunk and controller tests. Native checks need two clients for mutual approval, reconnect, direct IP, transfer, pull-back, clock correction and queue reordering with duplicate songs. iOS stubs are not a working peer implementation.
