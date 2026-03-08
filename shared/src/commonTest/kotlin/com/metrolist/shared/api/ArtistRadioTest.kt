package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArtistRadioTest {
    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun radioUsesTheArtistButtonPlaylistAndParametersAndFiltersBlockedSongs() =
        runBlocking {
            Settings.shared.dislikedSongIds = "blocked"
            for (endpointType in listOf("watchPlaylistEndpoint", "watchEndpoint")) {
                val requests = mutableListOf<String>()
                val videoId = if (endpointType == "watchEndpoint") "seed" else null
                val endpoint = """{"playlistId":"RDEMt6MUSQI0t_Ts2EDUDWGRhQ","params":"wAEB"${videoId?.let {
                    ",\"videoId\":\"$it\""
                }.orEmpty()}}"""
                val client =
                    mockClient(
                        MockEngine { request ->
                            val path = request.url.encodedPath.substringAfterLast('/')
                            requests += path
                            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                            val response =
                                when (path) {
                                    "browse" -> {
                                        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", body["browseId"]?.jsonPrimitive?.content)
                                        artistResponse(
                                            """{"startRadioButton":{"buttonRenderer":{"navigationEndpoint":{"$endpointType":$endpoint}}}}""",
                                        )
                                    }

                                    "next" -> {
                                        assertEquals("RDEMt6MUSQI0t_Ts2EDUDWGRhQ", body["playlistId"]?.jsonPrimitive?.content)
                                        assertEquals("wAEB", body["params"]?.jsonPrimitive?.content)
                                        assertEquals(videoId, body["videoId"]?.jsonPrimitive?.contentOrNull)
                                        """{"continuationContents":{"playlistPanelContinuation":{"contents":[
                                          {"playlistPanelVideoRenderer":{"videoId":"playable","title":{"runs":[{"text":"Track"}]}}},
                                          {"playlistPanelVideoRenderer":{"videoId":"blocked","title":{"runs":[{"text":"Blocked"}]}}}
                                        ]}}}"""
                                    }

                                    else -> {
                                        error("Unexpected request: $path")
                                    }
                                }
                            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    )
                try {
                    val songs = YouTubeRepository(InnerTube(client)).startArtistRadio("UCuAXFkgsw1L7xaCfnd5JJOw")
                    assertEquals(listOf("playable"), songs.map { it.id })
                    assertEquals(listOf("browse", "next"), requests)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun radioEndpointSupportsTheSameHeadersAsArtistDetails() {
        val header = """{"startRadioButton":{"buttonRenderer":{"navigationEndpoint":{
          "watchPlaylistEndpoint":{"playlistId":"artist-mix"}
        }}}}"""
        val bodies =
            listOf(
                "musicImmersiveHeaderRenderer",
                "musicVisualHeaderRenderer",
                "musicHeaderRenderer",
                "musicDetailHeaderRenderer",
                "musicResponsiveHeaderRenderer",
            ).map { artistResponse(header, it) } +
                """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
                  "contents":[{"musicResponsiveHeaderRenderer":$header}]
                }}}}]}}}"""
        for (body in bodies) {
            val response = repositoryJson.decodeFromString<BrowseResponse>(body)
            val endpoint = assertNotNull(YouTubeArtistParser.parseArtistRadioEndpoint(response))
            assertEquals("artist-mix", endpoint["playlistId"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun unavailableRadioNeverGuessesAPlaylistOrUsesTheShuffleButton() =
        runBlocking {
            for (header in listOf(
                "{}",
                """{"playButton":{"buttonRenderer":{"navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"shuffle"}}}}}""",
                """{"startRadioButton":{"buttonRenderer":{"isDisabled":true,"navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"mix"}}}}}""",
                """{"startRadioButton":{"buttonRenderer":{"navigationEndpoint":{"watchPlaylistEndpoint":{"params":"wAEB"}}}}}""",
                """{"startRadioButton":{"buttonRenderer":{"navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":""}}}}}""",
            )) {
                var requests = 0
                val client =
                    mockClient(
                        MockEngine { request ->
                            requests++
                            assertTrue(request.url.encodedPath.endsWith("/browse"))
                            respond(artistResponse(header), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    )
                try {
                    assertTrue(YouTubeRepository(InnerTube(client)).startArtistRadio("artist").isEmpty())
                    assertEquals(1, requests)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun cancellingArtistBrowsePropagatesCancellation() =
        runBlocking<Unit> {
            val client = mockClient(MockEngine { throw CancellationException("Cancelled browse") })
            try {
                assertFailsWith<CancellationException> { YouTubeRepository(InnerTube(client)).startArtistRadio("artist") }
            } finally {
                client.close()
            }
        }

    private fun artistResponse(
        header: String,
        renderer: String = "musicImmersiveHeaderRenderer",
    ) = """{"header":{"$renderer":$header}}"""

    private fun mockClient(engine: MockEngine) =
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
}
