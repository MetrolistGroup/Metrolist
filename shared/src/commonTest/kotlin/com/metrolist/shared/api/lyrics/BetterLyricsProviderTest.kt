package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.lyricsCuratorUrl
import com.metrolist.shared.model.lyricsProviderUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BetterLyricsProviderTest {
    @Test
    fun unisonSubmitterAndVideoMetadataArePreserved() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"success":true,"data":{"format":"lrc","lyrics":"[00:01.000]Line","submitter":{"displayName":"boidu","keyId":"curator_1"}}}""",
                        )
                    },
                )
            try {
                val lyrics =
                    assertNotNull(
                        UnisonProvider(client).getLyrics(
                            title = "Song",
                            artist = "Artist",
                            duration = null,
                            videoId = "abcdefghijk",
                        ),
                    )
                val meta = assertNotNull(MlxParser.parse(lyrics)).meta
                assertEquals("abcdefghijk", meta.sourceId)
                assertEquals("boidu", meta.lyricsCurator)
                assertEquals("curator_1", meta.lyricsCuratorId)
                assertEquals("https://unison.betterlyrics.org/song/abcdefghijk", lyricsProviderUrl("Unison", meta.sourceId))
                assertEquals(
                    "https://unison.betterlyrics.org/curator/curator_1",
                    lyricsCuratorUrl("Unison", meta.lyricsCuratorId),
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun plainBackgroundVocalsKeepTheirOwnTiming() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                <p begin="1s" end="4s"><span begin="1s" end="2s">Lead</span><span ttm:role="x-bg" begin="2.5s" end="3.5s">Echo</span></p>
            </div></body></tt>
            """.trimIndent()
        val lines = assertNotNull(MlxParser.parse(parseTtmlToMlx(ttml, "title", "artist", "test"))).lines
        assertEquals(listOf("Lead", "Echo"), lines.map { it.text })
        assertEquals(listOf(1_000L, 2_500L), lines.map { it.time })
        assertEquals(listOf(4_000L, 3_500L), lines.map { it.endTime })
        assertTrue(lines[1].isBackground)
        assertEquals(1, assertNotNull(lines[0].words).size)
    }

    @Test
    fun wordSyncedBackgroundIsMergedOnlyOnce() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                <p begin="1s" end="4s"><span begin="1s" end="2s">Lead</span><span ttm:role="x-bg" begin="2.5s" end="3.5s"><span begin="2.5s" end="3.5s">Echo</span></span></p>
            </div></body></tt>
            """.trimIndent()
        val line = assertNotNull(MlxParser.parse(parseTtmlToMlx(ttml, "title", "artist", "test"))).lines.single()
        val background = assertNotNull(line.words).single { it.isBackground }
        assertEquals("Echo", background.text)
        assertEquals(2.5, background.startTime)
        assertEquals(3.5, background.endTime)
    }

    @Test
    fun utf8ResponsePreservesGermanUmlautsThroughTtmlAndMlx() =
        runBlocking {
            val ttml =
                """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                    <p begin="1s" end="4s"><span begin="1s" end="2s">grünen </span><span begin="2s" end="3s">h&#252;geln </span><span begin="3s" end="4s">ÄÖÜ äö&#xFC; ß</span></p>
                </div></body></tt>
                """.trimIndent()
            val response = buildJsonObject { put("ttml", ttml) }.toString()
            val client = HttpClient(MockEngine { respond(response) })
            try {
                val lyrics = assertNotNull(BetterLyricsProvider(client).getLyrics("title", "artist", null))
                val line = LyricsUtils.parseLyrics(lyrics).single()
                assertEquals("grünen hügeln ÄÖÜ äöü ß", line.text)
                assertEquals(listOf("grünen", "hügeln", "ÄÖÜ äöü ß"), assertNotNull(line.words).map { it.text })
            } finally {
                client.close()
            }
        }

    @Test
    fun getLyricsDoesNotSwallowCancellation() {
        assertFailsWith<CancellationException> {
            runBlocking {
                val client =
                    HttpClient(
                        MockEngine {
                            throw CancellationException("cancelled")
                        },
                    )
                try {
                    BetterLyricsProvider(client).getLyrics("title", "artist", null)
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun ttmlAttributeWhitespacePreservesWordTimingThroughMlx() {
        val ttml =
            """
            <tt xmlns = "http://www.w3.org/ns/ttml"><body><div>
                <p begin = "1s" end = "3s"><span begin = "1s" end = "1.5s">Hello </span><span begin = "2s" end = "3s">world &amp; friends</span></p>
            </div></body></tt>
            """.trimIndent()
        val document = MlxParser.parse(parseTtmlToMlx(ttml, "title", "artist", "test"))
        assertNotNull(document)
        val line = document.lines.single()
        assertEquals(1_000L, line.time)
        assertEquals(3_000L, line.endTime)
        val words = assertNotNull(line.words)
        assertEquals(listOf("Hello", "world & friends"), words.map { it.text })
        assertEquals(listOf(1.0, 2.0), words.map { it.startTime })
        assertEquals(listOf(1.5, 3.0), words.map { it.endTime })
    }

    @Test
    fun ttmlParserRejectsDoctypeAndExternalEntities() {
        val maliciousTtml =
            """
            <!DOCTYPE tt [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0s" end="1s">&xxe;</p></div></body></tt>
            """.trimIndent()

        assertEquals("", parseTtmlToMlx(maliciousTtml, "title", "artist", "test"))
    }

    @Test
    fun retriesWithoutAlbumWhenAlbumScopedRequestReturnsNotFound() =
        runBlocking {
            val ttml =
                """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                    <p begin="1s" end="3s"><span begin="1s" end="3s">Recovered</span></p>
                </div></body></tt>
                """.trimIndent()
            val seenAlbums = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val albumParam = request.url.parameters["al"]
                        seenAlbums += albumParam
                        if (albumParam != null) {
                            respond("", HttpStatusCode.NotFound)
                        } else {
                            respond(buildJsonObject { put("ttml", ttml) }.toString())
                        }
                    },
                )
            try {
                val lyrics = assertNotNull(BetterLyricsProvider(client).getLyrics("Song", "Artist", 180_000, "Song - Single"))
                assertEquals(listOf("Song - Single", null), seenAlbums)
                assertEquals("Recovered", LyricsUtils.parseLyrics(lyrics).single().text)
            } finally {
                client.close()
            }
        }
}
