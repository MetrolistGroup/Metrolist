package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NextTrackPreloadTriggerTest {
    @Test
    fun cancelledPreparationRestartsWhenRecommendationsExtendAnUnchangedNextTrack() =
        runTest {
            val position = MutableStateFlow(210_000L)
            val songs = MutableStateFlow(listOf(song("current"), song("next")))
            val invalidation = MutableStateFlow(0L)
            val triggers = mutableListOf<NextTrackPreloadTrigger>()
            backgroundScope.launch {
                nextTrackPreloadTriggers(
                    playbackPosition = position,
                    currentMediaItemIndex = MutableStateFlow(0),
                    songQueue = songs,
                    repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                    invalidation = invalidation,
                    currentPlaybackInstanceId = { 7L },
                    hasMore = { false },
                    stopAfterCurrentSong = { false },
                    isPreloadedTargetActive = { _, _ -> false },
                ).collect { triggers += it }
            }
            runCurrent()
            assertEquals(1, triggers.size)

            invalidation.value++
            songs.value += song("recommendation")
            runCurrent()

            assertEquals(2, triggers.size)
            assertEquals("next", triggers.last().nextVideoId)
            assertEquals(7L, triggers.last().currentPlaybackInstanceId)
            assertEquals(1L, triggers.last().invalidationGeneration)
            assertTrue(triggers.last().shouldPreload)
            assertFalse(triggers.last().hasValidPreload)

            position.value += 100L
            runCurrent()
            assertEquals(2, triggers.size)
        }

    @Test
    fun cancellingAnAlreadyPreparedPlayerRetriggersTheSameQueueTarget() =
        runTest {
            val invalidation = MutableStateFlow(0L)
            var prepared = true
            val triggers = mutableListOf<NextTrackPreloadTrigger>()
            backgroundScope.launch {
                nextTrackPreloadTriggers(
                    playbackPosition = MutableStateFlow(210_000L),
                    currentMediaItemIndex = MutableStateFlow(0),
                    songQueue = MutableStateFlow(listOf(song("current"), song("next"))),
                    repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                    invalidation = invalidation,
                    currentPlaybackInstanceId = { 7L },
                    hasMore = { false },
                    stopAfterCurrentSong = { false },
                    isPreloadedTargetActive = { instanceId, songId -> prepared && instanceId == 7L && songId == "next" },
                ).collect { triggers += it }
            }
            runCurrent()
            assertTrue(triggers.single().hasValidPreload)

            prepared = false
            invalidation.value++
            runCurrent()

            assertEquals(2, triggers.size)
            assertTrue(triggers.last().shouldPreload)
            assertFalse(triggers.last().hasValidPreload)
        }

    @Test
    fun invalidationStillHonorsRepeatOneAndThePreloadDelay() =
        runTest {
            val position = MutableStateFlow(0L)
            val mode = MutableStateFlow(Player.REPEAT_MODE_OFF)
            val invalidation = MutableStateFlow(0L)
            val triggers = mutableListOf<NextTrackPreloadTrigger>()
            backgroundScope.launch {
                nextTrackPreloadTriggers(
                    playbackPosition = position,
                    currentMediaItemIndex = MutableStateFlow(0),
                    songQueue = MutableStateFlow(listOf(song("current"), song("next"))),
                    repeatMode = mode,
                    invalidation = invalidation,
                    currentPlaybackInstanceId = { 7L },
                    hasMore = { false },
                    stopAfterCurrentSong = { false },
                    isPreloadedTargetActive = { _, _ -> false },
                ).collect { triggers += it }
            }
            runCurrent()
            invalidation.value++
            runCurrent()
            assertFalse(triggers.last().shouldPreload)

            position.value = NEXT_TRACK_PRELOAD_DELAY_MS
            runCurrent()
            assertTrue(triggers.last().shouldPreload)

            mode.value = Player.REPEAT_MODE_ONE
            invalidation.value++
            runCurrent()
            assertFalse(triggers.last().shouldPreload)
        }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
