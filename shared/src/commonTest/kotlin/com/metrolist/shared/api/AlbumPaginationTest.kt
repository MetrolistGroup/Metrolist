package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.model.canSwitchToAudioTrack
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.AlbumViewModel
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AlbumPaginationTest {
    @Test
    fun missingAlbumPageShowsAnErrorAndRetryAcceptsEmptyOrPopulatedAlbums() =
        runBlocking {
            for (empty in listOf(false, true)) {
                var fail = true
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val client =
                    HttpClient(
                        MockEngine { request ->
                            if (fail) error("Offline")
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            respond(
                                if (body["browseId"]?.jsonPrimitive?.content == "MPREb_test") {
                                    initial
                                } else if (empty) {
                                    "{}"
                                } else {
                                    playlist
                                },
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
                try {
                    val viewModel = AlbumViewModel("MPREb_test", scope, YouTubeRepository(InnerTube(client, retryDelay = {})))
                    withTimeout(5_000L) {
                        while (viewModel.isLoading.value || viewModel.errorMessage.value == null) delay(10L)
                    }
                    assertNull(viewModel.albumPage.value)
                    assertNotNull(viewModel.errorMessage.value)
                    fail = false
                    viewModel.refresh()
                    withTimeout(5_000L) {
                        while (viewModel.isLoading.value || viewModel.albumPage.value == null) delay(10L)
                    }
                    assertNull(viewModel.errorMessage.value)
                    assertEquals(empty, viewModel.songs.value.isEmpty())
                } finally {
                    scope.cancel()
                    client.close()
                }
            }
        }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun selectionPlaybackDoesNotCarryTheAlbumsPendingPage() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        val response =
                            when (requests++) {
                                0 -> initial
                                1 -> playlist
                                else -> continuation
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val album = assertNotNull(repository.getAlbum("MPREb_test", loadAllPages = false))
                val selected = listOf(album.songs.first())
                val queue = MusicQueue()
                queue.setQueue(selected, 0)
                val selectedCursor = album.playlistPageForPlayback(selectedOnly = true)
                selectedCursor?.let { queue.appendPage(repository.continuePlaylistPage(it).songs.drop(selected.size)) }
                assertEquals(selected, queue.items.value)
                assertEquals(2, requests)
                val complete = repository.continuePlaylistPage(assertNotNull(album.playlistPageForPlayback(selectedOnly = false)))
                assertEquals(2, complete.songs.size)
                assertEquals(3, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun albumUsesCanonicalTrackIdsAndDefersPlaylistContinuation() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val response =
                            when (requests++) {
                                0 -> {
                                    assertEquals("MPREb_test", body["browseId"]?.jsonPrimitive?.content)
                                    initial
                                }

                                1 -> {
                                    assertEquals("VLOLAK5uy_test", body["browseId"]?.jsonPrimitive?.content)
                                    playlist
                                }

                                else -> {
                                    assertEquals("page-2", body["continuation"]?.jsonPrimitive?.content)
                                    continuation
                                }
                            }
                        respond(
                            response,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val album = assertNotNull(repository.getAlbum("MPREb_test", loadAllPages = false))
                assertEquals(2, requests, "Load the canonical playlist without fetching its continuation yet")
                assertEquals(listOf("Ei8UnOPJX7w"), album.songs.map { it.id })
                assertEquals(album.songs, assertNotNull(album.playlistPage).songs)
                assertFalse(
                    album.songs.single().canSwitchToAudioTrack,
                    "Album audio must not be replaced by a counterpart during streaming",
                )
                assertEquals(2, album.album.songCount, "Use the reported total, not the loaded page size")
                assertEquals(
                    "MPREb_test",
                    album.songs
                        .single()
                        .album
                        ?.id,
                )
                assertEquals(1, album.songs.single().trackNumber)
                assertEquals(
                    "Artist",
                    album.songs
                        .single()
                        .artists
                        .single()
                        .name,
                )
                assertNull(album.songs.single().totalTrackCount)
                assertEquals("album-cover", album.songs.single().thumbnail)
                val next = repository.continuePlaylistPage(assertNotNull(album.playlistPage))
                assertEquals(3, requests)
                assertEquals(listOf("Ei8UnOPJX7w", "second"), next.songs.map { it.id })
                assertEquals(listOf(1, 2), next.songs.map { it.trackNumber })
                assertEquals(listOf(2, 2), next.songs.map { it.totalTrackCount })
                assertEquals(listOf("MPREb_test", "MPREb_test"), next.songs.map { it.album?.id })
                assertEquals(listOf("Artist", "Guest"), next.songs.map { it.artists.single().name })
                assertEquals(listOf("album-cover", "album-cover"), next.songs.map { it.thumbnail })
                assertEquals(1, album.songs.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun firstAlbumPageRemainsUsableWhenTheContinuationFails() =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    val response =
                        when (requests++) {
                            0 -> initial
                            1 -> playlist
                            else -> error("Continuation unavailable")
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }.use { client ->
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val page = assertNotNull(repository.getAlbum("MPREb_test", loadAllPages = false))
                assertEquals(listOf("Ei8UnOPJX7w"), page.songs.map { it.id })
                assertFailsWith<IllegalStateException> { repository.completePlaylistPage(assertNotNull(page.playlistPage)) }
                assertEquals("MPREb_test", page.album.id)
                assertEquals(listOf("Ei8UnOPJX7w"), page.songs.map { it.id })
            }
        }

    @Test
    fun fullAlbumConsumersStillReceiveAllTracks() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            when (requests++) {
                                0 -> initial
                                1 -> playlist
                                else -> continuation
                            },
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val album = assertNotNull(YouTubeRepository(InnerTube(client)).getAlbum("MPREb_test"))
                assertEquals(3, requests)
                assertEquals(listOf("Ei8UnOPJX7w", "second"), album.songs.map { it.id })
                assertEquals(album.songs, assertNotNull(album.playlistPage).songs)
            } finally {
                client.close()
            }
        }

    @Test
    fun albumHeaderParserRetainsCanonicalPlaylistAndReportedCount() {
        val parsed =
            assertNotNull(YouTubeAlbumParser.parseAlbumHeader(repositoryJson.decodeFromString<BrowseResponse>(initial), "MPREb_test"))
        assertEquals("OLAK5uy_test", parsed.item.playlistId)
        assertEquals(2, parsed.item.songCount)
        assertEquals(
            "Artist",
            parsed.item.artists
                .single()
                .name,
        )
        assertNull(YouTubeAlbumParser.parseAlbumHeader(repositoryJson.decodeFromString<BrowseResponse>("{}"), "missing"))
    }

    private val initial = """{
      "microformat":{"microformatDataRenderer":{"urlCanonical":"https://www.youtube.com/playlist?list=OLAK5uy_test"}},
      "header":{"musicDetailHeaderRenderer":{
        "title":{"runs":[{"text":"Album"}]},
        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"album-cover"}]}}},
        "secondSubtitle":{"runs":[{"text":"2 songs"},{"text":" • "},{"text":"8 minutes"}]},
        "straplineTextOne":{"runs":[{"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UCtest"}}}]}
      }},
      "contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{
        "musicPlaylistShelfRenderer":{
          "contents":[{"musicResponsiveListItemRenderer":{
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First"}]}}}],
            "playlistItemData":{"videoId":"YQHsXMglC9A"},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"YQHsXMglC9A","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}}}
          }}],
          "continuations":[{"nextContinuationData":{"continuation":"album-video-page-2"}}]
        }
      }]}}}}
    }"""

    private val playlist = """{
      "contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{
        "musicPlaylistShelfRenderer":{
          "contents":[{"musicResponsiveListItemRenderer":{
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First"}]}}}],
            "playlistItemData":{"videoId":"Ei8UnOPJX7w"},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"Ei8UnOPJX7w","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}}
          }}],
          "continuations":[{"nextContinuationData":{"continuation":"page-2"}}]
        }
      }]}}}}
    }"""

    private val continuation = """{
      "continuationContents":{"musicPlaylistShelfContinuation":{"contents":[{"musicResponsiveListItemRenderer":{
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Second"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Guest","navigationEndpoint":{"browseEndpoint":{"browseId":"UCguest"}}}]}}}],
        "playlistItemData":{"videoId":"second"}
      }}]}}
    }"""
}
