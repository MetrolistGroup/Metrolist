package com.metrolist.shared.youtube.benchmark

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackBenchmarkRunnerTest {
    @Test
    fun cancellationDuringPreparationOrPlaybackReleasesThePlayerBeforeAnotherRun() =
        runTest {
            for (cancelDuringPrepare in listOf(true, false)) {
                val entered = Channel<Unit>(Channel.UNLIMITED)
                var playersClosed = 0
                var resolversClosed = 0
                var playersCreated = 0
                val client = HttpClient(MockEngine { error("Benchmark test must not make network requests") })
                val runner =
                    PlaybackBenchmarkRunner(
                        environmentFor = {
                            PlaybackBenchmarkEnvironment(
                                httpClient = client,
                                resolverFor = {
                                    object : StreamResolver {
                                        override suspend fun resolve(
                                            videoId: String,
                                            hints: ContentHints,
                                            audioQuality: AudioQuality,
                                            sourceSong: SongItem?,
                                        ) = PlaybackResource(videoId, "https://example.invalid/audio", emptyMap(), null, null)

                                        override suspend fun refresh(
                                            videoId: String,
                                            hints: ContentHints,
                                            audioQuality: AudioQuality,
                                            sourceSong: SongItem?,
                                        ) = resolve(videoId, hints, audioQuality, sourceSong)

                                        override suspend fun close() {
                                            currentCoroutineContext().ensureActive()
                                            resolversClosed++
                                        }
                                    }
                                },
                                playerFactory = {
                                    assertEquals(playersCreated, playersClosed)
                                    playersCreated++
                                    object : PlaybackBenchmarkPlayer {
                                        override val failure: Throwable? = null

                                        override suspend fun prepare(
                                            resource: PlaybackResource,
                                            httpClient: HttpClient,
                                            onResponseStatus: (Int) -> Unit,
                                        ): Boolean {
                                            if (cancelDuringPrepare) {
                                                entered.send(Unit)
                                                awaitCancellation()
                                            }
                                            return true
                                        }

                                        override suspend fun play() {
                                            entered.send(Unit)
                                            awaitCancellation()
                                        }

                                        override suspend fun seekTo(positionMs: Long) = Unit

                                        override suspend fun currentPosition() = 0L

                                        override suspend fun duration() = 180_000L

                                        override suspend fun isPlaying() = true

                                        override suspend fun wantsPlayback() = true

                                        override suspend fun close() {
                                            withContext(Dispatchers.Default) {
                                                currentCoroutineContext().ensureActive()
                                                playersClosed++
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    )
                try {
                    val first = launch { runner.run(config) }
                    entered.receive()
                    assertTrue(runner.progress.value.running)
                    runner.cancel()
                    first.join()
                    assertEquals(1, playersClosed)
                    assertEquals(1, resolversClosed)
                    assertFalse(runner.progress.value.running)
                    assertTrue(runner.progress.value.cancelled)

                    val second = launch { runner.run(config) }
                    entered.receive()
                    val replacement = launch { runner.run(config) }
                    entered.receive()
                    second.join()
                    replacement.cancelAndJoin()
                    assertEquals(3, playersCreated)
                    assertEquals(playersCreated, playersClosed)
                    assertEquals(playersCreated, resolversClosed)
                    assertFalse(runner.progress.value.running)
                } finally {
                    client.close()
                }
            }
        }

    private val config =
        PlaybackBenchmarkConfig(
            samples = PlaybackBenchmarkCase.entries.map { PlaybackBenchmarkSample(it.name, it) },
            selectedProfiles = emptyList(),
        )
}
