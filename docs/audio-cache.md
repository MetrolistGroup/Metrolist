# Audio cache and downloads

## Ownership and publication

Playback captures, pre-cache and manual downloads share storage rules. Publish only validated complete audio. Keep temporary files separate from visible entries and replace metadata and files together. Cancellation, cache clear or disabling caching must invalidate pending publication.

Desktop Storage groups the music-video mapping cache with the other cache rows. This resolver cache holds song-to-video lookup records, not media files, so it shows a record count and a clear action rather than a byte limit.

Readers hold leases. Clearing a cache removes future lookup without deleting bytes still used by an attached player. Release the final owner before reclaiming its file. Failed conversion, tagging or replacement retains the existing entry for retry.

## Pre-cache

Pre-cache resolves upcoming tracks without taking playback ownership. Preserve queue occurrence identity, cancellation and the configured storage limit. A track change or reordered queue must not publish an obsolete next-track result.

## Downloads

Restore download queues before accepting mutations or draining work. Retain completed files when a later transfer or metadata write fails. Preserve existing destinations during replacement and migration. Reconcile missing files without replacing complete remote playlist membership with an offline snapshot.

Android and desktop downloaded collections can filter by a library playlist before search and sorting. Selection, playback and removal operate on visible results. Playlist download state uses stored membership, including entries the user has not opened in the current session.

Remote playlist download refreshes store every page of unfiltered membership. Offline playlist pages read those stored entries. Failed and offline refreshes keep the previous membership.

## Formats

Android shared-folder exports remux WebM/Opus to tagged Ogg Opus and AAC to tagged M4A without re-encoding. Conversion or tagging failure must not publish raw source files. Private storage keeps the original representation. Shared-cache migrations retain originals until replacement files and format metadata commit.

SABR downloads and incomplete playback captures use validated transfers. Tokenless downloads renew sessions after 45 seconds of media and resume at a segment boundary. Renewal must preserve the encoded representation. Reject gaps, changed bytes and incomplete transfers; see [SABR constraints](innertube.md#sabr).

iOS capture validates URLs, redirects, response ranges and lengths. Captures retain session ownership through preloads and crossfades. Cache clear prevents publication while allowing attached readers to finish. Startup removes abandoned session files.

For fragmented AAC with an empty sample table, iOS clears container durations that AVFoundation would count twice. Files with samples in `moov` remain unchanged. Saved catalog duration takes precedence over an inflated file timeline. See [iOS Opus](ios-opus.md) for remux limits and offline priority.

## Animated artwork

Desktop stores media in `animated_artwork_cache` under its data directory and uses the shared disk-cache wrapper. `animatedArtworkCacheSize` defaults to 2048 MiB; zero disables retention.

Finite MP4/WebM files and supported finite HLS playlists have a 32 MiB per-file limit and a 15-second transfer deadline. Live, encrypted, discontinuous and cross-origin playlists keep native URL playback. Active snapshots survive cache clearing until their last reader closes. Session files remain temporary when retention is disabled.

## Verification

Run affected [platform checks](checks.md#platform-checks). Use disposable storage to check cold-cache completion, seeks, crossfades, cache clear during capture, low disk space, failed migration and each download destination. On iOS, check that throttled capture does not block unrelated playback or republish after clear.
