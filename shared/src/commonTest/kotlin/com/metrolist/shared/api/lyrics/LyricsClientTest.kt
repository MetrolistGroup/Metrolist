package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LyricsClientTest {
    @Test
    fun lyricsFailuresAreNotRecorded() =
        runBlocking {
            PlaybackEventLog.clear()
            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun browse(browseId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun player(videoId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun transcript(videoId: String): List<YouTubeTranscriptCue> = error("Unexpected YouTube request")

                    override suspend fun getText(url: String): String = error("Unexpected YouTube request")
                }
            HttpClient(MockEngine { respond("failed", HttpStatusCode.InternalServerError) }) {
                install(HttpFailureLog)
            }.use { httpClient ->
                val client = LyricsClient(httpClient, api, null)
                assertFailsWith<LyricsHttpStatusException> {
                    client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.LRCLIB)
                }
                assertEquals(0, PlaybackEventLog.events.value.size)
                // Requests outside lyrics lookups still record HTTP failures.
                httpClient.get("https://example.com/other")
                assertEquals(1, PlaybackEventLog.events.value.size)
            }
            PlaybackEventLog.clear()
        }

    @Test
    fun automaticLookupUsesBestResultAndOnlySourceBrowsingRequestsAllSources() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val allSourcesRequests = mutableListOf<Boolean>()
            val mlx = MlxBuilder().meta("Lyprio").line(1_000, 2_000, words = listOf(MlxWord("Lyric", 1_000, 2_000))).build()
            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun browse(browseId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun player(videoId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun transcript(videoId: String): List<YouTubeTranscriptCue> = error("Unexpected YouTube request")

                    override suspend fun getText(url: String): String = error("Unexpected YouTube request")
                }
            try {
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        allSourcesRequests += body["allSources"]?.jsonPrimitive?.content == "true"
                        respond(
                            """{"status":"ready","complete":true,"results":[{"subprovider":"TEST","format":"mlx","syncType":"word","lyrics":${Json.encodeToString(
                                mlx,
                            )}}]}""",
                            HttpStatusCode.OK,
                            headersOf("X-Lyprio-Protocol", "2"),
                        )
                    },
                ).use { httpClient ->
                    val provider =
                        LyprioLyricsProvider.createOrNull(
                            httpClient,
                            "https://lyrics.example",
                            "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
                        )
                    val client = LyricsClient(httpClient, api, provider)
                    Settings.shared.lyricsEnabledProviders = "LYPRIO"
                    Settings.shared.lyricsProviderOrder = "LYPRIO"
                    assertEquals(mlx, client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.AUTO)?.lyrics)
                    assertEquals(mlx, client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.LYPRIO)?.lyrics)
                    assertEquals(
                        listOf(mlx),
                        client.getLyricsResults("Song", "Artist", null, null, null, LyricsProvider.LYPRIO).map { it.lyrics },
                    )
                    assertEquals(listOf(false, false, true), allSourcesRequests)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun autoUsesTimingQualityThenConfiguredOrderAndEnabledProviders() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val paths = mutableListOf<String>()
            var unisonLyrics = "[00:01.000]Hello"
            var syncedLyrics: String? = "[00:01.000]Hello"
            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun browse(browseId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun player(videoId: String): JsonObject = error("Unexpected YouTube request")

                    override suspend fun transcript(videoId: String): List<YouTubeTranscriptCue> = error("Unexpected YouTube request")

                    override suspend fun getText(url: String): String = error("Unexpected YouTube request")
                }
            try {
                HttpClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/api/search" -> {
                                respond(
                                    """[{"id":1,"name":"Song","artistName":"Artist","duration":180,"instrumental":false,
                                        "syncedLyrics":${Json.encodeToString(syncedLyrics)},"plainLyrics":"Hello"}]""",
                                )
                            }

                            "/lyrics" -> {
                                respond("""{"success":true,"data":{"format":"lrc","lyrics":${Json.encodeToString(unisonLyrics)}}}""")
                            }

                            else -> {
                                error("Unexpected request: ${request.url}")
                            }
                        }
                    },
                ).use { httpClient ->
                    val client = LyricsClient(httpClient, api, null)
                    Settings.shared.lyricsEnabledProviders = "LRCLIB,UNISON"
                    Settings.shared.lyricsProviderOrder = "LRCLIB,UNISON"
                    assertEquals("LrcLib", client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.AUTO)?.provider)
                    Settings.shared.lyricsProviderOrder = "UNISON,LRCLIB"
                    assertEquals("Unison", client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.AUTO)?.provider)
                    Settings.shared.lyricsProviderOrder = "LRCLIB,UNISON"
                    unisonLyrics = "[00:01.000]<00:01.000>Hello<00:02.000>"
                    assertEquals("LrcLib", client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.AUTO)?.provider)
                    Settings.shared.lyricsEnabledProviders = "LRCLIB"
                    paths.clear()
                    assertEquals("LrcLib", client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.AUTO)?.provider)
                    assertEquals(listOf("/api/search"), paths)
                    syncedLyrics = null
                    Settings.shared.lyricsEnabledProviders = "LRCLIB,UNISON"
                    assertEquals("Unison", client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.AUTO)?.provider)
                    Settings.shared.lyricsEnabledProviders = "LRCLIB"
                    assertEquals("LrcLib", client.getLyrics("Song", "Artist", null, null, null, LyricsProvider.AUTO)?.provider)
                    Settings.shared.lyricsEnabledProviders = "UNISON"
                    Settings.shared.lyricsProviderOrder = "UNISON"
                    for (provider in listOf(LyricsProvider.UNISON, LyricsProvider.AUTO)) {
                        for (isVideoContext in listOf(false, true)) {
                            val result =
                                client.getLyrics(
                                    "Song",
                                    "Artist",
                                    null,
                                    videoId = "video",
                                    provider = provider,
                                    isVideoContext = isVideoContext,
                                )
                            assertNotNull(result)
                            assertEquals("Unison", result.provider)
                            assertEquals(isVideoContext, MlxParser.parse(result.lyrics).meta.ignoreRetiming)
                        }
                    }
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }
}
