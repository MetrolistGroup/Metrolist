/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.lyricsProviderUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcredLyricsProviderTest {
    @Test
    fun rejectsSingletonWrongArtistButKeepsCollaborationsAndIsrcMatches() {
        val candidate =
            LrcredResult(
                id = "candidate",
                trackName = "Song",
                artistName = "Unrelated Artist",
                albumName = "Album",
                duration = 180.0,
                timingType = "word",
                isrc = "recording",
            )

        fun match(
            item: LrcredResult,
            isrc: String? = null,
        ) = LrcredLyricsProvider.findBestMatch(listOf(item), "Song", "Main Artist", 180, "Album", isrc)

        assertNull(match(candidate))
        assertEquals(candidate, match(candidate, "recording"))
        for (artist in listOf("Guest & Main Artist", "Main Artist feat. Guest", "Guest, Main Artist")) {
            val collaboration = candidate.copy(artistName = artist)
            assertEquals(collaboration, match(collaboration))
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
                    LrcredLyricsProvider(client).getLyrics("title", "artist", null)
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun findBestMatchMatchesExactIsrc() {
        val results =
            listOf(
                LrcredResult(id = "1", isrc = "USUG11901472", trackName = "Cruel Summer (Remix)", duration = 180.0),
                LrcredResult(id = "2", isrc = "USUG12345678", trackName = "Cruel Summer", duration = 178.0),
            )

        val match =
            LrcredLyricsProvider.findBestMatch(
                results = results,
                title = "Cruel Summer",
                artist = "Taylor Swift",
                durationSec = 178,
                album = "Lover",
                isrc = "USUG11901472",
            )

        assertNotNull(match)
        assertEquals("1", match.id)
    }

    @Test
    fun findBestMatchScoresByTitleArtistAlbumAndQuality() {
        val results =
            listOf(
                LrcredResult(
                    id = "wrong_artist",
                    trackName = "Cruel Summer",
                    artistName = "Other Artist",
                    albumName = "Cover Album",
                    duration = 178.0,
                    timingType = "word",
                ),
                LrcredResult(
                    id = "line_synced",
                    trackName = "Cruel Summer",
                    artistName = "Taylor Swift",
                    albumName = "Lover",
                    duration = 178.0,
                    timingType = "line",
                ),
                LrcredResult(
                    id = "word_synced",
                    trackName = "Cruel Summer",
                    artistName = "Taylor Swift",
                    albumName = "Lover",
                    duration = 178.0,
                    timingType = "word",
                ),
            )

        val match =
            LrcredLyricsProvider.findBestMatch(
                results = results,
                title = "Cruel Summer",
                artist = "Taylor Swift",
                durationSec = 178,
                album = "Lover",
                isrc = null,
            )

        assertNotNull(match)
        assertEquals("word_synced", match.id)
    }

    @Test
    fun findBestMatchPenalizesMismatchedModifiers() {
        val results =
            listOf(
                LrcredResult(
                    id = "remix",
                    trackName = "Cruel Summer (Remix)",
                    artistName = "Taylor Swift",
                    albumName = "Lover",
                    duration = 178.0,
                    timingType = "word",
                ),
                LrcredResult(
                    id = "original",
                    trackName = "Cruel Summer",
                    artistName = "Taylor Swift",
                    albumName = "Lover",
                    duration = 178.0,
                    timingType = "line",
                ),
            )

        val match =
            LrcredLyricsProvider.findBestMatch(
                results = results,
                title = "Cruel Summer",
                artist = "Taylor Swift",
                durationSec = 178,
                album = "Lover",
                isrc = null,
            )

        assertNotNull(match)
        assertEquals("original", match.id)
    }

    @Test
    fun cleanBaseTitleStripsFeatAndVideoTags() {
        assertEquals("blinding lights", LrcredLyricsProvider.cleanBaseTitle("Blinding Lights (Official Video)"))
        assertEquals("blinding lights", LrcredLyricsProvider.cleanBaseTitle("Blinding Lights [feat. Rosalía]"))
        assertEquals("stay", LrcredLyricsProvider.cleanBaseTitle("Stay with Justin Bieber"))
        assertEquals("stay", LrcredLyricsProvider.cleanBaseTitle("Stay feat. Justin Bieber"))
    }

    @Test
    fun extractArtistsSplitsMultipleArtists() {
        val artists = LrcredLyricsProvider.extractArtists("Taylor Swift, Bon Iver & Aaron Dessner")
        assertEquals(listOf("taylor swift", "bon iver", "aaron dessner"), artists)
    }

    @Test
    fun successfulFetchConvertsTtmlToMlx() =
        runBlocking {
            val searchJson =
                """
                {
                  "results": [
                    {
                      "id": "1",
                      "track_name": "Cruel Summer",
                      "artist_name": "Taylor Swift",
                      "album_name": "Lover",
                      "duration": 178,
                      "timing_type": "word",
                      "isrc": "USUG11901472",
                      "lyricsUrl": "https://lrc.red/s/1.ttml"
                    }
                  ]
                }
                """.trimIndent()

            val ttml =
                """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:lrc="http://lrc.red/lyric-ttml-internal" lrc:timing="Word">
                  <head>
                    <metadata>
                      <sourceMetadata xmlns="http://lrc.red/lyric-ttml-internal">
                        <songwriters>
                          <songwriter>Taylor Swift</songwriter>
                          <songwriter>Jack Antonoff</songwriter>
                        </songwriters>
                      </sourceMetadata>
                    </metadata>
                  </head>
                  <body>
                    <div begin="00:01.000" end="00:05.000" lrc:songPart="Chorus">
                      <p begin="00:01.000" end="00:04.000">
                        <span begin="00:01.000" end="00:02.000">Cruel</span> <span begin="00:02.000" end="00:04.000">summer</span>
                      </p>
                    </div>
                  </body>
                </tt>
                """.trimIndent()

            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v1" -> {
                            respond(searchJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }

                        "/s/1.ttml" -> {
                            respond(ttml, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
                        }

                        else -> {
                            error("Unexpected url: ${request.url}")
                        }
                    }
                }

            val client = HttpClient(engine)
            client.use {
                val lyrics =
                    assertNotNull(
                        LrcredLyricsProvider(client).getLyrics(
                            title = "Cruel Summer",
                            artist = "Taylor Swift",
                            duration = 178_000,
                            album = "Lover",
                        ),
                    )

                val doc = assertNotNull(LyricsUtils.parseMlxDocument(lyrics))
                assertEquals("USUG11901472", doc.meta.sourceId)
                assertEquals("https://lrc.red/s/USUG11901472", lyricsProviderUrl("lrc.red", doc.meta.sourceId))
                assertEquals("Cruel Summer", doc.song.title)
                assertEquals("Taylor Swift", doc.song.artist)
                assertEquals(listOf("Taylor Swift", "Jack Antonoff"), doc.song.songwriters)
                assertEquals(1, doc.lines.size)
                assertEquals("Cruel summer", doc.lines[0].text)
                assertTrue(
                    doc.lines[0]
                        .words
                        .orEmpty()
                        .isNotEmpty(),
                )
            }
        }

    @Test
    fun videoFallbackRemovesVideoTagWhenInitialDurationFails() =
        runBlocking {
            var searchCount = 0
            val initialSearch = """{"results": []}"""
            val fallbackSearch =
                """
                {
                  "results": [
                    {
                      "id": "2",
                      "track_name": "Cruel Summer",
                      "artist_name": "Taylor Swift",
                      "duration": 178,
                      "timing_type": "line",
                      "lyricsUrl": "https://lrc.red/s/2.ttml"
                    }
                  ]
                }
                """.trimIndent()

            val ttml =
                """
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body>
                    <div>
                      <p begin="0.500" end="3.000">Cruel summer</p>
                    </div>
                  </body>
                </tt>
                """.trimIndent()

            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v1" -> {
                            searchCount++
                            val q = request.url.parameters["q"]
                            if (q?.contains("Official Video") == true) {
                                respond(initialSearch, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                            } else {
                                respond(fallbackSearch, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                            }
                        }

                        "/s/2.ttml" -> {
                            respond(ttml, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/xml"))
                        }

                        else -> {
                            error("Unexpected url: ${request.url}")
                        }
                    }
                }

            val client = HttpClient(engine)
            client.use {
                val lyrics =
                    assertNotNull(
                        LrcredLyricsProvider(client).getLyrics(
                            title = "Cruel Summer (Official Video)",
                            artist = "Taylor Swift",
                            duration = 195_000,
                        ),
                    )

                assertEquals(2, searchCount)
                val doc = assertNotNull(LyricsUtils.parseMlxDocument(lyrics))
                assertEquals("Cruel summer", doc.lines[0].text)
            }
        }

    @Test
    fun returnsNullWhenNoResultsFound() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("""{"results": []}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val client = HttpClient(engine)
            client.use {
                val lyrics =
                    LrcredLyricsProvider(client).getLyrics(
                        title = "Nonexistent Song",
                        artist = "Unknown Artist",
                        duration = 100_000,
                    )
                assertNull(lyrics)
            }
        }
}
