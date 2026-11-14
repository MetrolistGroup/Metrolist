package com.metrolist.shared.api.lyrics

import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsProviderFailureTest {
    @Test
    fun kuGouKeepsColonLyricsAndOnlyRemovesRecognizedCredits() =
        runBlocking {
            val lrc =
                """
                [00:00]作词：Writer
                [00:01]Opening
                [00:02]I said: stay
                [00:03]Another: line
                [00:04]Composer: Writer
                [00:05]Ending: stay
                """.trimIndent()
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/search") {
                            respond("""{"candidates":[{"id":1,"accesskey":"key","duration":180}]}""")
                        } else {
                            respond("""{"content":"${Base64.encode(lrc.encodeToByteArray())}"}""")
                        }
                    },
                )
            try {
                val lyrics = assertNotNull(KuGouLyricsProvider(client).getLyrics("Song", "Artist", 180))
                assertEquals(
                    listOf("Opening", "I said: stay", "Another: line", "Ending: stay"),
                    LyricsUtils.parseLyrics(lyrics).map { it.text },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun kuGouNormalizationRetainsWholeSecondTimestamps() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/search") {
                            respond("""{"candidates":[{"id":1,"accesskey":"key","duration":180}]}""")
                        } else {
                            val encoded = Base64.encode("[00:12]A complete lyric".encodeToByteArray())
                            respond("""{"content":"$encoded"}""")
                        }
                    },
                )
            try {
                val lyrics = assertNotNull(KuGouLyricsProvider(client).getLyrics("Song", "Artist", 180))
                assertEquals(12000L, LyricsUtils.parseLyrics(lyrics).single().time)
            } finally {
                client.close()
            }
        }

    @Test
    fun kuGouMatchesMillisecondCandidatesUnknownDurationAndAlbumFallback() =
        runBlocking {
            val encoded = Base64.encode("[00:05]KuGou lyric".encodeToByteArray())
            val seenQueries = mutableListOf<Pair<String?, String?>>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/search") {
                            val keyword = request.url.parameters["keyword"]
                            val duration = request.url.parameters["duration"]
                            seenQueries += keyword to duration
                            if (keyword?.contains("Single") == true) {
                                respond("""{"candidates":[]}""")
                            } else {
                                respond("""{"candidates":[{"id":9,"accesskey":"k","duration":180500}]}""")
                            }
                        } else {
                            respond("""{"content":"$encoded"}""")
                        }
                    },
                )
            try {
                val provider = KuGouLyricsProvider(client)
                val withAlbumFallback = assertNotNull(provider.getLyrics("Song", "Artist", 180, "Song - Single"))
                assertEquals("KuGou lyric", LyricsUtils.parseLyrics(withAlbumFallback).single().text)
                assertEquals(
                    listOf<Pair<String?, String?>>(
                        "Song - Artist Song - Single" to "180000",
                        "Song - Artist" to "180000",
                    ),
                    seenQueries,
                )

                seenQueries.clear()
                val unknownDuration = assertNotNull(provider.getLyrics("Song", "", 0))
                assertEquals("KuGou lyric", LyricsUtils.parseLyrics(unknownDuration).single().text)
                assertEquals(listOf<Pair<String?, String?>>("Song" to null), seenQueries)
            } finally {
                client.close()
            }
        }

    private val fetches: List<suspend (HttpClient) -> String?> =
        listOf(
            { LrcLibLyricsProvider(it).getLyrics("title", "artist", null) },
            { SimpMusicLyricsProvider(it).getLyrics("video") },
            { KuGouLyricsProvider(it).getLyrics("title", "artist", 180) },
            { BetterLyricsProvider(it).getLyrics("title", "artist", null) },
        )

    @Test
    fun failuresAndOversizedResponsesAreNotSuccessfulMisses() =
        runBlocking {
            for (fetch in fetches) {
                for ((body, status) in listOf(
                    "outage" to HttpStatusCode.ServiceUnavailable,
                    "invalid JSON" to HttpStatusCode.OK,
                    "x".repeat(1024 * 1024 + 1) to HttpStatusCode.OK,
                )) {
                    val client = HttpClient(MockEngine { respond(body, status) })
                    try {
                        assertFailsWith<Exception> { fetch(client) }
                    } finally {
                        client.close()
                    }
                }
                val cancelled = HttpClient(MockEngine { throw CancellationException("cancelled") })
                try {
                    assertFailsWith<CancellationException> { fetch(cancelled) }
                } finally {
                    cancelled.close()
                }
            }
        }

    @Test
    fun confirmedNotFoundRemainsAMiss() =
        runBlocking {
            for (fetch in fetches) {
                PlaybackEventLog.clear()
                val client =
                    HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
                        install(HttpFailureLog)
                    }
                try {
                    assertNull(fetch(client))
                    assertEquals(0, PlaybackEventLog.events.value.size)
                } finally {
                    client.close()
                }
            }
        }
}
