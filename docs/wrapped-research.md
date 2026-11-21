# Metrolist Wrapped research

Research date: 2026-11-21. The selected scope is recorded below; implementation behavior is documented in [Development](development.md#statistics).

## Existing implementations

- [Spotify Wrapped](https://newsroom.spotify.com/2025-12-03/2025-wrapped-user-experience/) combines short stories, top songs and artists, listening totals, playlists and share cards. Its cohort comparisons require Spotify's population data. Metrolist cannot derive equivalent global rankings from one listener's history.
- [Apple Music Replay](https://support.apple.com/109356) combines monthly and annual statistics, a highlight reel, sharing and replay playlists. Its year-round access is a useful model for an entry point that remains available after December.
- [YouTube Music Recap](https://support.google.com/youtubemusic/answer/11418178) includes annual and seasonal playlists and statistics. Eligibility depends on retained, unpaused listening history. Metrolist already discovers recap playlist shelves through `FEmusic_listening_review`; this does not supply the full interactive recap or measured listening duration.
- [ListenBrainz Year in Music](https://community.metabrainz.org/t/year-in-music-2025/813991) supports past reports. Its [statistics API](https://listenbrainz.readthedocs.io/en/latest/users/api/statistics.html) documents artist, recording, release, activity and year-in-music endpoints. It is an optional account-wide data source, including listening from other scrobbling clients.
- [make-a-wrapped](https://github.com/DevMatei/make-a-wrapped) generates recaps from scrobbling services and supports templates and an HTTP API. It is a server application with an AGPL-3.0 license. Its report compositions are useful references; using its service would add network and data-sharing requirements to a feature Metrolist can calculate locally.

Metrolist's own git history contains an earlier Android Wrapped implementation. The files at commit `73ca593d4` include `WrappedScreen`, `WrappedPages`, `WrappedViewModel` and a separate audio controller. That implementation uses fixed 2026 half-year dates and prepares multiple streams before its audio flow is ready. These files are absent from the current shared application. Any reused work must retain its attribution and license notices.

The current [Wrapped issue #5105](https://github.com/MetrolistGroup/Metrolist/issues/5105) asks for an always-available recap. It is unassigned and has no linked PR as of this research. The older [local Wrapped request #1973](https://github.com/MetrolistGroup/Metrolist/issues/1973) was closed after an implementation in the earlier Android release. Searches found no PR with Wrapped in its title; broad searches also return unrelated mentions.

## What the current application provides

The shared database stores play timestamps, listening duration, whether duration is known, source and available account provenance, and song metadata snapshots. `HistoryStats.kt` already computes top songs, artists and albums, total plays, measured listening time, and daily and hourly activity. Statistics applies existing song and artist exclusions. Hourly activity excludes date-only records and unmeasured YouTube imports. Statistics has no account filter, and historic local events generally have no account identity. A recap reusing these records is device-history based rather than account-specific.

Imported or synced history can establish a play without establishing how long it was heard. The duration of the recording is not the duration listened. A recap must label measured time and preserve unknown values. A recorded-duration count establishes how many rows are marked known and have positive duration, not the fidelity of foreign imports. Some old import paths mark absent duration as known zero. The first stored event establishes the start of available records, not proof that the complete period was recorded.

The shared statistics UI already has ranked artwork, emphasized Material typography, charts, RTL support and an image renderer. Android and iOS share the resulting PNG through native share sheets; desktop saves it through a file chooser. The existing export guards prevent sharing an empty, loading or failed result.

Current reference code:

- [History statistics](../shared/src/commonMain/kotlin/com/metrolist/shared/db/HistoryStats.kt)
- [Stored event metadata](../shared/src/commonMain/kotlin/com/metrolist/shared/db/entities/Event.kt)
- [Shared statistics screen](../shared/src/commonMain/kotlin/com/metrolist/shared/ui/screens/StatisticsScreen.kt)
- [PNG rendering](../shared/src/commonMain/kotlin/com/metrolist/shared/ui/statistics/StatisticsImage.kt)
- [Statistics behavior](development.md#statistics)

## Platform feasibility

| Area | Android | iOS | Desktop |
| --- | --- | --- | --- |
| Statistics computation | Shared Room queries | Shared Room queries | Shared Room queries |
| Presentation | Shared Compose content through the Android navigation host | Shared Compose content through the mobile host | Shared content with a wide layout and keyboard support |
| PNG export | Existing native share integration | Existing native share integration | Existing file chooser and image saving |
| Motion | Existing Compose and Material motion | Existing Compose and Material motion | Existing Compose and Material motion |
| Last.fm and ListenBrainz data | Shared HTTP clients can be extended | Shared HTTP clients can be extended | Shared HTTP clients can be extended |
| Runtime verification here | Connected Android devices | Requires an Apple runtime for device or simulator verification | Local Linux runtime available; macOS and Windows require their runtimes |

All three platforms can calculate a recap without a new Metrolist server. This means shared behavior, not identical records on every device. A combined cross-device history requires an explicit data source or synchronization contract; Device Connect playback alone does not establish one.

## Optional integrations

1. Use Metrolist's stored history as the default source. This reuses existing retention, exclusions and unknown-duration handling. It needs no new login. Android and desktop Statistics rank by play count, while the mobile iOS statistics loader ranks by measured time. Wrapped must use its own consistent rule across platforms.
2. Keep discovered YouTube recap playlists as playable playlist links. Do not make the local recap depend on their availability. The public [YouTube Data API](https://developers.google.com/youtube/data/reference/rest/v3/playlistItems/list) does not offer a supported watch-history retrieval path. Existing InnerTube history is a separate integration with its current precision limits.
3. Add Last.fm as an explicit alternative report source if requested. The shared client already supports scrobbling, recent tracks and top tracks. [user.getTopTracks](https://www.last.fm/api/show/user.getTopTracks) offers relative periods, including 12 months. An exact calendar year needs dated scrobbles or weekly chart aggregation, rather than treating a rolling 12-month chart as a calendar year. Scrobble counts do not establish exact listening minutes.
4. Add ListenBrainz as another explicit source if requested. Its documented year-in-music endpoint can provide a prepared annual report, while activity and ranking endpoints support other ranges. The current Metrolist client submits listens and validates tokens; reading reports is new work. Reports can be missing or not yet calculated, and their fields retain the service's definitions.
5. Treat Google Takeout or other history-file imports as separate import work. Parse recorded fixtures, preserve provenance and unknown duration, and deduplicate against existing events before including them in statistics. A file export is not automatically a complete history.

Never add remote service totals to local totals directly. The same listening event may already exist locally and in a scrobbling service. Exact deduplication requires event identity or sufficient timestamp precision. The user selected the larger YouTube or Metrolist count per song and local date group for Wrapped, retaining Metrolist's recorded listening time. This explicit counting rule can undercount separate plays and does not alter stored records.

Genre, mood, artist-country and listener-percentile stories need additional trustworthy metadata or population data. They cannot be inferred accurately from the current statistics alone. New playback backends are outside this feature.

## Design guidance

Opus 5.5 recommends a chaptered recap containing the selected period, plays and measured time, top artists, top songs, top albums, listening patterns and a final share summary. It proposes manual mobile navigation and a scrollable desktop report. The GPT 6.1 Sol review identified that imported week, month and year groups can appear on their period's first day. Daily totals therefore cannot establish streaks without additional precision filtering. Comparisons and discovery claims also need enough historical coverage to be meaningful. These claims are excluded from the selected first scope.

Gemini 4 Neon supplied two visual directions. An editorial layout follows the active app theme, uses large figures and shaped artwork, and adapts to a desktop grid. An artwork-led layout gives each chapter a palette derived from its leading image and uses a focused stage on desktop. Both can reuse existing typography, contrast checks and reduced-motion support.

The recommended first scope is shared recap content with manual navigation, a clear measured-time label, and local PNG export. It can be entered through Statistics throughout the year. Desktop's Home restrictions rule out adding a local-statistics shelf there. A mobile Home shortcut is a separate entry-point choice.

The smaller option is a dedicated recap summary and poster inside Statistics. Story playback, per-chapter images, video export, new data providers and year-over-year comparisons are independent scope choices.

## Selected scope

- Chapters on Android and iOS, with a scrollable desktop report.
- The current calendar year through today, available from Statistics throughout the year.
- Merged saved YouTube and Metrolist history, using the larger source count for each song and local date group. Preserve recorded time and stored history.
- Rank songs, artists and albums by play count, and show measured listening time separately.
- Material 3 Expressive, following the official [motion physics guidance](https://m3.material.io/styles/motion/overview/how-it-works) and existing app typography, shapes and color roles.
- An opt-in top-song soundtrack that preserves regular playback and restores it on exit while the temporary session still owns it.
- One locally generated summary PNG.

New history providers, a mobile Home shortcut, streaks, video export, playlist creation and comparisons are outside the selected first scope.

The user authorized full-send through an unmerged PR and a release build installed on the connected OnePlus CPH2747 over Wi-Fi. The PR must remain unmerged until the user gives a separate signal. The existing manual Android build workflow accepts a release variant and requires release signing. Installation should update the existing app without deleting its data.

External API contracts were read from documentation. The research did not exercise new provider APIs. iOS runtime verification needs an Apple environment; Linux compilation and Android device checks cannot establish iOS behavior.
