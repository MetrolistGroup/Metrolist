package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackEndTest {
    @Test
    fun doubledNativeTimelineUsesMetadataDuration() {
        assertEquals(176_000L, correctedNativeDurationMs(176_000L, 352_000L))
        assertEquals(264_000L, correctedNativeDurationMs(176_000L, 264_000L))
        assertEquals(300_000L, correctedNativeDurationMs(180_000L, 300_000L))
        assertEquals(180_000L, correctedNativeDurationMs(180_000L, 361_000L))
        assertEquals(263_999L, correctedNativeDurationMs(176_000L, 263_999L))
        assertEquals(181_000L, correctedNativeDurationMs(176_000L, 181_000L))
        assertEquals(352_000L, correctedNativeDurationMs(0L, 352_000L))
        assertEquals(0L, correctedNativeDurationMs(176_000L, 0L))
        assertEquals(300L, correctedNativeDurationMs(500L, 300L))
        assertEquals(500L, correctedNativeDurationMs(500L, 1_000L))
    }
}
