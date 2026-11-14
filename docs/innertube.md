# InnerTube integration

## Dependency development

`innerTubeXVersion` in `gradle.properties` pins the hosted artifact from [InnerTubeX](https://github.com/MetrolistGroup/innertubex). App builds do not need its source checkout.

For dependency development, choose one substitution:

- `-PuseLocalInnerTubeX` uses the sibling `../innertubex` composite build.
- `-PuseMavenLocalInnerTubeX -PinnerTubeXVersion=<local-version>` uses Maven local.

These flags do not alter hosted pins. QuackJS handles signature and throttling transforms separately from PO-token attestation.

## Pagination

App callers use `shared/src/commonMain/kotlin/com/metrolist/shared/api/YouTubeRepository.kt`.

- Playlist page flows are cold and emit deltas without prefetch. `first()` must not request continuations. Resume from the saved cursor and preserve duplicates by song ID plus playlist occurrence ID.
- Consumers sharing a playlist cursor reuse successful deltas. Failure or cancellation leaves it retryable. Keep account/session checks around reused results.
- Podcast pagination follows distinct continuation branches and deduplicates episode IDs. Retain sibling tokens and failed cursors. Whole-podcast actions need every episode.
- Interactive browse/Home shelf loaders request first pages with `loadAllPages = false`. Resume shelf cursors before outer cursors, preserve response order and retain failures for retry.
- `PlaylistPage.isOffline` marks an incomplete local snapshot. It cannot replace full stored membership, remote counts or account likes. Liked-song sync disables offline fallback.
- Playlist queues finish continuations before recommendations. Full-queue consumers, including synchronized playback, need complete snapshots. Respect cancellation and Auto load more when accepting in-flight results.

Artist failures retain available offline data. Without it, propagate the original error and log the response stage/status without bodies or credentials.

## Search

Android, desktop and iOS request suggestions after a 300 ms typing debounce and load results on submit. Only an empty field shows the search start page. Nonempty input keeps the suggestions page, with the typed query available as a search action while suggestions are unavailable. Existing suggestions stay visible while their replacement loads or fails. Clearing, submission and edits shorter than two characters discard remote suggestions; cancelled requests cannot publish old suggestions.

Verify typing after submitted results, rapid edits, deletion to one character, clearing, failed requests, submission and Back. Desktop inline search uses the shared debounce and does not start a separate preview search.

## Home

Home projects API renderers through shared models. Preserve shelf order, header endpoints, subtitles, durations and continuation ownership. Card shelves retain parent collections and child tracks through existing cards. Rich preview layouts need matching response data before implementation.

Home shelf pages use two columns for collections and artists, full-width 16:9 cards for video shelves, and rows for songs or mixed content. They retain the shelf items while loading its continuation or browse endpoint. Android and the shared mobile host retain page loaders with their navigation entries; paging failures keep the cursor for retry. Page identity includes the feed endpoint, chip parameters and shelf identity. Shelf More endpoints take precedence over header links. Artist header links never replace shelf contents with an artist page; a filtered shelf without its own endpoint parameters or cursor shows its existing items.

Signed-in, unfiltered Home fetches only missing moods and genres from Explore after its last continuation. Existing main-feed moods skip that request. The moods shelf stays at the end of the main feed, before any separately appended feeds. No other Explore shelves enter signed-in Home.

Mobile API library shelves show saved music albums while podcast shelves remain separate. Filter by browse ID and shared model type, never translated titles. Desktop excludes library/local-only sections under the [agent rule](../AGENTS.md#implementation-rules). Speed Dial is allowed.

Home cache identity respects account, locale and browse policy. Visitor publication must pass InnerTubeX's session compatibility check before accepting an in-flight response.

## History and uploads

Imported history keeps localized absolute date-bucket labels and server row order. Refresh repairs matched remote rows without overwriting measured local timestamps. Date buckets count in daily/total statistics, not hourly charts. Preserve offline labels and cancellation during synchronization.

Uploads require authenticated identity propagation through browse and playback. Local parser fixtures cannot establish live account response shapes or immediate upload visibility. Upload tests mutate the account; run them only with explicit authorization.

## PO tokens

Web SABR requires video-bound GVS attestation from the current watch page. Pair its challenge and `EVENT_ID` from that page. Player attestation uses a separate visitor-bound token. Redirects stay disabled; page-bound watch requests use `app=desktop`.

Providers advertise capabilities. Do not substitute a generic sidecar token for webpage attestation. External desktop providers require loopback HTTP plus declared capabilities through `innerTubePoTokenProviderUrl` and `innerTubePoTokenProviderCapabilities` or their environment equivalents.

A CDN 403 for an explicitly untokenized `_nopo` profile excludes that profile without discarding unrelated cached attestation. Tokenized or unidentified profiles invalidate attestation. Retain provider cooldowns, bounded mint deadlines and account/visitor validation.

Token callbacks keep request-local results. A canceled Kotlin caller does not cancel a WebView promise; late results cannot overwrite another request. Check this with `node --test scripts/test_page_bound_po_token.mjs`.

## SABR

`SabrAudioStream` can mint alongside an untokenized bootstrap. Later requests attach the ready token in streamer-context field 2. Before requesting beyond 80 seconds of media, or after PENDING/REQUIRED protection status, wait within the mint's 30-second bound. Failure retains attestation recovery.

Playback validates overlapping ranges against emitted bytes. Identical repeats after seeks are valid; changed bytes fail. Forward and backward discontinuities are allowed. One response without media progress or safe readahead is allowed for initial server backoff; repeated lack of progress triggers recovery. Video and download streams have separate limits.

`shareCachedDataFrom` requires an unopened replacement with the same encoded representation. Reference-counted storage is shared, but each stream owns its cursor, producer and failure state. Gaps are incomplete streams, not EOF. Normal producer completion must cover the expected duration when timestamps are available.

Android closes storage off the playback thread. Video hiding must wait until the initial container probe completes. Reattachment resumes from the audio clock with seek preroll and retained bytes. See [cache transfer rules](audio-cache.md#formats), [iOS preparation](ios-opus.md#capture-limits) and [error policy](playback.md#error-policy).

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

Run affected [platform checks](checks.md#platform-checks). Relevant suites include `SabrInputStreamTest`, `SabrPlaybackStreamsTest`, `SabrAudioFetchTest`, `DefaultStreamResolverTest` and the repository/parser tests beside changed code. Fixture sources and limits are in [test fixtures](test-fixtures.md).
