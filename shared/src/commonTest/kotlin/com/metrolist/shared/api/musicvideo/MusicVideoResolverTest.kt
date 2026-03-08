package com.metrolist.shared.api.musicvideo

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class MusicVideoResolverTest {
    @Test
    fun cachedAudioSwitchRestoresTheSelectedVideoAndItsTimingOnDisk() =
        runBlocking {
            val settings = Settings(MapSettings())
            val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
            val firstVideo = track.copy(id = "VIDEO_1", musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
            val secondVideo = firstVideo.copy(id = "VIDEO_2")
            val firstMap = CounterpartPositionMap(listOf(CounterpartSegment(4_000L, 0L, 120_000L)))
            val secondMap = CounterpartPositionMap(listOf(CounterpartSegment(9_000L, 0L, 120_000L)))
            var lookups = 0

            fun resolver() =
                MusicVideoResolver(
                    counterpartLookup = { video ->
                        lookups++
                        MusicVideoCounterpart(
                            video.id,
                            track.id,
                            track.musicVideoType,
                            if (video.id == firstVideo.id) firstMap else secondMap,
                        )
                    },
                    songLookup = { track },
                    search = { _, _ -> error("Known counterpart should not search") },
                    settings = settings,
                )

            val resolver = resolver()
            val firstAudio = resolver.resolveMusicTrack(firstVideo)
            resolver.resolveMusicTrack(secondVideo)
            assertEquals(MusicVideoResult.Found(secondVideo.id, secondMap.reversed()), resolver.resolveMusicVideo(track))

            assertEquals(firstAudio, resolver.resolveMusicTrack(firstVideo))
            val expected = MusicVideoResult.Found(firstVideo.id, firstMap.reversed())
            assertEquals(expected, resolver.resolveMusicVideo(track))
            assertEquals(expected, resolver().resolveMusicVideo(track))
            assertEquals(2, lookups)
        }

    @Test
    fun audioSwitchRetainsUgcVideoWhenNextAndVideoSearchHaveNoCounterpart() =
        runBlocking {
            val settings = Settings(MapSettings())
            val video =
                song(
                    "W5ww34C76Gk",
                    "MYTH IN SLAUGHTER | Hoax chase theme | BITE BY NIGHT OST",
                    "DxPrism",
                    "MUSIC_VIDEO_TYPE_UGC",
                )
            val track = song("olNdzTu5QYg", "Myth In Slaughter", "DxPrism", "MUSIC_VIDEO_TYPE_ATV")
            var searches = 0

            fun resolver() =
                MusicVideoResolver(
                    counterpartLookup = {
                        val response =
                            Json
                                .parseToJsonElement(
                                    """{"contents":[{"playlistPanelVideoRenderer":{
                                    "videoId":"${it.id}",
                                    "navigationEndpoint":{"watchEndpoint":{
                                        "videoId":"${it.id}",
                                        "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{
                                            "musicVideoType":"${it.musicVideoType}"
                                        }}
                                    }}
                                }}]}""",
                                ).jsonObject
                        parseMusicVideoCounterpart(response, it.id)
                    },
                    search = { _, filter ->
                        searches++
                        SearchResultPage(if (filter == SearchFilter.SONG) listOf(track) else emptyList())
                    },
                    songLookup = { null },
                    settings = settings,
                )

            val resolver = resolver()
            assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track))
            assertEquals(MusicTrackResult.Found(track), resolver.resolveMusicTrack(video))
            assertEquals(MusicVideoResult.Found(video.id), resolver.resolveMusicVideo(track))
            assertEquals(MusicVideoResult.Found(video.id), resolver().resolveMusicVideo(track))
            assertEquals(3, searches)

            resolver.evictMapping(video)
            assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track))
            assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track.copy(id = "ATV_ONLY")))
        }

    @Test
    fun audioSwitchRetainsAuthoritativeVideoWithReversedTiming() =
        runBlocking {
            val video = song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
            val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
            val response =
                Json
                    .parseToJsonElement(
                        """{"contents":[{"playlistPanelVideoWrapperRenderer":{
                        "primaryRenderer":{"playlistPanelVideoRenderer":{"videoId":"TRACK"}},
                        "counterpart":[{
                            "counterpartRenderer":{"playlistPanelVideoRenderer":{"videoId":"VIDEO"}},
                            "segmentMap":{"segment":[{
                                "primaryVideoStartTimeMilliseconds":"0",
                                "counterpartVideoStartTimeMilliseconds":"4492",
                                "durationMilliseconds":"131989"
                            }]}
                        }]
                    }}]}""",
                    ).jsonObject
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = {
                        check(it.id == video.id) { "Known reverse mapping should skip /next" }
                        parseMusicVideoCounterpart(response, it.id)
                    },
                    search = { _, _ -> error("Known counterpart should not search") },
                    songLookup = { track },
                    settings = Settings(MapSettings()),
                )
            val audio = resolver.resolveMusicTrack(video) as MusicTrackResult.Found
            val restored = resolver.resolveMusicVideo(audio.song) as MusicVideoResult.Found

            assertEquals(video.id, restored.videoId)
            assertEquals(500L, audio.positionMap?.mapPosition(4_992L))
            assertEquals(4_992L, restored.positionMap?.mapPosition(500L))
            resolver.evictMapping(track)
            assertEquals(0, resolver.getCacheEntryCount())
        }

    @Test
    fun cancellingTheTrackLookupOwnerLetsAWaitingCallerRetry() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val video = song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
            var calls = 0
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = { null },
                    search = { _, _ ->
                        calls++
                        release.await()
                        SearchResultPage(listOf(song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")))
                    },
                    settings = Settings(MapSettings()),
                )
            val owner = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolveMusicTrack(video) }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolveMusicTrack(video) }
            owner.cancelAndJoin()
            release.complete(Unit)
            assertTrue(waiting.await() is MusicTrackResult.Found)
            assertEquals(2, calls)
        }

    @Test
    fun trackLookupsShareMetadataAndPreservePositionMapsUntilExpiryOrInvalidation() =
        runBlocking {
            val settings = Settings(MapSettings())
            val clock = FakeClock(1_765_000_000_000L)
            val video = song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
            val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
            val map = CounterpartPositionMap(listOf(CounterpartSegment(0L, 1_000L, 90_000L)))
            val release = CompletableDeferred<Unit>()
            var metadataCalls = 0
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = { MusicVideoCounterpart(it.id, track.id, track.musicVideoType, map) },
                    songLookup = {
                        metadataCalls++
                        release.await()
                        track
                    },
                    settings = settings,
                    clock = clock,
                )
            val first = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolveMusicTrack(video) }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    resolver.resolveMusicTrack(
                        video.copy(queueSourceTitle = "Playlist", playlistSetVideoId = "occurrence", thumbnail = "artwork"),
                    )
                }
            assertEquals(1, metadataCalls)
            release.complete(Unit)
            val expected = MusicTrackResult.Found(track, map, CounterpartResolutionSource.AUTHORITATIVE)
            assertEquals(expected, first.await())
            assertEquals(expected, second.await())
            assertEquals(expected, resolver.resolveMusicTrack(video))
            assertEquals(1, metadataCalls)
            clock.advance(5 * 60_000L)
            assertEquals(expected, resolver.resolveMusicTrack(video))
            settings.dataSyncId = "another-account"
            assertEquals(expected, resolver.resolveMusicTrack(video))
            settings.contentLanguage = "pl"
            assertEquals(expected, resolver.resolveMusicTrack(video))
            resolver.evictMapping(video)
            assertEquals(expected, resolver.resolveMusicTrack(video))
            resolver.evictMapping(track)
            assertEquals(expected, resolver.resolveMusicTrack(video))
            resolver.clearCache()
            assertEquals(expected, resolver.resolveMusicTrack(video))
            assertEquals(7, metadataCalls)
        }

    @Test
    fun trackSearchCachesSuccessButRetriesErrorsAndMisses() =
        runBlocking {
            val video = song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
            val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
            var calls = 0
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = { null },
                    search = { _, _ ->
                        when (++calls) {
                            1 -> SearchResultPage(emptyList(), onlineSearchFailed = true)
                            2 -> SearchResultPage(emptyList())
                            else -> SearchResultPage(listOf(track))
                        }
                    },
                    settings = Settings(MapSettings()),
                )
            assertEquals(MusicTrackResult.Error, resolver.resolveMusicTrack(video))
            assertEquals(MusicTrackResult.NotFound, resolver.resolveMusicTrack(video))
            assertEquals(MusicTrackResult.Found(track), resolver.resolveMusicTrack(video))
            assertEquals(MusicTrackResult.Found(track), resolver.resolveMusicTrack(video))
            assertEquals(3, calls)
        }

    @Test
    fun clearedPendingTrackLookupCannotRepopulateTheCache() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val video = song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
            var calls = 0
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = { null },
                    search = { _, _ ->
                        calls++
                        release.await()
                        SearchResultPage(listOf(song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")))
                    },
                    settings = Settings(MapSettings()),
                )
            val pending = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolveMusicTrack(video) }
            resolver.clearCache()
            release.complete(Unit)
            assertEquals(MusicTrackResult.Error, pending.await())
            assertTrue(resolver.resolveMusicTrack(video) is MusicTrackResult.Found)
            assertEquals(2, calls)
        }

    @Test
    fun nothingOnMeAlbumTrackMatchesSearchWhenNextHasNoCounterpart() =
        runBlocking {
            val video =
                song(
                    "4CmYE-fbVWc",
                    "The Skinner Brothers - Nothing on Me (Official Music Video)",
                    "The Skinner Brothers",
                    "MUSIC_VIDEO_TYPE_OMV",
                ).copy(duration = 163L)
            val track = song("obGydj8nf7o", "NOTHING ON ME", "The Skinner Brothers", "MUSIC_VIDEO_TYPE_ATV").copy(duration = 160L)
            var nextLookups = 0
            val searches = mutableListOf<Pair<String, SearchFilter>>()
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = {
                        nextLookups++
                        null
                    },
                    search = { query, filter ->
                        searches += query to filter
                        SearchResultPage(listOf(video, track))
                    },
                    songLookup = { error("Search results already contain track metadata") },
                    settings = Settings(MapSettings()),
                )

            assertEquals(MusicTrackResult.Found(track), resolver.resolveMusicTrack(video))
            assertEquals(1, nextLookups)
            assertEquals(listOf("${video.title} The Skinner Brothers" to SearchFilter.SONG), searches)
        }

    @Test
    fun returnedOnlineFailuresAreRetriedWithoutCachingEvenOnWideSearch() =
        runBlocking {
            for (failWide in listOf(false, true)) {
                var calls = 0
                var failed = true
                val resolver =
                    MusicVideoResolver(
                        search = { _, _ ->
                            calls++
                            SearchResultPage(emptyList(), onlineSearchFailed = failed && (!failWide || calls % 2 == 0))
                        },
                        counterpartLookup = { null },
                        songLookup = { null },
                        settings = Settings(MapSettings()),
                    )
                val track = song("TRACK", "Title", "Artist")
                assertEquals(MusicVideoResult.Error, resolver.resolveMusicVideo(track))
                assertEquals(0, resolver.getCacheEntryCount())
                failed = false
                assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track))
                assertEquals(if (failWide) 4 else 3, calls)
            }
        }

    @Test
    fun returnedOnlineTrackFailureIsAnError() =
        runBlocking {
            val resolver =
                MusicVideoResolver(
                    search = { _, _ -> SearchResultPage(emptyList(), onlineSearchFailed = true) },
                    counterpartLookup = { null },
                    songLookup = { null },
                    settings = Settings(MapSettings()),
                )
            assertEquals(MusicTrackResult.Error, resolver.resolveMusicTrack(song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")))
        }

    private fun song(
        id: String,
        title: String,
        artist: String,
        type: String? = null,
        isUploaded: Boolean = false,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            isUploaded = isUploaded,
            thumbnail = null,
            artists = listOf(ArtistTiny(id = null, name = artist)),
            album = null,
            musicVideoType = type,
        )

    @Test
    fun uploadedTrackDoesNotTriggerCounterpartLookup() =
        runBlocking {
            var lookups = 0
            val resolver =
                MusicVideoResolver(
                    search = { _, _ ->
                        lookups++
                        SearchResultPage(emptyList(), null)
                    },
                    counterpartLookup = {
                        lookups++
                        null
                    },
                    songLookup = { null },
                    settings = Settings(MapSettings()),
                )

            assertEquals(
                MusicVideoResult.NotFound,
                resolver.resolveMusicVideo(song("UPLOAD", "Title", "Artist", isUploaded = true)),
            )
            assertEquals(0, lookups)
        }

    private fun resolverWith(
        settings: Settings,
        vararg pages: SearchResultPage,
    ): Pair<MusicVideoResolver, MutableList<Pair<String, SearchFilter>>> {
        val calls = mutableListOf<Pair<String, SearchFilter>>()
        var index = 0
        val resolver =
            MusicVideoResolver(
                search = { query, filter ->
                    calls.add(query to filter)
                    pages.getOrNull(index++) ?: SearchResultPage(emptyList(), null)
                },
                counterpartLookup = { null },
                songLookup = { null },
                settings = settings,
            )
        return resolver to calls
    }

    @Test
    fun searchesWithTitleArtistAndVideoFilter() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")
        val (resolver, calls) =
            resolverWith(settings, SearchResultPage(listOf(omv), null))

        runBlocking {
            val result = resolver.resolveMusicVideo(song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV"))
            assertEquals(MusicVideoResult.Found("MVID"), result)
        }
        assertEquals(1, calls.size)
        assertEquals("Never Gonna Give You Up Rick Astley official video", calls[0].first)
        assertEquals(SearchFilter.VIDEO, calls[0].second)
    }

    @Test
    fun twoPassSearchUpgradesNonOfficialExactMatchToOfficial() {
        val settings = Settings(MapSettings())
        // A later search pass can find the official video after an earlier pass returns a same-title non-official upload.
        val nonOfficial = song("VNON", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO")
        val official = song("VOMV", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")
        val (resolver, calls) =
            resolverWith(
                settings,
                SearchResultPage(listOf(nonOfficial), null),
                SearchResultPage(listOf(official), null),
            )
        val track = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            val result = resolver.resolveMusicVideo(track)
            assertEquals(MusicVideoResult.Found("VOMV"), result)
        }
        assertEquals(2, calls.size)
    }

    @Test
    fun authoritativeCounterpartWinsWithoutSearch() {
        val settings = Settings(MapSettings())
        var searchCalls = 0
        val positionMap = CounterpartPositionMap(listOf(CounterpartSegment(0, 4_492, 10_000)))
        val resolver =
            MusicVideoResolver(
                search = { _, _ ->
                    searchCalls++
                    SearchResultPage(emptyList(), null)
                },
                counterpartLookup = { MusicVideoCounterpart(it.id, "MVID", "MUSIC_VIDEO_TYPE_OMV", positionMap) },
                songLookup = { null },
                settings = settings,
            )

        runBlocking {
            assertEquals(
                MusicVideoResult.Found("MVID", positionMap, CounterpartResolutionSource.AUTHORITATIVE),
                resolver.resolveMusicVideo(song("TRACK", "Title", "Artist")),
            )
        }
        assertEquals(0, searchCalls)
    }

    @Test
    fun persistedCounterpartCacheSkipsLookupAndKeepsPositionMap() {
        val settings = Settings(MapSettings())
        val track = song("TRACK", "Title", "Artist")
        val positionMap = CounterpartPositionMap(listOf(CounterpartSegment(0, 4_492, 10_000)))

        runBlocking {
            MusicVideoResolver(
                counterpartLookup = { MusicVideoCounterpart(it.id, "MVID", "MUSIC_VIDEO_TYPE_OMV", positionMap) },
                songLookup = { null },
                settings = settings,
            ).resolveMusicVideo(track)

            var lookupCalls = 0
            val cached =
                MusicVideoResolver(
                    search = { _, _ -> error("cached counterpart should not search") },
                    counterpartLookup = {
                        lookupCalls++
                        null
                    },
                    songLookup = { null },
                    settings = settings,
                ).resolveMusicVideo(track)

            assertTrue(cached is MusicVideoResult.Found)
            assertEquals("MVID", cached.videoId)
            assertEquals(positionMap, cached.positionMap)
            assertEquals(0, lookupCalls)
        }
    }

    @Test
    fun persistedCounterpartsDoNotCollideForSameMetadata() {
        val settings = Settings(MapSettings())
        val firstTrack = song("TRACK_1", "Title", "Artist")
        val secondTrack = song("TRACK_2", "Title", "Artist")

        runBlocking {
            MusicVideoResolver(
                counterpartLookup = { MusicVideoCounterpart(it.id, "MVID_1", "MUSIC_VIDEO_TYPE_OMV", null) },
                songLookup = { null },
                settings = settings,
            ).resolveMusicVideo(firstTrack)

            var lookupCalls = 0
            val result =
                MusicVideoResolver(
                    counterpartLookup = {
                        lookupCalls++
                        MusicVideoCounterpart(it.id, "MVID_2", "MUSIC_VIDEO_TYPE_OMV", null)
                    },
                    songLookup = { null },
                    settings = settings,
                ).resolveMusicVideo(secondTrack)

            assertEquals("MVID_2", (result as MusicVideoResult.Found).videoId)
            assertEquals(1, lookupCalls)
        }
    }

    @Test
    fun missingAuthoritativeCounterpartFallsBackToSearch() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
        val resolver =
            MusicVideoResolver(
                search = { _, _ -> SearchResultPage(listOf(omv), null) },
                counterpartLookup = { null },
                songLookup = { null },
                settings = settings,
            )

        runBlocking {
            assertEquals(MusicVideoResult.Found("MVID"), resolver.resolveMusicVideo(song("TRACK", "Title", "Artist")))
        }
    }

    @Test
    fun authoritativeTrackCounterpartReturnsMetadataAndReverseMap() {
        val settings = Settings(MapSettings())
        val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
        val map = CounterpartPositionMap(listOf(CounterpartSegment(4_492, 0, 10_000)))
        val resolver =
            MusicVideoResolver(
                search = { _, _ -> SearchResultPage(emptyList(), null) },
                counterpartLookup = { MusicVideoCounterpart(it.id, "TRACK", "MUSIC_VIDEO_TYPE_ATV", map) },
                songLookup = { track },
                settings = settings,
            )

        runBlocking {
            assertEquals(
                MusicTrackResult.Found(track, map, CounterpartResolutionSource.AUTHORITATIVE),
                resolver.resolveMusicTrack(song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")),
            )
        }
    }

    @Test
    fun authoritativeLookupFailureFallsBackToSearch() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
        val resolver =
            MusicVideoResolver(
                search = { _, _ -> SearchResultPage(listOf(omv), null) },
                counterpartLookup = { throw IllegalStateException("next unavailable") },
                songLookup = { null },
                settings = settings,
            )

        runBlocking {
            assertEquals(MusicVideoResult.Found("MVID"), resolver.resolveMusicVideo(song("TRACK", "Title", "Artist")))
        }
    }

    @Test
    fun authoritativeLookupPropagatesCancellation() {
        val settings = Settings(MapSettings())
        val resolver =
            MusicVideoResolver(
                search = { _, _ -> SearchResultPage(emptyList(), null) },
                counterpartLookup = { throw CancellationException("cancelled") },
                songLookup = { null },
                settings = settings,
            )

        runBlocking {
            assertFailsWith<CancellationException> {
                resolver.resolveMusicVideo(song("TRACK", "Title", "Artist"))
            }
        }
    }

    @Test
    fun authoritativeHitCachesBySourceVideoId() {
        val settings = Settings(MapSettings())
        var counterpartCalls = 0
        val resolver =
            MusicVideoResolver(
                search = { _, _ -> SearchResultPage(emptyList(), null) },
                counterpartLookup = {
                    counterpartCalls++
                    MusicVideoCounterpart(it.id, "MVID", "MUSIC_VIDEO_TYPE_OMV", null)
                },
                songLookup = { null },
                settings = settings,
            )
        val track = song("TRACK", "Title", "Artist")
        val expected = MusicVideoResult.Found("MVID", source = CounterpartResolutionSource.AUTHORITATIVE)

        runBlocking {
            assertEquals(expected, resolver.resolveMusicVideo(track))
            assertEquals(expected, resolver.resolveMusicVideo(track.copy(title = "Renamed")))
        }
        assertEquals(1, counterpartCalls)
    }

    @Test
    fun cachesResultWithoutSearchingAgain() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")
        val (resolver, calls) =
            resolverWith(settings, SearchResultPage(listOf(omv), null))
        val track = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            assertEquals(MusicVideoResult.Found("MVID"), resolver.resolveMusicVideo(track))
            assertEquals(MusicVideoResult.Found("MVID"), resolver.resolveMusicVideo(track))
        }
        assertEquals(1, calls.size)
    }

    @Test
    fun cachesNegativeResultAfterSuccessfulEmptySearch() {
        val settings = Settings(MapSettings())
        val (resolver, calls) = resolverWith(settings, SearchResultPage(emptyList(), null))
        val track = song("TRACK", "Obscure Track", "Obscure Artist", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track))
            assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track))
        }
        assertEquals(2, calls.size)
    }

    @Test
    fun doesNotCacheWhenSearchThrows() {
        val settings = Settings(MapSettings())
        var searchCalls = 0
        val resolver =
            MusicVideoResolver(
                search = { _, _ ->
                    searchCalls++
                    throw RuntimeException("network down")
                },
                counterpartLookup = { null },
                songLookup = { null },
                settings = settings,
            )
        val track = song("TRACK", "Obscure Track", "Obscure Artist", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            assertEquals(MusicVideoResult.Error, resolver.resolveMusicVideo(track))
            assertEquals(MusicVideoResult.Error, resolver.resolveMusicVideo(track))
        }
        assertEquals(2, searchCalls)
    }

    @Test
    fun usesTitleOnlyQueryWhenNoArtist() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Instrumental Piece", "", "MUSIC_VIDEO_TYPE_OMV")
        val (resolver, calls) =
            resolverWith(settings, SearchResultPage(listOf(omv), null))

        runBlocking {
            val result = resolver.resolveMusicVideo(song("TRACK", "Instrumental Piece", "", "MUSIC_VIDEO_TYPE_ATV"))
            assertEquals(MusicVideoResult.Found("MVID"), result)
        }
        assertEquals("Instrumental Piece official video", calls[0].first)
    }

    @Test
    fun propagatesCancellation() {
        val settings = Settings(MapSettings())
        val resolver =
            MusicVideoResolver(
                search = { _, _ -> throw CancellationException("cancelled") },
                counterpartLookup = { null },
                songLookup = { null },
                settings = settings,
            )

        runBlocking {
            assertFailsWith<CancellationException> {
                resolver.resolveMusicVideo(song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV"))
            }
        }
    }

    @Test
    fun persistsCacheAcrossInstances() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")
        val track = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")
        var searchCalls = 0
        val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
            searchCalls++
            SearchResultPage(listOf(omv), null)
        }

        runBlocking {
            MusicVideoResolver(
                search = search,
                counterpartLookup = { null },
                songLookup = { null },
                settings = settings,
            ).resolveMusicVideo(track)
            val reloaded = MusicVideoResolver(search = search, counterpartLookup = { null }, songLookup = { null }, settings = settings)
            assertEquals(MusicVideoResult.Found("MVID"), reloaded.resolveMusicVideo(track))
        }
        assertEquals(1, searchCalls)
    }

    @Test
    fun reSearchesAfterCacheEntryExpires() {
        val settings = Settings(MapSettings())
        val fakeClock = FakeClock(1_000_000L)
        val omv = song("MVID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")
        var searchCalls = 0
        val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
            searchCalls++
            SearchResultPage(listOf(omv), null)
        }
        val track = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            val resolver =
                MusicVideoResolver(
                    search = search,
                    counterpartLookup = { null },
                    songLookup = { null },
                    settings = settings,
                    clock = fakeClock,
                )
            resolver.resolveMusicVideo(track)
            fakeClock.advance(FOUND_TTL_PLUS_ONE_DAY)
            assertEquals(MusicVideoResult.Found("MVID"), resolver.resolveMusicVideo(track))
        }
        assertEquals(2, searchCalls)
    }

    @Test
    fun negativeCacheExpiresBeforeFoundCache() {
        val settings = Settings(MapSettings())
        val fakeClock = FakeClock(1_000_000L)
        var searchCalls = 0
        val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
            searchCalls++
            SearchResultPage(emptyList(), null)
        }
        val track = song("TRACK", "Obscure Track", "Obscure Artist", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            val resolver =
                MusicVideoResolver(
                    search = search,
                    counterpartLookup = { null },
                    songLookup = { null },
                    settings = settings,
                    clock = fakeClock,
                )
            assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track))
            fakeClock.advance(NEGATIVE_TTL_PLUS_ONE_DAY)
            assertEquals(MusicVideoResult.NotFound, resolver.resolveMusicVideo(track))
        }
        assertEquals(4, searchCalls)
    }

    @Test
    fun evictsOldestEntriesBeyondCapacity() {
        val settings = Settings(MapSettings())
        val fakeClock = FakeClock(1_000_000L)
        var searchCalls = 0
        val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
            searchCalls++
            SearchResultPage(listOf(song("MVID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")), null)
        }

        runBlocking {
            val resolver =
                MusicVideoResolver(
                    search = search,
                    counterpartLookup = { null },
                    songLookup = { null },
                    settings = settings,
                    clock = fakeClock,
                )
            for (i in 1..MUSIC_VIDEO_CACHE_CAPACITY) {
                resolver.resolveMusicVideo(song("T$i", "Track $i", "Artist $i", "MUSIC_VIDEO_TYPE_ATV"))
            }
            resolver.resolveMusicVideo(song("T-EXTRA", "Track Extra", "Artist Extra", "MUSIC_VIDEO_TYPE_ATV"))
            assertEquals((MUSIC_VIDEO_CACHE_CAPACITY + 1) * 2, searchCalls)
            resolver.resolveMusicVideo(song("T1", "Track 1", "Artist 1", "MUSIC_VIDEO_TYPE_ATV"))
            assertEquals((MUSIC_VIDEO_CACHE_CAPACITY + 2) * 2, searchCalls)
        }
    }

    @Test
    fun cacheKeyIgnoresArtistOrder() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Collab Track", "Band", "MUSIC_VIDEO_TYPE_OMV")
        var searchCalls = 0
        val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
            searchCalls++
            SearchResultPage(listOf(omv), null)
        }
        val trackAB =
            SongItem(
                id = "T1",
                title = "Collab Track",
                thumbnail = null,
                artists = listOf(ArtistTiny(null, "Artist A"), ArtistTiny(null, "Artist B")),
                album = null,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            )
        val trackBA =
            SongItem(
                id = "T1",
                title = "Collab Track",
                thumbnail = null,
                artists = listOf(ArtistTiny(null, "Artist B"), ArtistTiny(null, "Artist A")),
                album = null,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            )
        val trackAOnly =
            SongItem(
                id = "T3",
                title = "Collab Track",
                thumbnail = null,
                artists = listOf(ArtistTiny(null, "Artist A")),
                album = null,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            )

        runBlocking {
            val resolver = MusicVideoResolver(search = search, counterpartLookup = { null }, songLookup = { null }, settings = settings)
            resolver.resolveMusicVideo(trackAB)
            resolver.resolveMusicVideo(trackBA)
            resolver.resolveMusicVideo(trackAOnly)
        }
        assertEquals(4, searchCalls)
    }

    @Test
    fun deduplicatesConcurrentLookupsForSameKey() {
        val settings = Settings(MapSettings())
        val startGate = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        var counterpartCalls = 0
        val track = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            val resolver =
                MusicVideoResolver(
                    search = { _, _ -> error("authoritative result should skip search") },
                    counterpartLookup = {
                        counterpartCalls++
                        startGate.complete(Unit)
                        releaseGate.await()
                        MusicVideoCounterpart(it.id, "MVID", "MUSIC_VIDEO_TYPE_OMV", null)
                    },
                    songLookup = { null },
                    settings = settings,
                )
            val first = async { resolver.resolveMusicVideo(track) }
            startGate.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolveMusicVideo(track) }
            releaseGate.complete(Unit)
            val expected = MusicVideoResult.Found("MVID", source = CounterpartResolutionSource.AUTHORITATIVE)
            assertEquals(expected, first.await())
            assertEquals(expected, second.await())
        }
        assertEquals(1, counterpartCalls)
    }

    @Test
    fun clearCacheCancelsInFlightLookups() {
        val settings = Settings(MapSettings())
        val omv = song("MVID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")
        val startGate = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        var searchCalls = 0
        val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
            searchCalls++
            startGate.complete(Unit)
            releaseGate.await()
            SearchResultPage(listOf(omv), null)
        }
        val track = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")

        runBlocking {
            val resolver = MusicVideoResolver(search = search, counterpartLookup = { null }, songLookup = { null }, settings = settings)
            val first = async { resolver.resolveMusicVideo(track) }
            startGate.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolveMusicVideo(track) }
            resolver.clearCache()
            releaseGate.complete(Unit)
            assertEquals(MusicVideoResult.Error, second.await())
            assertEquals(MusicVideoResult.Error, first.await())
            assertEquals(0, resolver.getCacheEntryCount())
            assertEquals("{}", settings.musicVideoCacheJson)
        }
    }

    @Test
    fun negativeSearchCacheStillChecksAndPersistsAlbumMatch() {
        val settings = Settings(MapSettings())
        val track =
            SongItem(
                id = "AUDIO_ID",
                title = "Track Title",
                thumbnail = null,
                artists = listOf(ArtistTiny(null, "Artist Name")),
                album = AlbumTiny("ALBUM_ID", "Album Title"),
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            )
        val video = track.copy(id = "VIDEO_ID", musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val albumPage =
            AlbumPage(
                album = AlbumItem("ALBUM_ID", "Album Title", null, listOf(ArtistTiny(null, "Artist Name")), "2022"),
                songs = listOf(video),
            )

        runBlocking {
            MusicVideoResolver(
                search = { _, _ -> SearchResultPage(emptyList(), null) },
                counterpartLookup = { null },
                albumLookup = { null },
                songLookup = { null },
                settings = settings,
            ).resolveMusicVideo(track)

            val albumResult =
                MusicVideoResolver(
                    search = { _, _ -> error("album match should skip search") },
                    counterpartLookup = { null },
                    albumLookup = { albumPage },
                    songLookup = { null },
                    settings = settings,
                ).resolveMusicVideo(track)
            assertEquals("VIDEO_ID", (albumResult as MusicVideoResult.Found).videoId)

            val persistedResult =
                MusicVideoResolver(
                    search = { _, _ -> error("persisted album match should skip search") },
                    counterpartLookup = { error("persisted album match should skip lookup") },
                    albumLookup = { error("persisted album match should skip album lookup") },
                    songLookup = { null },
                    settings = settings,
                ).resolveMusicVideo(track)
            assertEquals("VIDEO_ID", (persistedResult as MusicVideoResult.Found).videoId)
        }
    }

    @Test
    fun clearCacheClearsAllEntriesAndSettings() {
        val settings = Settings(MapSettings())
        val omv1 = song("MVID1", "Song 1", "Artist 1", "MUSIC_VIDEO_TYPE_OMV")
        val omv2 = song("MVID2", "Song 2", "Artist 2", "MUSIC_VIDEO_TYPE_OMV")
        val track1 = song("TRACK1", "Song 1", "Artist 1", "MUSIC_VIDEO_TYPE_ATV")
        val track2 = song("TRACK2", "Song 2", "Artist 2", "MUSIC_VIDEO_TYPE_ATV")
        val search: suspend (String, SearchFilter) -> SearchResultPage = { query, _ ->
            if (query.contains("Song 1")) {
                SearchResultPage(listOf(omv1), null)
            } else {
                SearchResultPage(listOf(omv2), null)
            }
        }

        runBlocking {
            val resolver = MusicVideoResolver(search = search, counterpartLookup = { null }, songLookup = { null }, settings = settings)
            resolver.resolveMusicVideo(track1)
            resolver.resolveMusicVideo(track2)
            assertEquals(2, resolver.getCacheEntryCount())
            assertTrue(resolver.getCacheSizeBytes() > 0)
            assertNotEquals("{}", settings.musicVideoCacheJson)

            resolver.clearCache()
            assertEquals(0, resolver.getCacheEntryCount())
            assertEquals("{}", settings.musicVideoCacheJson)
        }
    }

    @Test
    fun evictMappingRemovesCachedEntryAndAuthoritative() {
        val settings = Settings(MapSettings())
        val track = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")
        val omv = song("MVID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV")
        var counterpartCalls = 0
        var searchCalls = 0
        val counterpartLookup: suspend (SongItem) -> MusicVideoCounterpart? = {
            counterpartCalls++
            if (counterpartCalls == 1) {
                MusicVideoCounterpart("TRACK", "MVID", "MUSIC_VIDEO_TYPE_OMV", null)
            } else {
                null
            }
        }
        val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
            searchCalls++
            SearchResultPage(listOf(omv), null)
        }

        runBlocking {
            val resolver =
                MusicVideoResolver(search = search, counterpartLookup = counterpartLookup, songLookup = { null }, settings = settings)
            val first = resolver.resolveMusicVideo(track)
            assertEquals(MusicVideoResult.Found("MVID", null, CounterpartResolutionSource.AUTHORITATIVE), first)
            assertEquals(1, resolver.getCacheEntryCount())

            resolver.evictMapping(track)
            assertEquals(0, resolver.getCacheEntryCount())

            val second = resolver.resolveMusicVideo(track)
            assertEquals(MusicVideoResult.Found("MVID", null, CounterpartResolutionSource.HEURISTIC), second)
            assertEquals(1, searchCalls)
        }
    }

    @Test
    fun negativeCacheSurvivesRestartUntilItsOriginalExpiry() =
        runBlocking {
            val settings = Settings(MapSettings())
            val clock = FakeClock(1_765_000_000_000L)
            var calls = 0
            val search: suspend (String, SearchFilter) -> SearchResultPage = { _, _ ->
                calls++
                SearchResultPage(emptyList(), null)
            }

            fun resolver() =
                MusicVideoResolver(search = search, counterpartLookup = {
                    null
                }, songLookup = { null }, settings = settings, clock = clock)
            val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
            assertEquals(MusicVideoResult.NotFound, resolver().resolveMusicVideo(track))
            assertEquals(2, calls)
            clock.advance(60_000)
            assertEquals(MusicVideoResult.NotFound, resolver().resolveMusicVideo(track))
            assertEquals(2, calls)
            clock.advance(NEGATIVE_TTL_PLUS_ONE_DAY)
            resolver().resolveMusicVideo(track)
            assertEquals(4, calls)
        }

    @Test
    fun cancellingOwnerDoesNotCancelWaitingLookup() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resolver =
                MusicVideoResolver(
                    search = { _, _ ->
                        started.complete(Unit)
                        release.await()
                        SearchResultPage(emptyList(), null)
                    },
                    counterpartLookup = { null },
                    songLookup = { null },
                    settings = Settings(MapSettings()),
                )
            val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
            val owner = async { resolver.resolveMusicVideo(track) }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolveMusicVideo(track) }
            owner.cancel()
            release.complete(Unit)
            assertEquals(MusicVideoResult.NotFound, waiter.await())
        }

    @Test
    fun authoritativeCounterpartsExpireAndStayBounded() =
        runBlocking {
            val clock = FakeClock(1_765_000_000_000L)
            var calls = 0
            val track = song("TRACK", "Title", "Artist", "MUSIC_VIDEO_TYPE_ATV")
            val resolver =
                MusicVideoResolver(
                    counterpartLookup = {
                        calls++
                        MusicVideoCounterpart(it.id, track.id, "MUSIC_VIDEO_TYPE_ATV", null)
                    },
                    songLookup = { track },
                    settings = Settings(MapSettings()),
                    clock = clock,
                )
            val video = song("VIDEO", "Title", "Artist", "MUSIC_VIDEO_TYPE_OMV")
            resolver.resolveMusicTrack(video)
            resolver.resolveMusicTrack(video)
            assertEquals(1, calls)
            clock.advance(FOUND_TTL_PLUS_ONE_DAY)
            resolver.resolveMusicTrack(video)
            assertEquals(2, calls)
            repeat(200) {
                clock.advance(1)
                resolver.resolveMusicTrack(video.copy(id = "video-$it"))
            }
            resolver.resolveMusicTrack(video)
            assertEquals(203, calls)
        }

    private class FakeClock(
        private var nowMs: Long,
    ) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)

        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    companion object {
        private const val FOUND_TTL_PLUS_ONE_DAY = 31L * 24 * 60 * 60 * 1000
        private const val NEGATIVE_TTL_PLUS_ONE_DAY = 8L * 24 * 60 * 60 * 1000
        private const val MUSIC_VIDEO_CACHE_CAPACITY = 200
    }
}
