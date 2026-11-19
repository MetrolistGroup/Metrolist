package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RepeatMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMediaControlsTest {
    @Test
    fun firstLiveTrackCannotGoBackEvenAfterPlaybackStarts() {
        assertFalse(controls(currentIndex = 0, positionMs = 4_000L, isLive = true).previous)
    }

    @Test
    fun liveTrackCanGoBackWhenQueueHasPreviousTrack() {
        assertTrue(controls(currentIndex = 1, positionMs = 4_000L, isLive = true).previous)
    }

    @Test
    fun firstRegularTrackCanRestartOnceItHasProgressed() {
        assertFalse(controls(currentIndex = 0, positionMs = 0L, isLive = false).previous)
        assertTrue(controls(currentIndex = 0, positionMs = 4_000L, isLive = false).previous)
    }

    private fun controls(
        currentIndex: Int,
        positionMs: Long,
        isLive: Boolean,
    ) = desktopMediaControls(
        hasTrack = true,
        loading = false,
        guest = false,
        queueSize = 2,
        currentIndex = currentIndex,
        positionMs = positionMs,
        isLiveStream = isLive,
        repeatMode = RepeatMode.OFF,
        autoLoadMore = false,
    )
}
