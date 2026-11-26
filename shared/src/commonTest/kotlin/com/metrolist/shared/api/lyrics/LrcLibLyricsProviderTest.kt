package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.lyricsProviderUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LrcLibLyricsProviderTest {
    @Test
    fun selectsMatchingRecordingInsteadOfFirstResult() =
        runTest {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """[
            {"id":1,"name":"Another track","artistName":"Artist","duration":180,"instrumental":false,"plainLyrics":"Wrong"},
            {"id":2,"name":"Track","artistName":"Artist","albumName":"Live","duration":260,"instrumental":false,"plainLyrics":"Wrong version"},
            {"id":3,"name":"Track","artistName":"Artist","albumName":"Album","duration":180,"instrumental":false,"plainLyrics":"Correct"}
        ]""",
                        )
                    },
                )
            try {
                val provider = LrcLibLyricsProvider(client)
                val lyrics = assertNotNull(provider.getLyrics("Track", "Artist", 180000, "Album"))
                val document = MlxParser.parse(lyrics)
                assertEquals("Correct", document.lines.single().text)
                assertEquals("3", document.meta.sourceId)
                assertEquals("https://lrclib.net/tracks/3", lyricsProviderUrl("LrcLib", document.meta.sourceId))
                assertNull(provider.getLyrics("Missing", "Artist", 180000))
                assertNotNull(provider.getLyrics("Track", "Artist", 0, "Album"))
            } finally {
                client.close()
            }
        }

    @Test
    fun matchesCollaborationsVideoTitlesAndFallsBackWithoutSingleAlbum() =
        runTest {
            val seenAlbums = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val albumParam = request.url.parameters["album_name"]
                        seenAlbums += albumParam
                        if (albumParam != null) {
                            respond("[]")
                        } else {
                            respond(
                                """[
                                    {"id":1,"name":"Night Dancer (Remix)","artistName":"imase","duration":210,"instrumental":false,"syncedLyrics":"[00:01.00]Wrong remix"},
                                    {"id":2,"name":"Night Dancer","artistName":"imase","albumName":"POP CUBE","duration":210,"instrumental":false,"syncedLyrics":"[00:01.00]Synced line"}
                                ]""",
                            )
                        }
                    },
                )
            try {
                val provider = LrcLibLyricsProvider(client)
                val lyrics =
                    assertNotNull(
                        provider.getLyrics(
                            title = "Night Dancer (Official Music Video)",
                            artist = "imase, Featured Artist",
                            duration = 212_000,
                            album = "Night Dancer - Single",
                        ),
                    )
                assertEquals(listOf("Night Dancer - Single", null), seenAlbums)
                assertEquals(
                    "Synced line",
                    MlxParser
                        .parse(lyrics)
                        .lines
                        .single()
                        .text,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun convertsWordSyncedLyricsfile() =
        runTest {
            // Reduced from LRCLIB record 8277104; words carry no end_ms, so ends come from the next word or line.
            val lyricsfile =
                """
                version: '1.0'
                metadata:
                  title: Blinding Lights
                  artist: The Weeknd
                  duration_ms: 200000
                  instrumental: false
                lines:
                - text: I've been tryna call
                  start_ms: 27100
                  end_ms: 28198
                  words:
                  - text: 'I''ve '
                    start_ms: 27139
                  - text: 'been '
                    start_ms: 27528
                  - text: 'tryna '
                    start_ms: 27819
                  - text: call
                    start_ms: 28130
                - text: Line only
                  start_ms: 29000
                  end_ms: 30000
                - text: Hello world
                  start_ms: 31000
                  words:
                  - text: Hello
                    start_ms: 31000
                  - text: ' '
                    start_ms: 31400
                  - text: world
                    start_ms: 31500
                    end_ms: 32000
                """.trimIndent()
            val body =
                buildJsonArray {
                    addJsonObject {
                        put("id", 8277104)
                        put("name", "Blinding Lights")
                        put("artistName", "The Weeknd")
                        put("duration", 200)
                        put("instrumental", false)
                        put("hasWordSync", true)
                        put("syncedLyrics", "[00:27.14]Legacy line")
                        put("lyricsfile", lyricsfile)
                    }
                }.toString()
            val client = HttpClient(MockEngine { respond(body) })
            try {
                val lyrics = assertNotNull(LrcLibLyricsProvider(client).getLyrics("Blinding Lights", "The Weeknd", 200_000))
                val document = MlxParser.parse(lyrics)
                assertEquals("8277104", document.meta.sourceId)
                val (wordLine, plainLine, segmentLine) = document.lines
                assertEquals(27_100L, wordLine.time)
                assertEquals(28_198L, wordLine.endTime)
                assertEquals(
                    listOf(
                        "I've" to (27_139L to 27_528L),
                        "been" to (27_528L to 27_819L),
                        "tryna" to (27_819L to 28_130L),
                        "call" to (28_130L to 28_198L),
                    ),
                    wordLine.words.orEmpty().map { it.text to ((it.startTime * 1000).roundToLong() to (it.endTime * 1000).roundToLong()) },
                )
                assertEquals(listOf(true, true, true, false), wordLine.words.orEmpty().map { it.hasTrailingSpace })
                assertEquals("Line only", plainLine.text)
                assertEquals(29_000L, plainLine.time)
                assertEquals("Hello world", segmentLine.text)
                assertEquals(32_000L, segmentLine.endTime)
                assertEquals(listOf(true, false), segmentLine.words.orEmpty().map { it.hasTrailingSpace })
                assertNull(lyricsfileToMlx(lyricsfile.replace("version: '1.0'", "version: '2.0'"), "Blinding Lights", "The Weeknd"))
            } finally {
                client.close()
            }
        }
}
