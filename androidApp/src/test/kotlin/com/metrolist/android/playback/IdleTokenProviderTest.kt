package com.metrolist.android.playback

import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleTokenProviderTest {
    @Test
    fun releasesAfterSustainedPauseButNotWhilePlaybackIsRequested() =
        runTest {
            val active = MutableStateFlow(true)
            val provider = RecordingTokenProvider()
            backgroundScope.launch { releaseIdleTokenProvider(active, MutableStateFlow(false), provider) }
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, provider.closes)

            active.value = false
            runCurrent()
            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(0, provider.closes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, provider.closes)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, provider.closes)
        }

    @Test
    fun resumingCancelsPendingReleaseAndNextPauseGetsItsOwnGracePeriod() =
        runTest {
            val active = MutableStateFlow(false)
            val provider = RecordingTokenProvider()
            backgroundScope.launch { releaseIdleTokenProvider(active, MutableStateFlow(false), provider) }
            runCurrent()
            advanceTimeBy(59_000)
            active.value = true
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, provider.closes)

            active.value = false
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, provider.closes)
        }

    @Test
    fun resumingCancelsReleaseWaitingForTheProviderAndShutdownStillCleansUp() =
        runTest {
            val active = MutableStateFlow(false)
            val releaseAllowed = CompletableDeferred<Unit>()
            val provider = RecordingTokenProvider { releaseAllowed.await() }
            val observer = backgroundScope.launch { releaseIdleTokenProvider(active, MutableStateFlow(false), provider) }
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, provider.attempts)
            assertEquals(0, provider.closes)

            active.value = true
            runCurrent()
            releaseAllowed.complete(Unit)
            runCurrent()
            assertEquals(0, provider.closes)

            observer.cancelAndJoin()
            assertEquals(1, provider.closes)
        }

    @Test
    fun shutdownWaitsForCleanupEvenWhenThePlaybackObserverIsCancelled() =
        runTest {
            val releaseAllowed = CompletableDeferred<Unit>()
            val provider = RecordingTokenProvider { releaseAllowed.await() }
            val observer = backgroundScope.launch { releaseIdleTokenProvider(MutableStateFlow(true), MutableStateFlow(false), provider) }
            runCurrent()
            observer.cancel()
            runCurrent()
            assertEquals(1, provider.attempts)
            assertFalse(observer.isCompleted)
            releaseAllowed.complete(Unit)
            runCurrent()
            assertTrue(observer.isCompleted)
            assertEquals(1, provider.closes)
        }

    @Test
    fun preparingPlaybackCancelsIdleCleanupBeforePlayIntentReachesThePlayer() =
        runTest {
            val loading = MutableStateFlow(false)
            val provider = RecordingTokenProvider()
            backgroundScope.launch { releaseIdleTokenProvider(MutableStateFlow(false), loading, provider) }
            runCurrent()
            advanceTimeBy(59_000)
            loading.value = true
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(0, provider.closes)

            loading.value = false
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, provider.closes)
        }

    @Test
    fun idleReleaseDuringSlowIncomingPrefetchStillReachesPlaySong() =
        runTest {
            val resolving = CompletableDeferred<Unit>()
            val provider = RecordingTokenProvider { resolving.cancel() }
            backgroundScope.launch { releaseIdleTokenProvider(MutableStateFlow(false), MutableStateFlow(false), provider) }
            val song = SongItem("incoming", "Track", null, listOf(ArtistTiny(null, "Artist")), null)
            val prepared =
                async {
                    val metadata =
                        resolveIncomingPlaybackSong(
                            song.id,
                            "Service",
                            loadMetadata = {
                                delay(45_000)
                                null
                            },
                            loadPlayerInformation = { null },
                        )
                    prefetchIncomingAudio(metadata, wantVideo = false, canPlayLocally = true) { resolving.await() }
                    metadata
                }
            var playedSong: SongItem? = null
            val playSong: (SongItem) -> Unit = { playedSong = it }
            val incomingIntent = launch { playSong(prepared.await()) }
            runCurrent()
            advanceTimeBy(45_000)
            runCurrent()
            assertFalse(prepared.isCompleted)
            assertNull(playedSong)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(1, provider.closes)
            assertTrue(incomingIntent.isCompleted)
            assertFalse(incomingIntent.isCancelled)
            assertEquals(song.id, playedSong?.id)
        }

    @Test
    fun cancellingIncomingIntentDuringPrefetchDoesNotReachPlaySong() =
        runTest {
            val resolving = CompletableDeferred<Unit>()
            val song = SongItem("incoming", "Track", null, listOf(ArtistTiny(null, "Artist")), null)
            var played = false
            val incomingIntent =
                launch {
                    prefetchIncomingAudio(song, wantVideo = false, canPlayLocally = true) { resolving.await() }
                    played = true
                }
            runCurrent()
            incomingIntent.cancelAndJoin()
            assertTrue(incomingIntent.isCancelled)
            assertFalse(played)
        }

    private class RecordingTokenProvider(
        private val beforeClose: suspend () -> Unit = {},
    ) : TokenProvider {
        var attempts = 0
        var closes = 0

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult? = null

        override suspend fun close() {
            attempts++
            beforeClose()
            closes++
        }
    }
}
