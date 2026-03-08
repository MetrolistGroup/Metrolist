package com.metrolist.shared.api.lyrics

import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PaxsenixLyricsProviderTest {
    @Test
    fun unauthorizedSearchRenewsTokenWhenClientDisablesExpectSuccess() =
        runBlocking {
            PlaybackEventLog.clear()
            var tokens = 0
            var searches = 0
            HttpClient(
                MockEngine { request ->
                    when {
                        request.url.encodedPath.contains("/assets/") -> {
                            tokens++
                            respond("eyJtoken$tokens.payload.signature")
                        }

                        request.url.host == "beta.music.apple.com" -> {
                            respond("""<script src="/assets/index~test.js"></script>""")
                        }

                        else -> {
                            searches++
                            assertEquals("Bearer eyJtoken$tokens.payload.signature", request.headers[HttpHeaders.Authorization])
                            if (searches == 1) {
                                respond("expired", HttpStatusCode.Unauthorized)
                            } else {
                                respond("""{"results":{}}""")
                            }
                        }
                    }
                },
            ) {
                expectSuccess = false
                install(HttpFailureLog)
            }.use { client ->
                assertEquals(null, PaxsenixLyricsProvider(client).getLyrics("Title", "Artist", null))
                assertEquals(2, tokens)
                assertEquals(3, searches)
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    @Test
    fun returnsBestFoundLyricsWithoutFetchingWorseMatch() =
        runBlocking {
            val fetchedLyricsIds = mutableListOf<String>()
            val searchResponse =
                """
                {
                  "results":{"songs":{"data":[{"id":"1"},{"id":"2"}]}},
                  "resources":{"songs":{
                    "1":{"attributes":{"name":"Yurail","artistName":"INABAKUMORI","albumName":"Yurail - Single","durationInMillis":201500}},
                    "2":{"attributes":{"name":"Yurail (Instrumental)","artistName":"INABAKUMORI","albumName":"Yurail - Single","durationInMillis":201500}}
                  }}
                }
                """.trimIndent()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.host == "beta.music.apple.com" && request.url.encodedPath.contains("/assets/") -> {
                            respond("eyJaaa.bbb.ccc")
                        }

                        request.url.host == "beta.music.apple.com" -> {
                            respond("""<script src="/assets/index~test.js"></script>""")
                        }

                        request.url.host == "amp-api.music.apple.com" -> {
                            respond(searchResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }

                        request.url.host == "lyrics.paxsenix.org" -> {
                            val id = assertNotNull(request.url.parameters["id"])
                            fetchedLyricsIds += id
                            check(id == "1") { "lower-ranked candidate should not be fetched" }
                            respond(
                                """{"type":"None","ttmlContent":"<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" itunes:timing=\"None\"><body><div><p>First line</p></div></body></tt>","plain":"[Verse]\nFirst line"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val client = HttpClient(engine)

            client.use {
                val lyrics =
                    assertNotNull(
                        actual =
                            PaxsenixLyricsProvider(client).getLyrics(
                                title = "Yurail",
                                artist = "INABAKUMORI",
                                duration = 202_000,
                                album = "Yurail",
                            ),
                        message = engine.requestHistory.joinToString("\n") { it.url.toString() },
                    )
                assertEquals(listOf("1"), fetchedLyricsIds)
                assertEquals(
                    listOf("[Verse]", "First line"),
                    LyricsUtils
                        .parseMlxDocument(lyrics)
                        ?.lines
                        ?.map { it.text },
                )
            }
        }

    @Test
    fun enhancedLrcBackgroundFollowingALineStaysWithThatLine() {
        val mlx =
            paxsenixLyricsJsonToMlx(
                jsonText =
                    """
                    {
                      "elrcMultiPerson": "[00:01.00]<00:01.00>Lead <00:02.00>end\n[bg: <00:01.25>Background<00:02.25>]\n[00:03.00]<00:03.00>Next<00:04.00>"
                    }
                    """.trimIndent(),
                title = "Title",
                artist = "Artist",
            )

        val lines = assertNotNull(mlx?.let(LyricsUtils::parseMlxDocument)).lines
        assertEquals(2, lines.size)
        assertTrue(lines[0].words.orEmpty().any { it.isBackground && it.text == "Background" })
        assertTrue(lines[1].words.orEmpty().none { it.isBackground })
    }

    @Test
    fun explicitlyMarkedBackgroundLineIsMergedIntoTheLeadLine() {
        val mlx =
            paxsenixLyricsJsonToMlx(
                jsonText =
                    """
                    {
                      "content": [
                        {
                          "timestamp": 0,
                          "endtime": 5000,
                          "text": [{"text": "Lead", "timestamp": 0, "endtime": 5000}]
                        },
                        {
                          "timestamp": 1000,
                          "endtime": 6000,
                          "background": true,
                          "text": [{"text": "Background", "timestamp": 1000, "endtime": 6000}]
                        }
                      ]
                    }
                    """.trimIndent(),
                title = "Title",
                artist = "Artist",
            )

        val line = assertNotNull(mlx?.let(LyricsUtils::parseMlxDocument)).lines.single()
        assertEquals("Lead", line.text)
        assertTrue(line.words.orEmpty().any { it.isBackground && it.text == "Background" })
    }
}
