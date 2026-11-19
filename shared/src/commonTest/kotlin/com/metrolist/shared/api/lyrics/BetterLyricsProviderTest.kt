package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.lyricsCuratorUrl
import com.metrolist.shared.model.lyricsProviderUrl
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.getSystemLanguage
import com.russhwolf.settings.MapSettings
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetterLyricsProviderTest {
    @Test
    fun metadataTranslationsFollowLineKeysAndTheSelectedLanguage() =
        withTranslationLanguage("pl-PL") {
            val ttml =
                """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <head><metadata><audio lyricOffset="0.5"/><translations>
                    <translation xml:lang="en"><text for="L1">English</text></translation>
                    <translation xml:lang="pl"><text for="L2">Drugi</text><text for="L1">Pierwszy</text></translation>
                  </translations></metadata></head>
                  <body><div>
                    <p begin="1s" end="2s" ttm:key="L1">Original one</p>
                    <p begin="3s" end="4s" ttm:key="missing">Original two</p>
                    <p begin="5s" end="6s" ttm:key="L2">Original three</p>
                  </div></body>
                </tt>
                """.trimIndent()
            val mlx = parseTtmlToMlx(ttml, "Song", "Artist", "Unison")
            val document = MlxParser.parse(mlx)
            assertEquals(listOf("Pierwszy", null, "Drugi"), document.lines.map { it.translatedText })
            assertEquals(listOf(1500L, 3500L, 5500L), document.lines.map { it.time })
            assertEquals("pl-PL", document.song.translationLanguage)
            Settings.shared.lyricsAiTargetLanguage = "de"
            assertTrue(MlxParser.parse(mlx).lines.all { it.translatedText == null })
        }

    @Test
    fun inlineTranslationsKeepWordTimingAndBackgroundVocals() =
        withTranslationLanguage("de") {
            val ttml =
                """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                  <p begin="1s" end="4s">
                    <span begin="1s" end="2s">Lead</span>
                    <span ttm:role="x-translation" xml:lang="de-DE">Stimme</span>
                    <span ttm:role="x-translation" xml:lang="en">Voice</span>
                    <span ttm:role="x-bg" begin="2s" end="3s">
                      <span begin="2s" end="3s">Echo</span>
                      <span ttm:role="x-translation" xml:lang="de">Nachhall</span>
                    </span>
                  </p>
                  <p begin="5s" end="6s">Plain<span ttm:role="x-translation" xml:lang="fr">Autre</span></p>
                </div></body></tt>
                """.trimIndent()
            val lines = MlxParser.parse(parseTtmlToMlx(ttml, "Song", "Artist", "BetterLyrics")).lines
            assertEquals("Lead", lines.first().text)
            assertEquals("Stimme", lines.first().translatedText)
            assertEquals("Nachhall", lines.first().backgroundTranslatedText)
            assertEquals(listOf("Lead", "Echo"), assertNotNull(lines.first().words).map { it.text })
            assertEquals(listOf(1.0, 2.0), assertNotNull(lines.first().words).map { it.startTime })
            assertEquals("Plain", lines.last().text)
            assertNull(lines.last().translatedText)
        }

    @Test
    fun deviceTranslationTargetUsesTheSystemLanguage() =
        withTranslationLanguage("device") {
            // Android host tests have no Resources, so there is no device language to follow.
            val language = runCatching { getSystemLanguage() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withTranslationLanguage
            assertEquals(language, lyricsTranslationTargetLanguage())
            val ttml =
                """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                  <p begin="1s" end="2s">Original<span ttm:role="x-translation" xml:lang="$language">Translation</span></p>
                </div></body></tt>
                """.trimIndent()
            assertEquals(
                "Translation",
                MlxParser
                    .parse(parseTtmlToMlx(ttml, "Song", "Artist", "test"))
                    .lines
                    .single()
                    .translatedText,
            )
        }

    private fun withTranslationLanguage(
        language: String,
        block: () -> Unit,
    ) {
        val previous = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()).apply { lyricsAiTargetLanguage = language })
        try {
            block()
        } finally {
            Settings.replaceSharedForTest(previous)
        }
    }

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
    fun aSecondTimedBackgroundVocalIsNotPromotedToLead() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                <p begin="1s" end="5s">
                    <span begin="1s" end="2s">Lead</span>
                    <span ttm:role="x-bg" begin="2.5s" end="3.5s"><span begin="2.5s" end="3.5s">First echo</span></span>
                    <span ttm:role="x-bg" begin="3.5s" end="4.5s"><span begin="3.5s" end="4.5s">Second echo</span></span>
                </p>
            </div></body></tt>
            """.trimIndent()
        val lines = MlxParser.parse(parseTtmlToMlx(ttml, "title", "artist", "test")).lines
        val lead = lines.single { !it.isBackground }
        assertEquals("Lead", lead.text)
        val firstEcho = lead.words.orEmpty().single { it.isBackground }
        assertEquals("First echo", firstEcho.text)
        val echo = lines.single { it.isBackground }
        assertEquals("Second echo", echo.text)
        val word = echo.words.orEmpty().single()
        assertTrue(word.isBackground)
        assertEquals(3.5, word.startTime)
        assertEquals(4.5, word.endTime)
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
