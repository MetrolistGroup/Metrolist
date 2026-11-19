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

    @Test
    fun repeatAllEnablesPreviousAtTheFirstSeekableTrackOnlyWhenAnotherTrackExists() {
        assertTrue(controls(currentIndex = 0, positionMs = 0L, isLive = false, repeatMode = RepeatMode.ALL).previous)
        assertFalse(controls(currentIndex = 0, positionMs = 0L, isLive = true, repeatMode = RepeatMode.ALL).previous)
        assertFalse(controls(currentIndex = 0, positionMs = 0L, isLive = false, repeatMode = RepeatMode.ALL, queueSize = 1).previous)
        assertFalse(controls(currentIndex = 0, positionMs = 0L, isLive = false, repeatMode = RepeatMode.ONE).previous)
    }

    private fun controls(
        currentIndex: Int,
        positionMs: Long,
        isLive: Boolean,
        repeatMode: RepeatMode = RepeatMode.OFF,
        queueSize: Int = 2,
    ) = desktopMediaControls(
        hasTrack = true,
        loading = false,
        guest = false,
        queueSize = queueSize,
        currentIndex = currentIndex,
        positionMs = positionMs,
        isLiveStream = isLive,
        repeatMode = repeatMode,
        autoLoadMore = false,
    )
}
