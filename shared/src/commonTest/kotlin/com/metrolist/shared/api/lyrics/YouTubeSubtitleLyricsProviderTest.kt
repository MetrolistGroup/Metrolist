package com.metrolist.shared.api.lyrics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeSubtitleLyricsProviderTest {
    @Test
    fun emptyTranscriptsAreMissesButFailedRequestsCanRetry() =
        runBlocking {
            var fail = false
            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String) = JsonObject(emptyMap())

                    override suspend fun browse(browseId: String) = JsonObject(emptyMap())

                    override suspend fun player(videoId: String): JsonObject {
                        if (fail) error("Transient failure")
                        return JsonObject(emptyMap())
                    }

                    override suspend fun transcript(videoId: String) = emptyList<YouTubeTranscriptCue>()

                    override suspend fun getText(url: String) = ""
                }
            val provider = YouTubeSubtitleLyricsProvider(api)
            assertNull(provider.getLyricsOrNull("video"))
            fail = true
            assertFailsWith<IllegalStateException> { provider.getLyricsOrNull("video") }
            fail = false
            assertNull(provider.getLyricsOrNull("video"))
        }

    @Test
    fun transcriptWordTimingIsPreservedInLyrics() =
        runBlocking {
            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String) = JsonObject(emptyMap())

                    override suspend fun browse(browseId: String) = JsonObject(emptyMap())

                    override suspend fun player(videoId: String) = JsonObject(emptyMap())

                    override suspend fun transcript(videoId: String) =
                        listOf(
                            YouTubeTranscriptCue(
                                text = "hello world",
                                startMs = 1_000L,
                                durationMs = 1_000L,
                                words =
                                    listOf(
                                        YouTubeTranscriptWord("hello", 1_000L),
                                        YouTubeTranscriptWord("world", 1_500L),
                                    ),
                            ),
                        )

                    override suspend fun getText(url: String) = ""
                }

            val lyrics = YouTubeSubtitleLyricsProvider(api).getLyricsOrNull("video")
            val entry = LyricsUtils.parseLyrics(requireNotNull(lyrics)).single()

            assertEquals("hello world", entry.text)
            assertEquals(1.0, entry.words?.first()?.startTime)
            assertEquals(1.5, entry.words?.last()?.startTime)
            assertEquals(2.0, entry.words?.last()?.endTime)
        }

    @Test
    fun captionTracksAndCueTextAreBounded() =
        runBlocking {
            var captionFetches = 0
            val api =
                object : YouTubeLyricsApi {
                    override suspend fun watchNext(videoId: String) = JsonObject(emptyMap())

                    override suspend fun browse(browseId: String) = JsonObject(emptyMap())

                    override suspend fun player(videoId: String) =
                        Json
                            .parseToJsonElement(
                                """{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[${(1..10).joinToString {
                                    "{\"baseUrl\":\"https://www.youtube.com/api/timedtext?id=$it\"}"
                                }}]}}}""",
                            ).jsonObject

                    override suspend fun transcript(videoId: String) = emptyList<YouTubeTranscriptCue>()

                    override suspend fun getText(url: String): String {
                        captionFetches++
                        return """{"events":[{"tStartMs":0,"segs":[{"utf8":"${"x".repeat(4_097)}"}]}]}"""
                    }
                }

            val tracks = YouTubeSubtitleLyricsProvider(api).getAllTranscriptCueTracks("video")

            assertEquals(8, captionFetches)
            assertTrue(tracks.isEmpty())
        }
}
