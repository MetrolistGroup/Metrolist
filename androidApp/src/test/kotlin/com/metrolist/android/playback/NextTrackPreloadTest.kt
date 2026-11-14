package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.playback.QUEUE_SOURCE_RECOMMENDED
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class NextTrackPreloadTest {
    @Test
    fun manualNextConsumesOnlyReadyValidPreloadsAndFallsBackExactlyOnce() {
        for (ready in listOf(true, false)) {
            for (valid in listOf(true, false)) {
                var consumed = 0
                var advanced = 0
                advanceWithPreloadedNext(ready, consume = {
                    consumed++
                    valid
                }, advance = { advanced++ })
                assertEquals(if (ready) 1 else 0, consumed)
                assertEquals(if (ready && valid) 0 else 1, advanced)
            }
        }
    }

    @Test
    fun directChunksUseTrackBitrateAndRespectResolverLimit() {
        val resource =
            PlaybackResource(
                "id",
                "url",
                emptyMap(),
                null,
                null,
                bitrate = 128_000,
                videoBitrate = 2_000_000,
            )
        assertEquals(960_000, directMediaChunkSize(resource, video = false))
        assertEquals(1_000_000, directMediaChunkSize(resource, video = true))
        assertEquals(32_000, directMediaChunkSize(resource.copy(rangeChunkSizeBytes = 32_000), video = false))
        assertEquals(
            600_000,
            directMediaChunkSize(
                resource.copy(
                    bitrate = null,
                    contentLengthBytes = 1_000_000,
                    durationSeconds = 100,
                ),
                video = false,
            ),
        )
        assertEquals(1_048_576, directMediaChunkSize(resource.copy(bitrate = null), video = false))
        assertEquals(1, directMediaChunkSize(resource.copy(rangeChunkSizeBytes = 0), video = false))
        assertEquals(1_048_576, directMediaChunkSize(resource.copy(bitrate = Int.MAX_VALUE), video = false))
        assertEquals(
            1_048_576,
            directMediaChunkSize(resource.copy(bitrate = Int.MAX_VALUE, rangeChunkSizeBytes = Long.MAX_VALUE), video = false),
        )
        assertEquals(480_000, directMediaChunkSize(resource.copy(bitrate = 64_000), video = false))
    }

    @Test
    fun repeatAllDropsGeneratedTailButPreservesOriginalQueueAndCurrentTrack() {
        val songs =
            listOf(
                SongItem("a", "A", null, emptyList(), null),
                SongItem("b", "B", null, emptyList(), null),
                SongItem("c", "C", null, emptyList(), null, queueSourceTitle = QUEUE_SOURCE_RECOMMENDED),
                SongItem("d", "D", null, emptyList(), null, queueSourceTitle = QUEUE_SOURCE_AUTO_MIX),
            )
        assertEquals(listOf(2, 3), generatedQueueIndicesToRemoveForRepeat(songs, 1))
        assertEquals(listOf(3), generatedQueueIndicesToRemoveForRepeat(songs, 2))
    }

    @Test
    fun terminalSabrErrorsNeverRetryAndTransientLoadsHaveAFiniteBudget() {
        for (kind in SabrFailureKind.entries) {
            val error = IOException("source failed", IOException(SabrProtocolException("terminal", kind)))
            assertFalse(shouldRetryPlaybackLoad(error, errorCount = 1))
        }
        for (attempt in 1..3) {
            assertTrue(shouldRetryPlaybackLoad(SocketTimeoutException(), errorCount = attempt))
        }
        assertFalse(shouldRetryPlaybackLoad(SocketTimeoutException(), errorCount = 4))
    }

    @Test
    fun secondaryPlayerReceivesFocusOnlyAfterPrimaryRetires() {
        assertTrue(AndroidPlaybackPlayerRole.PRIMARY.handlesAudioFocus)
        assertFalse(AndroidPlaybackPlayerRole.SECONDARY.handlesAudioFocus)

        val events = mutableListOf<String>()
        transferPlaybackAudioFocus(
            retirePrimary = { events += "primary retired" },
            enablePromotedPlayer = { events += "promoted enabled" },
        )

        assertEquals(listOf("primary retired", "promoted enabled"), events)
    }

    @Test
    fun cancellingSecondaryClosesOnlyItsLifecycle() {
        val events = mutableListOf<String>()
        val currentPlaybackInstance = 7L
        val currentQueueIndex = 2
        var currentReporterStopped = false
        var secondaryReporterStopped = false
        var secondaryHasAudioFocus = false

        cancelSecondaryPlayback(
            detachPlayer = { events += "secondary detached" },
            closeSources = { events += "secondary sources closed" },
            stopReporting = {
                secondaryReporterStopped = true
                events += "secondary reporting stopped"
            },
            releasePlayer = { events += "secondary released" },
        )

        assertEquals(7L, currentPlaybackInstance)
        assertEquals(2, currentQueueIndex)
        assertFalse(currentReporterStopped)
        assertTrue(secondaryReporterStopped)
        assertFalse(secondaryHasAudioFocus)
        assertEquals(
            listOf(
                "secondary detached",
                "secondary sources closed",
                "secondary reporting stopped",
                "secondary released",
            ),
            events,
        )
    }

    @Test
    fun repeatModeSelectsTheExpectedQueueItem() {
        assertEquals(1, repeatedQueueIndex(Player.REPEAT_MODE_ONE, currentIndex = 1, lastIndex = 2))
        assertEquals(0, repeatedQueueIndex(Player.REPEAT_MODE_ALL, currentIndex = 2, lastIndex = 2))
        assertEquals(null, repeatedQueueIndex(Player.REPEAT_MODE_ALL, currentIndex = 1, lastIndex = 2))
        assertEquals(null, repeatedQueueIndex(Player.REPEAT_MODE_OFF, currentIndex = 2, lastIndex = 2))
        assertEquals(2, nextQueuedIndex(queueSize = 3, currentIndex = 1, repeatMode = Player.REPEAT_MODE_OFF))
        assertEquals(0, nextQueuedIndex(queueSize = 3, currentIndex = 2, repeatMode = Player.REPEAT_MODE_ALL))
        assertEquals(null, nextQueuedIndex(queueSize = 3, currentIndex = 2, repeatMode = Player.REPEAT_MODE_OFF))
        assertEquals(null, nextQueuedIndex(queueSize = 3, currentIndex = -1, repeatMode = Player.REPEAT_MODE_ALL))
        assertEquals(null, repeatedQueueIndex(Player.REPEAT_MODE_ALL, 2, 2, hasMore = true))
        assertEquals(2, repeatedQueueIndex(Player.REPEAT_MODE_ONE, 2, 2, hasMore = true))
        assertEquals(null, nextQueuedIndex(3, 2, Player.REPEAT_MODE_ALL, hasMore = true))
        assertEquals(2, nextQueuedIndex(3, 1, Player.REPEAT_MODE_ALL, hasMore = true))
    }

    @Test
    fun preloadOwnershipUsesTheQueuedSongOccurrenceInsteadOfCanonicalVideoId() {
        val token =
            AndroidPlaybackPlayer.PlaybackPreloadToken(
                generation = 2L,
                playerInstanceId = 4L,
                videoId = "canonical-track",
                queueSongId = "queued-video",
            )

        assertFalse(isPreloadedQueueTargetChanged(token, "queued-video"))
        assertTrue(isPreloadedQueueTargetChanged(token, "different-song"))
        assertTrue(isPreloadedQueueTargetChanged(token, null))
    }

    @Test
    fun nextTrackPreloadsAfterFiveSecondsOfPlayback() {
        assertFalse(shouldPreloadNextTrack(NEXT_TRACK_PRELOAD_DELAY_MS - 1L, "next", Player.REPEAT_MODE_OFF, false))
        assertTrue(shouldPreloadNextTrack(NEXT_TRACK_PRELOAD_DELAY_MS, "next", Player.REPEAT_MODE_OFF, false))
        assertFalse(shouldPreloadNextTrack(NEXT_TRACK_PRELOAD_DELAY_MS, null, Player.REPEAT_MODE_OFF, false))
        assertFalse(shouldPreloadNextTrack(NEXT_TRACK_PRELOAD_DELAY_MS, "next", Player.REPEAT_MODE_ONE, false))
        assertFalse(shouldPreloadNextTrack(NEXT_TRACK_PRELOAD_DELAY_MS, "next", Player.REPEAT_MODE_OFF, true))
    }

    @Test
    fun delayedCompletionFromCrossfadedTrackIsConsumedOnce() {
        val guard = CrossfadeCompletionGuard()
        guard.arm(41L)
        assertFalse(guard.hasConsumedCompletion)

        assertFalse(guard.consume(40L))
        assertTrue(guard.consume(41L))
        assertTrue(guard.hasConsumedCompletion)
        assertFalse(guard.consume(41L))
        assertTrue(guard.takeConsumedCompletion())
        assertFalse(guard.hasConsumedCompletion)
        assertFalse(guard.takeConsumedCompletion())

        guard.arm(42L)
        assertTrue(guard.consume(42L))
        guard.discardConsumedCompletion()
        assertFalse(guard.hasConsumedCompletion)
        assertFalse(guard.takeConsumedCompletion())

        guard.arm(43L)
        guard.clear()
        assertFalse(guard.hasConsumedCompletion)
        assertFalse(guard.consume(43L))

        guard.arm(44L)
        guard.arm(45L)
        assertFalse(guard.consume(44L))
        assertTrue(guard.consume(45L))
    }

    @Test
    fun promotedTrackCompletionAdvancesOnceAndNormalCompletionIsNotConsumedAfterFadeIn() {
        val guard = CrossfadeCompletionGuard()
        guard.arm(41L)
        guard.arm(42L)
        assertFalse(guard.consume(41L))
        assertTrue(guard.consume(42L))
        assertTrue(guard.takeConsumedCompletion())
        guard.clear()
        assertFalse(guard.takeConsumedCompletion())
        assertFalse(guard.consume(42L))

        guard.arm(43L)
        assertFalse(guard.takeConsumedCompletion())
        guard.clear()
        assertFalse(guard.consume(43L))
    }

    @Test
    fun crossfadeStartsOnlyInsideTheConfiguredEndWindow() {
        fun crossfade(
            positionMs: Long,
            isPlaying: Boolean = true,
            isGapless: Boolean = false,
            stopAfterCurrentSong: Boolean = false,
        ) = shouldStartAndroidCrossfade(
            playbackPositionMs = positionMs,
            durationMs = 180_000L,
            crossfadeDurationMs = 5_000L,
            isPlaying = isPlaying,
            crossfadeEnabled = true,
            isCrossfading = false,
            repeatMode = Player.REPEAT_MODE_OFF,
            isGapless = isGapless,
            stopAfterCurrentSong = stopAfterCurrentSong,
        )
        assertTrue(crossfade(176_000L))
        assertFalse(crossfade(174_999L))
        assertFalse(crossfade(176_000L, isGapless = true))
        assertFalse(crossfade(176_000L, isPlaying = false))
        assertFalse(crossfade(176_000L, stopAfterCurrentSong = true))
    }
}
