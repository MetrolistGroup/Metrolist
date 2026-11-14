package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.lyricsProviderUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
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
}
