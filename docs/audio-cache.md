# Audio cache and downloads

## Ownership and publication

Playback captures, pre-cache and manual downloads share storage rules. Publish only validated complete audio. Keep temporary files separate from visible entries and replace metadata and files together. Cancellation, cache clear or disabling caching must invalidate pending publication.

Desktop Storage groups the music-video mapping cache with the other cache rows. This resolver cache holds song-to-video lookup records, not media files, so it shows a record count and a clear action rather than a byte limit.

Readers hold leases. Clearing a cache removes future lookup without deleting bytes still used by an attached player. Release the final owner before reclaiming its file. Failed conversion, tagging or replacement retains the existing entry for retry.

## Pre-cache

Pre-cache resolves upcoming tracks without taking playback ownership. Preserve queue occurrence identity, cancellation and the configured storage limit. A track change or reordered queue must not publish an obsolete next-track result.

## Downloads

Restore download queues before accepting mutations or draining work. Retain completed files when a later transfer or metadata write fails. Preserve existing destinations during replacement and migration. Reconcile missing files without replacing complete remote playlist membership with an offline snapshot.

Android song-mode playback, preloading and returning from video mode keep the selected recording when its downloaded audio is available. Streamed video audio in the playback cache can still resolve a studio counterpart online, with the cache retained as a fallback.

Android and desktop downloaded collections can filter by a library playlist before search and sorting. Selection, playback and removal operate on visible results. Playlist download state uses stored membership, including entries the user has not opened in the current session.

Remote playlist download refreshes store every page of unfiltered membership. Offline playlist pages read those stored entries. Failed and offline refreshes keep the previous membership.

## Android v13 recovery

v13.7.0 stored downloads in `files/download` with no eviction and streamed audio in `files/exoplayer` with the configured LRU limit. Both caches used `StandaloneDatabaseProvider` and `databases/exoplayer_internal.db`. `MediaItemExt` and `DownloadUtil` used the song ID as the custom cache key. The download index can also map a custom key back to its request ID. Recovery uses that mapping rather than guessing IDs from suffixes or URLs.

Each cache has a hexadecimal `<uid>.uid` file. Its `ExoPlayerCacheIndex<uid>` table maps numeric content IDs to keys and serialized content metadata, including `exo_len`. Spans live in numbered subdirectories as `<content-id>.<position>.<timestamp>.v3.exo`. `ExoPlayerCacheFileMetadata<uid>` records each span's name, length and last-touch time. The shared `ExoPlayerDownloads` table records request IDs, URIs, custom cache keys, state, start/update times, content length, downloaded bytes, progress and failure/stop reasons. Its states are queued `0`, stopped `1`, downloading `2`, completed `3`, failed `4`, removing `5` and restarting `7`.

v14 lists and offline lookup read Room's `cached_audio` table, not the old cache directories or the song flags alone. The original importer only opened `files/download`. It removed entries even when it skipped them for an unknown length, incomplete coverage or a missing Room song. It did not consult the download index, use Room format lengths or import `files/exoplayer`. It required a full extra copy plus 16 MiB free. A failed song left the startup audit pending; a recent successful audit could postpone a new recovery. Opening SimpleCache with a missing or different content index could itself delete spans before import. `7a760cbf4` repaired download dates; recovery keeps that repair.

Startup now runs recovery after library migration, even if an earlier audit succeeded recently. Recovery reads the Media3 database read-only and atomically writes small span manifests under `files/legacy-audio`. Room indexes complete audio in place, so recovery copies no audio and needs no second file's worth of free space. Playback seeks across spans; cache promotion and folder exports read the same spans in order. A completed download-index entry remains a download even when its audio is in the player cache. Length comes from cache metadata, the download index, Room's format, or the downloaded byte count of a completed request. Coverage must start at zero and reach that length with no gaps, and source file lengths must match the index.

Titles, ordered artists, albums and artwork URLs come from migrated Room songs. Both collections retain the old `dateDownload`; missing dates use the request start time or the span timestamp. Import does not fetch metadata or artwork from the network. Artwork embedded in exported files remains a separate concern.

Recovery never opens SimpleCache or removes legacy audio. Missing indexes, unreadable metadata and failed Room writes retain all spans and keep recovery pending. Other songs still import when one entry fails. Partial, gapped, unknown-length, unmapped and removing/restarting entries remain on disk without being published. Repeated runs preserve existing v14 audio and its dates. Normal removal, eviction or a successful folder export can later reclaim indexed spans. An empty manifest records a handled song so another audit cannot restore audio the user removed. Files already deleted by an earlier app version cannot be recovered here.

Storage screens count indexed audio once. Clearing the song cache removes indexed cache entries, including their legacy spans, and preserves downloaded entries in either old directory. It leaves unclassified legacy files intact. Android system backups exclude legacy audio and recovery manifests, as they do current audio storage.

## Formats

Android shared-folder exports remux WebM/Opus to tagged Ogg Opus and AAC to tagged M4A without re-encoding. New exports embed the retained PNG artwork when it is available, along with title, artist and album tags. Conversion or tagging failure must not publish raw source files. Private storage keeps the original representation. Shared-cache migrations retain originals until replacement files and format metadata commit.

Desktop downloads transcode to tagged AAC/M4A before publication. Temporary JavaCPP tag strings, rational values and frame views are released at their call sites; partially initialized encoders and metadata dictionaries also have cleanup owners.

SABR downloads and incomplete playback captures use validated transfers. Tokenless downloads renew sessions after 45 seconds of media and resume at a segment boundary. Renewal must preserve the encoded representation. Reject gaps, changed bytes and incomplete transfers; see [SABR constraints](innertube.md#sabr).

iOS capture validates URLs, redirects, response ranges and lengths. Captures retain session ownership through preloads and crossfades. Cache clear prevents publication while allowing attached readers to finish. Startup removes abandoned session files.

For fragmented AAC with an empty sample table, iOS clears container durations that AVFoundation would count twice. Files with samples in `moov` remain unchanged. Saved catalog duration takes precedence over an inflated file timeline. See [iOS Opus](ios-opus.md) for remux limits and offline priority.

## Animated artwork

Desktop stores media in `animated_artwork_cache` under its data directory and uses the shared disk-cache wrapper. `animatedArtworkCacheSize` defaults to 2048 MiB; zero disables retention.

Finite MP4/WebM files and supported finite HLS playlists have a 32 MiB per-file limit and a 15-second transfer deadline. Live, encrypted, discontinuous and cross-origin playlists keep native URL playback. Active snapshots survive cache clearing until their last reader closes. Session files remain temporary when retention is disabled.

## Verification

Run affected [platform checks](checks.md#platform-checks). Use disposable storage to check cold-cache completion, seeks, crossfades, cache clear during capture, low disk space, failed migration and each download destination. On iOS, check that throttled capture does not block unrelated playback or republish after clear.

On desktop, Auto download saved albums and playlists starts downloads only after saving succeeds. Albums finish loading their remaining tracks first. Playlists use complete stored membership or load all pages, and downloads continue after leaving the collection page. Unavailable and blocked songs are filtered through the normal download path.
