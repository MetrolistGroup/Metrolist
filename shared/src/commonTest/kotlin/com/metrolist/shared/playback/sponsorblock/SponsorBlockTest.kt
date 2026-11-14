package com.metrolist.shared.playback.sponsorblock

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SponsorBlockTest {
    private val episode = SongItem("abcdefghijk", "Episode", null, emptyList(), null, musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
    private val response = """[{"videoID":"abcdefghijk","segments":[{"segment":[1,5],"category":"sponsor","actionType":"skip"}]}]"""

    @Test
    fun handledNotFoundDoesNotBecomeADiagnosticFailure() =
        runTest {
            PlaybackEventLog.clear()
            val http =
                HttpClient(
                    MockEngine { respond("", HttpStatusCode.NotFound) },
                ) {
                    install(HttpFailureLog)
                }
            try {
                assertTrue(SponsorBlockClient(http).segments(episode, true).isEmpty())
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            } finally {
                http.close()
                PlaybackEventLog.clear()
            }
        }

    @Test
    fun filtersVideoCategoryActionAndInvalidRangesAndMergesOverlaps() {
        val body =
            """
            [
              {"videoID":"anotherVideo","segments":[{"segment":[0,100],"category":"sponsor","actionType":"skip"}]},
              {"videoID":"abcdefghijk","segments":[
                {"segment":[2.5,5.75],"category":"sponsor","actionType":"skip"},
                {"segment":[1,3],"category":"selfpromo","actionType":"skip"},
                {"segment":[5.75,6],"category":"interaction","actionType":"skip"},
                {"segment":[8,9],"category":"sponsor","actionType":"skip"},
                {"segment":[0,100],"category":"music_offtopic","actionType":"skip"},
                {"segment":[0,100],"category":"intro","actionType":"skip"},
                {"segment":[0,100],"category":"sponsor","actionType":"mute"},
                {"segment":[0,100],"category":"sponsor"},
                {"segment":[-1,100],"category":"sponsor","actionType":"skip"},
                {"segment":[5,5],"category":"sponsor","actionType":"skip"},
                {"segment":[8,2],"category":"sponsor","actionType":"skip"},
                {"segment":[0,1e300],"category":"sponsor","actionType":"skip"},
                {"segment":[0,"NaN"],"category":"sponsor","actionType":"skip"},
                {"segment":[0,"Infinity"],"category":"sponsor","actionType":"skip"},
                {"segment":[0],"category":"sponsor","actionType":"skip"},
                {"segment":[0,4,9],"category":"sponsor","actionType":"skip"},
                {"segment":[{},4],"category":"sponsor","actionType":"skip"},
                null
              ]}
            ]
            """.trimIndent()
        val segments = parseSponsorBlockSegments(body, episode.id)
        assertEquals(listOf(SponsorBlockSegment(1000, 6000), SponsorBlockSegment(8000, 9000)), segments)
        assertNull(segments.skipTarget(999, 10_000))
        assertEquals(6000L, segments.skipTarget(1000, 10_000))
        assertNull(segments.skipTarget(6000, 10_000))
        assertNull(segments.skipTarget(5000, 5500))
        assertEquals(9000L, segments.skipTarget(8500, 0))
        assertTrue(parseSponsorBlockSegments(body, "missingVideo").isEmpty())
    }

    @Test
    fun onlyExplicitOnlinePodcastEpisodesAreEligible() {
        assertTrue(canSkipPodcastSegments(episode, true))
        assertFalse(canSkipPodcastSegments(episode, false))
        assertFalse(canSkipPodcastSegments(null, true))
        assertFalse(canSkipPodcastSegments(episode.copy(isLocal = true), true))
        assertFalse(canSkipPodcastSegments(episode.copy(isUploaded = true), true))
        assertFalse(canSkipPodcastSegments(episode.copy(id = "local_recording"), true))
        for (type in listOf(
            null,
            "MUSIC_VIDEO_TYPE_ATV",
            "MUSIC_VIDEO_TYPE_OMV",
            "MUSIC_VIDEO_TYPE_UGC",
            "MUSIC_VIDEO_TYPE_VIDEO",
            "MUSIC_VIDEO_TYPE_LIVE",
            "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK",
        )) {
            assertFalse(canSkipPodcastSegments(episode.copy(musicVideoType = type, podcastId = "podcast"), true), type)
        }
    }

    @Test
    fun hashesRequestsAndCachesByVideoIdWithoutRequestingSongsOrDisabledEpisodes() =
        runTest {
            var requests = 0
            val http =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        assertEquals("https", request.url.protocol.name)
                        assertEquals("sponsor.ajay.app", request.url.host)
                        assertTrue(Regex("/api/skipSegments/[0-9a-f]{4}").matches(request.url.encodedPath))
                        if (requests == 1) assertEquals("/api/skipSegments/ca2f", request.url.encodedPath)
                        assertFalse(request.url.toString().contains(episode.id))
                        assertEquals(
                            sponsorBlockCategories,
                            request.url.parameters
                                .getAll("category")
                                ?.toSet(),
                        )
                        assertEquals("skip", request.url.parameters["actionType"])
                        respond(response)
                    },
                )
            try {
                val client = SponsorBlockClient(http)
                assertTrue(client.segments(episode, false).isEmpty())
                assertTrue(client.segments(episode.copy(musicVideoType = "MUSIC_VIDEO_TYPE_ATV"), true).isEmpty())
                assertEquals(0, requests)
                assertEquals(listOf(SponsorBlockSegment(1000, 5000)), client.segments(episode, true))
                assertEquals(client.segments(episode, true), client.segments(episode, true))
                assertEquals(1, requests)
                assertTrue(client.segments(episode.copy(id = "otherId"), true).isEmpty())
                assertEquals(2, requests)
            } finally {
                http.close()
            }
        }

    @Test
    fun failuresAndMalformedResponsesAreSilentAndCached() =
        runTest {
            for ((body, status) in listOf(
                "" to HttpStatusCode.NotFound,
                "down" to HttpStatusCode.ServiceUnavailable,
                "invalid" to HttpStatusCode.OK,
                "{}" to HttpStatusCode.OK,
            )) {
                var requests = 0
                val http =
                    HttpClient(
                        MockEngine {
                            requests++
                            respond(body, status)
                        },
                    )
                try {
                    val client = SponsorBlockClient(http)
                    assertTrue(client.segments(episode, true).isEmpty())
                    assertTrue(client.segments(episode, true).isEmpty())
                    assertEquals(1, requests)
                } finally {
                    http.close()
                }
            }
            val http = HttpClient(MockEngine { error("network unavailable") })
            try {
                assertTrue(SponsorBlockClient(http).segments(episode, true).isEmpty())
            } finally {
                http.close()
            }
        }

    @Test
    fun cancellationIsNotSwallowed() =
        runTest {
            val http = HttpClient(MockEngine { throw CancellationException("cancelled") })
            try {
                assertFailsWith<CancellationException> { SponsorBlockClient(http).segments(episode, true) }
            } finally {
                http.close()
            }
        }

    @Test
    fun playbackSkipsOnlyWhilePlayingAndEnabledAndNeverSeeksReplacementSongs() =
        runTest {
            val http = HttpClient(MockEngine { respond(response) })
            try {
                val client = SponsorBlockClient(http)
                client.segments(episode, true)
                val song = MutableStateFlow<SongItem?>(episode)
                val position = MutableStateFlow(1000L)
                val playing = MutableStateFlow(false)
                val enabled = MutableStateFlow(true)
                val seeks = mutableListOf<Long>()
                var notices = 0
                var canSeek = true
                val observer =
                    backgroundScope.launch {
                        observeSponsorBlockSkipping(
                            song,
                            position,
                            MutableStateFlow(10_000L),
                            playing,
                            enabled,
                            { canSeek },
                            seeks::add,
                            { notices++ },
                            client,
                        )
                    }
                runCurrent()
                assertTrue(seeks.isEmpty())
                playing.value = true
                runCurrent()
                assertEquals(listOf(5000L), seeks)
                position.value = 1500
                runCurrent()
                assertEquals(1, notices)
                position.value = 5000
                runCurrent()
                canSeek = false
                position.value = 2000
                runCurrent()
                assertEquals(1, seeks.size)
                canSeek = true
                position.value = 2100
                runCurrent()
                assertEquals(2, seeks.size)
                enabled.value = false
                runCurrent()
                position.value = 5000
                runCurrent()
                position.value = 1000
                runCurrent()
                assertEquals(2, seeks.size)
                song.value = episode.copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
                enabled.value = true
                runCurrent()
                position.value = 2000
                runCurrent()
                assertEquals(2, seeks.size)
                assertEquals(2, notices)
                observer.cancel()
            } finally {
                http.close()
            }
        }

    @Test
    fun changingToMusicCancelsPendingLookupWithoutSeeking() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val responseGate = CompletableDeferred<Unit>()
            val http =
                HttpClient(
                    MockEngine {
                        started.complete(Unit)
                        try {
                            responseGate.await()
                            respond(response)
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                )
            try {
                val song = MutableStateFlow<SongItem?>(episode)
                val seeks = mutableListOf<Long>()
                val observer =
                    backgroundScope.launch {
                        observeSponsorBlockSkipping(
                            song,
                            MutableStateFlow(
                                2000L,
                            ),
                            MutableStateFlow(10_000L),
                            MutableStateFlow(true),
                            MutableStateFlow(true),
                            {
                                true
                            },
                            seeks::add,
                            {},
                            SponsorBlockClient(http),
                        )
                    }
                started.await()
                song.value = episode.copy(musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
                cancelled.await()
                runCurrent()
                assertTrue(seeks.isEmpty())
                observer.cancel()
            } finally {
                http.close()
            }
        }
}
