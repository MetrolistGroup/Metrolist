package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsAnimationFramesTest {
    @Test
    fun hidingCancelsPendingFramesAndResumingUsesTheCurrentFrame() =
        runTest {
            val clock = BroadcastFrameClock()
            val visible = MutableStateFlow(false)
            val frames = mutableListOf<Long>()
            backgroundScope.launch(clock) {
                animateLyricsFrames(visible) { frames += it }
            }
            runCurrent()
            assertFalse(clock.hasAwaiters)

            visible.value = true
            runCurrent()
            assertTrue(clock.hasAwaiters)
            clock.sendFrame(1_000_000L)
            runCurrent()
            assertEquals(listOf(1L), frames)
            assertTrue(clock.hasAwaiters)

            visible.value = false
            runCurrent()
            assertFalse(clock.hasAwaiters)
            clock.sendFrame(30_000_000_000L)
            runCurrent()
            assertEquals(listOf(1L), frames)

            visible.value = true
            runCurrent()
            assertTrue(clock.hasAwaiters)
            clock.sendFrame(60_000_000_000L)
            runCurrent()
            assertEquals(listOf(1L, 60_000L), frames)
        }

    @Test
    fun resumingAfterAnExitExpiresPublishesOneCurrentFrame() =
        runTest {
            val clock = BroadcastFrameClock()
            val visible = MutableStateFlow(true)
            val frames = mutableListOf<Long>()
            var exiting = true
            backgroundScope.launch(clock) {
                animateLyricsFrames(visible, shouldAnimate = { exiting }) { frames += it }
            }
            runCurrent()
            clock.sendFrame(1_000_000L)
            runCurrent()
            assertTrue(clock.hasAwaiters)

            visible.value = false
            runCurrent()
            assertFalse(clock.hasAwaiters)
            exiting = false
            visible.value = true
            runCurrent()
            assertTrue(clock.hasAwaiters)
            clock.sendFrame(15_000_000_000L)
            runCurrent()
            assertEquals(listOf(1L, 15_000L), frames)
            assertFalse(clock.hasAwaiters)
        }

    @Test
    fun finishingAnExitAnimationStopsRequestingFrames() =
        runTest {
            val clock = BroadcastFrameClock()
            val frames = mutableListOf<Long>()
            val job =
                launch(clock) {
                    animateLyricsFrames(MutableStateFlow(true), shouldAnimate = { frames.size < 2 }) { frames += it }
                }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(16_000_000L)
            runCurrent()

            assertEquals(listOf(0L, 16L), frames)
            assertFalse(clock.hasAwaiters)
            job.cancel()
        }
}
