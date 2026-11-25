# InnerTube integration

## Dependency development

`innerTubeXVersion` in `gradle.properties` pins the hosted artifact from [InnerTubeX](https://github.com/MetrolistGroup/innertubex). App builds do not need its source checkout.

For dependency development, choose one substitution:

- `-PuseLocalInnerTubeX` uses the sibling `../innertubex` composite build.
- `-PuseMavenLocalInnerTubeX -PinnerTubeXVersion=<local-version>` uses Maven local.

These flags do not alter hosted pins. QuackJS handles signature and throttling transforms separately from PO-token attestation.

## Content locale

`GlobalYouTube.contentLocale` retains the resolved content language, including region and script subtags. Shazam, Paxsenix lyrics, subtitle preferences and Last.fm use it. Subtitle selection tries the exact tag, then its primary language, while preserving the preference for manual captions over automatic captions. Saved `lastResolvedContentLanguage` also retains this tag for artwork and artist lookups.

When YouTube Music does not serve the system region, `closestContentCountry` picks the alphabetically first served region with a locale in the same language, preferring the same script. If no served region uses that language, it sends `US`, as the maintainer decided in [#5150](https://github.com/MetrolistGroup/Metrolist/issues/5150).

Only the InnerTube locale maps the language to a YouTube-accepted `hl`, trying the full tag, its first two subtags and then the primary language. The isolated song-details request applies the same mapping. YouTube response date parsing, Home cache signatures, playback diagnostics and search suggestions use the request locale. Discord labels and display number/date formats use the app or system locale.

If no tag is accepted, `hl` is blank and InnerTubeX v0.13.1 or later leaves it out of the request context, so YouTube picks the language from the IP address and request headers.

## Pagination

App callers use `shared/src/commonMain/kotlin/com/metrolist/shared/api/YouTubeRepository.kt`.

- Playlist page flows are cold and emit deltas without prefetch. `first()` must not request continuations. Resume from the saved cursor and preserve duplicates by song ID plus playlist occurrence ID.
- Generated mix continuations have no final page. Whole-playlist actions use the loaded pages as a finite snapshot, and a cache refresh replaces the previous batch. Normal playlists and albums still load every page.
- Consumers sharing a playlist cursor reuse successful deltas. Failure or cancellation leaves it retryable. Keep account/session checks around reused results.
- Podcast pagination follows distinct continuation branches and deduplicates episode IDs. Retain sibling tokens and failed cursors. Whole-podcast actions need every episode.
- Interactive browse/Home shelf loaders request first pages with `loadAllPages = false`. Resume shelf cursors before outer cursors, preserve response order and retain failures for retry.
- `PlaylistPage.isOffline` marks an incomplete local snapshot. It cannot replace full stored membership, remote counts or account likes. Liked-song sync disables offline fallback.
- Playlist queues finish continuations before recommendations. Full-queue consumers, including synchronized playback, need complete snapshots. Respect cancellation and Auto load more when accepting in-flight results.

Artist failures retain available offline data. Without it, propagate the original error and log the response stage/status without bodies or credentials.

Greyed browse rows with a title and duration remain eligible for playback, including rows whose title opens a Premium dialog. That restriction describes the browse client; the stream resolver can try other clients. Deleted and private placeholders without playable metadata stay unavailable.

## Search

Spotify account imports first search with the source album in the query, then retry without it if no exact title, artist and duration match is found. This handles YouTube Music catalog entries whose album metadata differs while keeping the match checks unchanged.

Android, desktop and iOS request suggestions after a 300 ms typing debounce and load results on submit. Only an empty field shows the search start page. Nonempty input keeps the suggestions page, with the typed query available as a search action while suggestions are unavailable. Existing suggestions stay visible while their replacement loads or fails. Clearing, submission and edits shorter than two characters discard remote suggestions; cancelled requests cannot publish old suggestions.

Verify typing after submitted results, rapid edits, deletion to one character, clearing, failed requests, submission and Back. Desktop inline search uses the shared debounce and does not start a separate preview search.

## Home

Home projects API renderers through shared models. Preserve shelf order, header endpoints, subtitles, durations and continuation ownership. Card shelves retain parent collections and child tracks through existing cards. Rich preview layouts need matching response data before implementation.

Home shelf pages use two columns for collections and artists, full-width 16:9 cards for video shelves, and rows for songs or mixed content. They retain the shelf items while loading its continuation or browse endpoint. Android and the shared mobile host retain page loaders with their navigation entries; paging failures keep the cursor for retry. Page identity includes the feed endpoint, chip parameters and shelf identity. Shelf More endpoints take precedence over header links. Artist header links never replace shelf contents with an artist page; a filtered shelf without its own endpoint parameters or cursor shows its existing items.

Quick picks reuses a source shelf's browse endpoint and continuation only when that shelf contains every displayed pick. Local recommendations that only overlap an API shelf keep their existing songs when expanded. Without an endpoint or continuation, the page ends without a network request. Songs present in the "Heard in Shorts" shelf are always excluded from Quick picks while remaining in the "Heard in Shorts" shelf when it is visible.

Signed-in, unfiltered Home fetches only missing moods and genres from Explore after its last continuation. Existing main-feed moods skip that request. The moods shelf stays at the end of the main feed, before any separately appended feeds. No other Explore shelves enter signed-in Home.

Mobile API library shelves show saved music albums while podcast shelves remain separate. Filter by browse ID and shared model type, never translated titles. Desktop excludes library/local-only sections under the [agent rule](../AGENTS.md#implementation-rules). Speed Dial is allowed.

Home cache identity respects account, locale and browse policy. Visitor publication must pass InnerTubeX's session compatibility check before accepting an in-flight response.

Authenticated Home, playlist and shelf requests reject signed-out responses before publishing or caching their content. YouTube Music can report this through `responseContext.serviceTrackingParams`, with `GFEEDBACK`'s `logged_in` set to `0`, without the older `mainAppWebResponseContext.loggedOut` field. A rejected response rechecks the account menu. The shared account-browse reader uses the same verification policy. A working account or an unavailable account check retains the session; only confirmed account rejection uses the existing session-expiry handling. Intentionally anonymous browsing still accepts signed-out feeds.

Home diagnostics record HTTP status, whether login was requested, credential and visitor presence, region override and shelf counts. They omit credentials and content. A generic feed alone does not establish that YouTube treated a request as signed out.

On mobile, signed-out users and users with Classic recommendations enabled get unfiltered Home Quick picks from local listening history: songs related to recently played tracks plus a few forgotten favourites, stripped of blocked entries, non-musical videos and podcasts before sampling. Filtered Home shelves and their continuations keep API-first selection. Qualified local plays fetch related music for these picks, and new history results rebuild the shelf without a Home reload. API picks remain the fallback until local recommendations are available. When local recommendations are active, unfiltered Home continuations retain the current Quick picks, using API picks only if the current list is empty. Restored snapshots rebuild picks for the current mode, falling back to the snapshot's saved picks when its shortened feed has no API shelf. Signed-out recommendation selection does not enable Classic radio. Desktop Home keeps its API-only discovery selection.

Legacy settings migration and backup imports convert `SYSTEM_DEFAULT` and lowercase `system` to `SYSTEM` for app language, content language and content country. Invalid saved content languages and countries return to the system locale before requests, including settings already migrated to v14. Language validation uses the platform locale catalog and bundled resource tags. Imports also clear v13's literal `null` visitor data without discarding valid session values. Failed restores keep their exact settings snapshot.

Artist subscriber and monthly-audience counts use the same extractor without consulting the app or content locale. It preserves YouTube's digits, separators and compact unit, including counts after a translated label. Unknown or ambiguous formats retain YouTube's full text and omit the app's audience label. The shared Compose artist page, About view, menus and song details use that distinction on Android, desktop and iOS. Captured request pairs and refresh instructions are in [test fixtures](test-fixtures.md#artist-audience-counts).

## Audio counterparts

Video-to-audio lookup prefers YouTube's explicit counterpart and rejects changes between studio and alternate versions. Search fallback requires the complete title after artist-prefix and presentation-decorator removal and an exact artist name or shared artist ID. Known durations use the existing music-video ratio and intro/outro limits; the audio track may exceed the video's duration by at most 30 seconds. Matching tracks rank by shared artist ID, audio type, closest known duration and search order. The selected video stays when the title names a live performance, remix or other alternate version. Title normalization preserves Unicode combining marks. Audio-to-video selection rules and counterpart position maps remain unchanged. With music videos turned off, Android plays a requested video's own audio and looks up no audio counterpart; only Song mode with videos on swaps to the audio track.

## History and uploads

Imported history keeps localized absolute date-bucket labels and server row order. Refresh repairs matched remote rows without overwriting measured local timestamps. Date buckets count in daily/total statistics, not hourly charts. Preserve offline labels and cancellation during synchronization.

Uploads require authenticated identity propagation through browse and playback. Local parser fixtures cannot establish live account response shapes or immediate upload visibility. Upload tests mutate the account; run them only with explicit authorization.

## PO tokens

Web SABR requires video-bound GVS attestation from the current watch page. Pair its challenge and `EVENT_ID` from that page. Player attestation uses a separate visitor-bound token. Redirects stay disabled; page-bound watch requests use `app=desktop`.

Providers advertise capabilities. Do not substitute a generic sidecar token for webpage attestation. External desktop providers require loopback HTTP plus declared capabilities through `innerTubePoTokenProviderUrl` and `innerTubePoTokenProviderCapabilities` or their environment equivalents.

A CDN 403 for an explicitly untokenized `_nopo` profile excludes that profile without discarding unrelated cached attestation. Tokenized or unidentified profiles invalidate attestation. Retain provider cooldowns, bounded mint deadlines and account/visitor validation.

After a web-token rejection, audio requests try tokenless VISIONOS first. A failed probe continues through the normal clients. Video and YouTube canvas requests keep their video requirement and skip this audio-only preference.

Android and desktop playback allow InnerTubeX's progressive transport, which iOS, downloads and canvas requests do not. `IOS_PRODUCER` needs no PO token, but YouTube only returns muxed 360p itag 18 for it, so selection tries it after every other healthy client. `PlaybackResource.isMuxedProgressive` keeps those files out of the persistent song caches, so a later play resolves a full-quality stream again.

YouTube Music video canvas over SABR plays from the start only. Short-clip seeks and clip preparation stay disabled for SABR because mid-video requests often stay on protection status 2 with no media and fail as `ATTESTATION_REQUIRED`. Direct video canvas can still use short clips.

Token callbacks keep request-local results. A canceled Kotlin caller does not cancel a WebView promise; late results cannot overwrite another request. Check this with `node --test scripts/test_page_bound_po_token.mjs`.

With crash reporting enabled, `PlaybackClientStats` counts resolve outcomes per client profile and playback successes and failures by category. It stores only catalog profile IDs and counters, never video IDs. Every fifth launch Android and desktop send one Sentry log per profile and reset the counts; sum `playback.play.ok` and `playback.play.fail.*` by `playback.profile` in Sentry Logs to compare clients. iOS counts locally but does not send them yet.

## SABR

`SabrAudioStream` can mint alongside an untokenized bootstrap. Later requests attach the ready token in streamer-context field 2. Before requesting beyond 80 seconds of media, or after PENDING/REQUIRED protection status, wait within the mint's 30-second bound. Failure retains attestation recovery.

Playback validates overlapping ranges against emitted bytes. Identical repeats after seeks are valid; changed bytes fail. Forward and backward discontinuities are allowed. One response without media progress or safe readahead is allowed for initial server backoff; repeated lack of progress triggers recovery. Video and download streams have separate limits.

`shareCachedDataFrom` requires an unopened replacement with the same encoded representation. Reference-counted storage is shared, but each stream owns its cursor, producer and failure state. Gaps are incomplete streams, not EOF. Normal producer completion must cover the expected duration when timestamps are available.

Android closes storage off the playback thread. Video hiding must wait until the initial container probe completes. Reattachment resumes from the audio clock with seek preroll and retained bytes. See [cache transfer rules](audio-cache.md#formats), [iOS preparation](ios-opus.md#capture-limits) and [error policy](playback.md#error-policy).

## Live stream classification

Player metadata uses current-live flags or an HLS-only response without a finite duration. `isLiveContent` alone does not mark an active stream. Recordings with a finite duration keep seeking and the normal client fallback, including saved songs with a live performance type. Confirmed live playback clears the song duration so extraction and player controls use the same classification.

Stream resolution uses the same rule for Android HLS responses. Extracted streams retain finite durations from client metadata, SABR or the queued song. Playback resources carry the resolver's classification; client profiles and URL markers alone do not mark a song live.

## Live tests

`Live*` tests under `shared/src/desktopTest/kotlin/com/metrolist/shared/youtube/innertube/` contact YouTube. They require explicit opt-in and never run in CI. Supply `INNERTUBE_TOKEN_BASE64` through the environment or untracked `local.properties` for signed-in probes. Never commit credentials or unsanitized traces.

Run `./gradlew :shared:desktopTest --tests '*<suite>' <flags>`.

| Suite | Enable flags |
| --- | --- |
| `LiveInnerTubeClientMatrixTest` | `-DrunLiveInnerTubeClientTests=true`; add `-DfailLiveInnerTubeClientFailures=true` for per-client failures |
| `LiveYouTubeRepositorySmokeTest` | `-DrunLiveInnerTubeClientTests=true` |
| `LiveInnerTubeClientBenchmarkTest` | `-DrunLiveInnerTubeClientBenchmarks=true -DinnerTubeBenchmarkIterations=5` |
| `LiveInnerTubePlaybackMatrixTest` | `-DrunLiveInnerTubePlaybackMatrix=true` |
| `LiveIosSabrAudioTest` | `-DrunLiveDesktopSabrPlaybackTest=true` |
| `LiveSignedOutPlaybackMatrixTest` | `-DrunLiveSignedOutPlaybackMatrix=true` |
| `LiveUploadedSongTest` | `-DliveUploadFile=<local-file>`; uploads, decodes and deletes that track |

For signed-out runs, use `-DinnerTubeSignedOut=true`. Upload probes need a new file at least 30 seconds long. Preserve minimum probe delays. Reports go to `shared/build/reports/live-innertube/`; playback reports include sanitized HAR output. A player response alone does not verify audible playback.

## Backend verification

Run affected [platform checks](checks.md#platform-checks). Relevant suites are the parser tests beside changed code. Fixture sources and limits are in [test fixtures](test-fixtures.md).
