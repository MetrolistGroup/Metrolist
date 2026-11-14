package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class SearchSongAlbumTest {
    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun resolvesAnAlbumOmittedFromSearchTracksWithoutChangingTheSearchRow() =
        runBlocking {
            val song = searchSong()
            assertNull(song.album)
            val requests = mutableListOf<String>()
            val client =
                client { path ->
                    requests += path
                    if (path.endsWith("get_queue")) queueResponse("track", albumRuns) else "{}"
                }
            try {
                val album = YouTubeRepository(InnerTube(client)).resolveSongAlbum(song)
                assertEquals(AlbumTiny("MPREbalbum", "Black Panther: The Album"), album)
                assertEquals(listOf("/youtubei/v1/next", "/youtubei/v1/music/get_queue"), requests)
                assertEquals("track", song.id)
                assertEquals(listOf("Kendrick Lamar", "SZA"), song.artists.map { it.name })
                assertNull(song.album)
            } finally {
                client.close()
            }
        }

    @Test
    fun preservesKnownAlbumsAndSkipsLocalUploadsAndEpisodes() =
        runBlocking {
            val knownAlbum = AlbumTiny("MPREbknown", "Known album")
            var requests = 0
            val client =
                client {
                    requests++
                    "{}"
                }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                assertEquals(knownAlbum, repository.resolveSongAlbum(searchSong().copy(album = knownAlbum)))
                assertNull(repository.resolveSongAlbum(searchSong().copy(isLocal = true)))
                assertNull(repository.resolveSongAlbum(searchSong().copy(isUploaded = true)))
                assertNull(repository.resolveSongAlbum(searchSong().copy(podcastId = "podcast")))
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun preservesTheExistingNextLookupWithoutRequestingQueueMetadata() =
        runBlocking {
            val requests = mutableListOf<String>()
            val client =
                client { path ->
                    requests += path
                    if (path.endsWith("get_queue")) queueResponse("track", "[]") else nextResponse("track")
                }
            try {
                assertEquals("MPREbalbum", YouTubeRepository(InnerTube(client)).resolveSongAlbum(searchSong())?.id)
                assertEquals(listOf("/youtubei/v1/next"), requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun rejectsMetadataForAnotherSongAndPreservesAnUnlinkedAlbum() =
        runBlocking {
            val client =
                client { path ->
                    if (path.endsWith("get_queue")) queueResponse("other", albumRuns) else nextResponse("other")
                }
            try {
                val album = AlbumTiny(null, "Unlinked album")
                assertEquals(album, YouTubeRepository(InnerTube(client)).resolveSongAlbum(searchSong().copy(album = album)))
            } finally {
                client.close()
            }
        }

    @Test
    fun replacesPlaylistAndVideoIdsWithAnAlbumBrowseId() =
        runBlocking {
            for (invalidId in listOf("VLPLcollection", "OLAK5uy_playlist", "PLplaylist", "track")) {
                val requests = mutableListOf<String>()
                client { path ->
                    requests += path
                    nextResponse("track")
                }.use { client ->
                    val album =
                        YouTubeRepository(InnerTube(client)).resolveSongAlbum(
                            searchSong().copy(album = AlbumTiny(invalidId, "Stale album")),
                        )
                    assertEquals("MPREbalbum", album?.id)
                    assertEquals(listOf("/youtubei/v1/next"), requests)
                }
            }
        }

    @Test
    fun unresolvedInvalidIdsCannotOpenAnAlbumPage() =
        runBlocking {
            client { "{}" }.use { client ->
                assertNull(
                    YouTubeRepository(InnerTube(client)).resolveSongAlbum(
                        searchSong().copy(album = AlbumTiny("VLPLcollection", "Stale album")),
                    ),
                )
            }
        }

    @Test
    fun propagatesCancellation() =
        runBlocking<Unit> {
            val client = client { throw CancellationException("Menu closed") }
            try {
                assertFailsWith<CancellationException> { YouTubeRepository(InnerTube(client)).resolveSongAlbum(searchSong()) }
            } finally {
                client.close()
            }
        }

    private fun searchSong(): SongItem =
        assertIs<SongItem>(
            YouTubeSearchParser
                .parseSearchResponse(
                    Json
                        .parseToJsonElement(
                            """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
                      "title":{"runs":[{"text":"Tracks"}]},
                      "contents":[{"musicResponsiveListItemRenderer":{
                        "flexColumns":[
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"All The Stars"}]}}},
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                            {"text":"Kendrick Lamar","navigationEndpoint":{"browseEndpoint":{"browseId":"UCkendrick"}}},
                            {"text":", "},{"text":"SZA","navigationEndpoint":{"browseEndpoint":{"browseId":"UCsza"}}},
                            {"text":" • "},{"text":"1.3B plays"}
                          ]}}}
                        ],
                        "navigationEndpoint":{"watchEndpoint":{"videoId":"track"}}
                      }}]
                    }}]}}}""",
                        ).jsonObject,
                ).single(),
        )

    private fun client(response: (String) -> String): HttpClient =
        HttpClient(
            MockEngine { request ->
                respond(response(request.url.encodedPath), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    private fun queueResponse(
        id: String,
        runs: String,
    ): String =
        """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{
          "videoId":"$id","title":{"runs":[{"text":"All The Stars"}]},"longBylineText":{"runs":$runs}
        }}}]}"""

    private fun nextResponse(id: String): String =
        """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{
          "watchNextTabbedResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"musicQueueRenderer":{
            "content":{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{
              "videoId":"$id","longBylineText":{"runs":$albumRuns}
            }}]}}
          }}}}]}
        }}}}"""

    private val albumRuns =
        """[{"text":"Kendrick Lamar","navigationEndpoint":{"browseEndpoint":{"browseId":"UCkendrick"}}},
          {"text":" • "},{"text":"Black Panther: The Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREbalbum"}}}]"""
}
