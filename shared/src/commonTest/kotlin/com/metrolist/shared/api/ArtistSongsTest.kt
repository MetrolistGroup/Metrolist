package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtistSongsTest {
    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    private val song = SongItem("first", "First", null, emptyList(), AlbumTiny("album", "Album"))
    private val video = song.copy(id = "video", musicVideoType = MUSIC_VIDEO_TYPE_OMV)
    private val page =
        ArtistPageResult(
            linkedMapOf("Vídeos" to listOf(video), "Canciones" to listOf(song)),
            mapOf("Vídeos" to ("VLOLvideos" to null), "Canciones" to ("VLOLtracks" to "params")),
        )

    @Test
    fun repositoryFollowsSongsContinuationAndPreservesOrder() =
        runTest {
            fun track(
                id: String,
                type: String = "MUSIC_VIDEO_TYPE_ATV",
            ) = """{
            "musicResponsiveListItemRenderer": {
                "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}],
                "playlistItemData":{"videoId":"$id"},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"$id",
                    "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}
            }
        }"""
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val response =
                            if (requests++ == 0) {
                                assertEquals("VLOLtracks", body["browseId"]?.jsonPrimitive?.content)
                                assertEquals("params", body["params"]?.jsonPrimitive?.content)
                                """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
                  "sectionListRenderer":{"contents":[{"musicShelfRenderer":{
                    "contents":[${track("one")}],
                    "continuations":[{"nextContinuationData":{"continuation":"next"}}]
                  }}]}
                }}}]}}}"""
                            } else {
                                assertEquals("next", body["continuation"]?.jsonPrimitive?.content)
                                """{"continuationContents":{"musicShelfContinuation":{
                    "contents":[${track("two")},${track("video", MUSIC_VIDEO_TYPE_OMV)},${track("one")}]
                }}}"""
                            }
                        respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val result = YouTubeRepository(InnerTube(client)).getArtistSongs("artist", page)
                assertEquals(listOf("one", "two"), result.map { it.id })
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun loadsSongsShelfInOrderAndExcludesVideosAndDuplicates() =
        runTest {
            val second = song.copy(id = "second")
            val songs =
                loadArtistSongs(page) { id, params ->
                    assertEquals("VLOLtracks", id)
                    assertEquals("params", params)
                    listOf(second, video, song, second, song.copy(id = "podcast", musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))
                }
            assertEquals(listOf(second, song), songs)
        }

    @Test
    fun usesPreviewOnlyWhenThereIsNoMoreEndpoint() =
        runTest {
            val withoutEndpoint = loadArtistSongs(page.copy(sectionBrowseIds = emptyMap())) { _, _ -> error("Unexpected request") }
            assertEquals(listOf(song), withoutEndpoint)
            assertEquals(emptyList(), loadArtistSongs(page) { _, _ -> emptyList() })
            assertEquals(
                emptyList(),
                loadArtistSongs(page.copy(sections = mapOf("Vídeos" to listOf(video)))) { _, _ -> error("Video shelf") },
            )
        }

    @Test
    fun failedFullBrowseReportsFailureWithoutPlayingPreview() =
        runTest {
            val client =
                HttpClient(MockEngine { respond("{}", HttpStatusCode.ServiceUnavailable) }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                var failures = 0
                val songs = YouTubeRepository(InnerTube(client)).getArtistSongs("artist", page) { failures++ }
                assertEquals(emptyList(), songs)
                assertEquals(1, failures)
            } finally {
                client.close()
            }
        }

    @Test
    fun cancellationDoesNotStartAPartialQueue() =
        runTest {
            assertFailsWith<CancellationException> { loadArtistSongs(page) { _, _ -> throw CancellationException() } }
        }
}
