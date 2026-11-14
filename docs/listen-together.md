# Listen Together

## Protocol

Coordinate schema changes upstream. Do not modify `metroproto` here. Keep these representations synchronized:

1. `.proto` definitions in `metroproto/`.
2. JVM generation in `listenTogetherProto`.
3. Shared models and validation in `shared/src/commonMain/kotlin/com/metrolist/shared/listentogether/network/Protocol.kt`.
4. JVM `MessageCodec.kt` and common `PortableMessageCodec.kt`.

Import shared network models rather than generated JVM types. Native targets use the hand-written portable codec. It rejects more than 16,384 fields per protobuf object. Room-entry state must have matching room identifiers.

`ProtocolFeatures.supported` advertises capabilities; the server returns the enabled subset. Older servers enable none. Preserve that path.

| Capability | Contract |
| --- | --- |
| `request_id` | Correlate responses. Log automatic-traffic errors without clearing rooms or showing user errors. Retry `sync_rate_limited` after a second; report `queue_too_large`. |
| `origin_tag` | Ignore echoed host edits that would revert newer state. |
| `host_sequence` | Prevent old observations from overriding a seek. |
| `drift_report` | Supply timeline correction. |
| `desync_canary` | Request full state for track/queue hash disagreement. |
| `server_restarting` | Retain playback and reconnect after the announced delay plus jitter without spending a failure attempt. |
| `replace_queue` | Replace complete queue state, including empty queues. |
| `track_revision` | Acknowledge track changes. |
| `suggestion_ack` | Track guest suggestion IDs and confirmation. |

Encode on `Dispatchers.Default` in send order, capturing negotiated compression. Decode sequentially off the caller dispatcher. Discard results after a connection-generation change before updating room or playback state.

## Queue and recovery

Retain the committed queue and station metadata until room audio preparation succeeds. Prepare remote queues paused. Guests resume after synchronization; host imports explicitly start playback. Failed or canceled loads preserve committed state. Recovery stays paused even if the host sends play. Empty sync snapshots cancel pending loads and pause without discarding the committed queue.

Read the player's queue snapshot and current occurrence for publication. Serialize server updates with host publication and preserve unpublished host edits. Preserve duplicate occurrences, canonical upcoming order and empty replacements. Consume legacy host queue acknowledgements once.

Publish host track changes when the player's current song changes. Keep periodic timeline updates. Guests reuse matching tracks from the player queue with the host's metadata before requesting metadata for an unfamiliar track, and still wait for buffer synchronization before playing.

Removing the final host track pauses guests and clears the upcoming queue. The server retains last-track metadata because `change_track` requires a track; clearing it needs an upstream action.

Pending joins can be canceled and resubmitted after reconnect. Keep approval rows until acknowledgement, and failed decisions until retry. With `suggestion_ack`, retry unacknowledged guest sends after authentication; coalesce repeated taps. `duplicate_suggestion` confirms an existing pending send. Leaving clears unsent suggestions.

Notify hosts of automatic approvals only after confirmation. Rejection, manual decisions, leaving or host-role loss discard pending automatic feedback. Guest volume and mute are local; Sync host volume controls whether host volume overrides them.

Leaving unlocks local playback. Retry after connection exhaustion resumes transport; Leave Room exits it. Reconcile disconnected queue/volume edits before republishing. Changing servers cancels pending requests and clears the old room/session rather than transferring it.

## Integration setting

Custom servers have an Edit server details action for a local name and maintainer. Editing these labels leaves the URL, connection and remote server metadata unchanged. Removing the server also removes its saved labels.

The master switch defaults on. Disabling it disconnects, cancels requests/retries and clears the saved room session. It blocks creation, joining, restore and invitation entry. Re-enabling does not rejoin. The Integrations setting remains available.

Settings and server management require the switch but not a username. Creating or joining requires a username. Guest preferences do not change the active host's moderation rules. On Android, guest-lock reads must not construct the manager; [playback binding](android-startup.md#service-binding) happens on demand.

## Invitation links

Room invitations use `https://metrolist.cc/listen?code=<code>` or `https://metrolist.cc/party?r=<code>`. Media share and copy actions use `https://music.youtube.com` through `ShareUrls`. Loopback and Workers preview hosts do not open media or room invitations.

Android and iOS validate eight-character room codes in HTTPS invitations and `metrolist://listen-together/<code>`. Android also accepts shared text. Active-room changes require confirmation; dismissing keeps the current room. Invitations prefill the join form.

Packaged Windows launches register `metrolist://` per user and forward URLs to an existing instance. Both `metrolist://listen?code=ABCDEFGH` and `metrolist://listen-together/ABCDEFGH` reach the invitation handler. Development runs do not register. Relaunch after moving a portable directory to update its path. Installer registration and uninstall cleanup need companion MetrolistManager support.

HTTPS handoff requires a website link to the custom scheme. iOS entitlements request `applinks:metrolist.cc`; delivery also requires the domain's Apple App Site Association document and a matching signing-team application identifier/provisioning profile. Independently re-signed IPAs are not automatically covered. Test cold and running-app delivery on native platforms.

## Verification

Run affected [platform checks](checks.md#platform-checks), including both codecs. Test negotiated and legacy capabilities, malformed messages, duplicate occurrences, canceled preparation, reconnect and server changes. Native checks need two clients for joins, approvals, host edits, guest locks and invitation delivery.
