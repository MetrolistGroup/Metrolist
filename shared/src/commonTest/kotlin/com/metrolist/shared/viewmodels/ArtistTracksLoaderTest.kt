package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.ArtistViewModel.ArtistUiState
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtistTracksLoaderTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun visibleMetadataIsRequestedOnceAndUpdatesEveryOccurrenceWithoutReplacingTitles() =
        runTest {
            var requests = 0
            val client =
                client {
                    requests++
                    """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{"videoId":"song","title":{"runs":[{"text":"Remote title"}]},"lengthText":{"runs":[{"text":"3:20"}]}}}}]}"""
                }
            val job = Job()
            val scope = CoroutineScope(coroutineContext + job)
            try {
                val song = SongItem("song", "Displayed title", null, emptyList(), null)
                val known = song.copy(id = "known", duration = 45)
                val otherSongs = listOf(known)
                val sections = mapOf("Songs" to listOf(song, known), "Videos" to listOf(song), "Other" to otherSongs)
                val state = MutableStateFlow(ArtistUiState(sections = sections, unfilteredSections = sections))
                val repository = YouTubeRepository(InnerTube(client))
                val loader = ArtistTracksLoader("artist", scope, state) { repository }
                loader.loadItemMetadata(listOf(song, known))
                loader.loadItemMetadata(listOf(song))
                job.children.toList().forEach { it.join() }
                assertEquals(1, requests)
                for (section in listOf("Songs", "Videos")) {
                    val updated =
                        state.value.sections
                            .getValue(section)
                            .first() as SongItem
                    assertEquals(200L, updated.duration)
                    assertEquals("Displayed title", updated.title)
                }
                assertSame(otherSongs, state.value.sections.getValue("Other"))
                assertSame(
                    known,
                    state.value.sections
                        .getValue("Songs")
                        .last(),
                )
            } finally {
                job.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun durationUpdatesPreserveUnchangedSectionsAndUnknownResponses() {
        val song = SongItem("song", "Title", null, emptyList(), null)
        val known = song.copy(id = "known", duration = 45)
        val album = AlbumItem("song", "Album", null, emptyList())
        val albums = listOf(album)
        val sections = mapOf("Songs" to listOf(song, known), "Albums" to albums)
        assertSame(sections, updateArtistSongDurations(sections, emptyMap()))
        assertSame(sections, updateArtistSongDurations(sections, mapOf("missing" to 90, "known" to 45)))
        val updated = updateArtistSongDurations(sections, mapOf("song" to 200))
        assertSame(albums, updated.getValue("Albums"))
        assertSame(known, updated.getValue("Songs").last())
        assertEquals(song.copy(duration = 200), updated.getValue("Songs").first())
    }

    @Test
    fun loadedDurationsStillApplyTheShortSongFilter() {
        val song = SongItem("song", "Title", null, emptyList(), null)
        Settings.shared.hideShortSongs = true
        Settings.shared.minimumSongDurationSeconds = 60
        val sections = mapOf("Songs" to listOf(song))
        assertEquals(sections, visibleArtistSections(sections, "artist"))
        assertTrue(visibleArtistSections(updateArtistSongDurations(sections, mapOf("song" to 30)), "artist").isEmpty())
    }

    @Test
    fun resetDiscardsInFlightMetadataFromThePreviousPage() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                client {
                    started.complete(Unit)
                    release.await()
                    """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{"videoId":"song","title":{"runs":[{"text":"Old page"}]},"lengthText":{"runs":[{"text":"3:20"}]}}}}]}"""
                }
            val job = Job()
            val scope = CoroutineScope(coroutineContext + job)
            try {
                val song = SongItem("song", "Song", null, emptyList(), null)
                val sections = mapOf("Songs" to listOf(song))
                val initial = ArtistUiState(sections = sections, unfilteredSections = sections)
                val state = MutableStateFlow(initial)
                val repository = YouTubeRepository(InnerTube(client))
                val loader = ArtistTracksLoader("artist", scope, state) { repository }
                loader.loadItemMetadata(listOf(song))
                started.await()
                assertEquals(setOf("song"), loader.reset())
                release.complete(Unit)
                job.children.toList().forEach { it.join() }
                assertEquals(initial, state.value)
            } finally {
                job.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun failedCarouselCanRetryAndAppendsNewSongsOnlyOnce() =
        runTest {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler {
                                requests++
                                if (requests == 1) {
                                    respond("{}", HttpStatusCode.Forbidden)
                                } else {
                                    val rows =
                                        listOf("first", "second").joinToString { id ->
                                            """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id","navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}}}]}}}]}}"""
                                        }
                                    respond(
                                        """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[$rows]}}]}}}""",
                                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                }
                            }
                        },
                    ),
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val job = Job()
            val scope = CoroutineScope(coroutineContext + job)
            try {
                val first = SongItem("first", "First", null, emptyList(), null)
                val sections = mapOf("Songs" to listOf(first))
                val state =
                    MutableStateFlow(
                        ArtistUiState(
                            sections = sections,
                            unfilteredSections = sections,
                            sectionBrowseIds = mapOf("Songs" to ("VLsongs" to null)),
                        ),
                    )
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val loader = ArtistTracksLoader("artist", scope, state) { repository }
                loader.loadSection("Songs")
                loader.loadSection("Songs")
                job.children.toList().forEach { it.join() }
                assertEquals(1, requests)
                assertTrue("Songs" in state.value.failedSections)
                assertTrue(state.value.loadingSections.isEmpty())
                loader.loadSection("Songs")
                job.children.toList().forEach { it.join() }
                assertEquals(
                    listOf("first", "second"),
                    state.value.sections
                        .getValue("Songs")
                        .map { it.id },
                )
                assertTrue(state.value.failedSections.isEmpty())
                assertFalse("Songs" in state.value.loadableSections)
                loader.loadSection("Songs")
                assertEquals(2, requests)
            } finally {
                job.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun carouselAppendsAlbumsAndSinglesInOrderWithoutRepeatingThePreview() =
        runTest {
            for (type in listOf("Album", "Single")) {
                val client =
                    client {
                        val rows =
                            listOf("MPREbpreview", "MPREbnew", "MPREbnew").joinToString { id ->
                                """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$id"}]},"subtitle":{"runs":[{"text":"$type"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}}"""
                            }
                        """{"contents":{"sectionListRenderer":{"contents":[{"gridRenderer":{"items":[$rows]}}]}}}"""
                    }
                val job = Job()
                try {
                    val preview = AlbumItem("MPREbpreview", "Preview", null, emptyList(), type = type)
                    val sections = mapOf(type to listOf(preview))
                    val state =
                        MutableStateFlow(
                            ArtistUiState(
                                sections = sections,
                                unfilteredSections = sections,
                                sectionBrowseIds = mapOf(type to ("releases" to null)),
                            ),
                        )
                    val loader =
                        ArtistTracksLoader("artist", CoroutineScope(coroutineContext + job), state) {
                            YouTubeRepository(InnerTube(client))
                        }
                    loader.loadSection(type)
                    job.children.toList().forEach { it.join() }
                    assertEquals(
                        listOf("MPREbpreview", "MPREbnew"),
                        state.value.sections
                            .getValue(type)
                            .map { it.id },
                    )
                    assertTrue(
                        state.value.sections
                            .getValue(type)
                            .all { it is AlbumItem },
                    )
                    assertFalse(type in state.value.loadableSections)
                } finally {
                    job.cancelAndJoin()
                    client.close()
                }
            }
        }

    private fun TestScope.client(body: suspend () -> String) =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        respond(body(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            ),
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
}
