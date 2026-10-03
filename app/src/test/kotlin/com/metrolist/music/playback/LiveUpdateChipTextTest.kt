package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveUpdateChipTextTest {
    @Test
    fun `titles that fit are kept whole`() {
        assertEquals("Yellow", liveUpdateChipText("Yellow"))
        assertEquals("Starboy", liveUpdateChipText("Starboy"))
        assertEquals("夜に駆ける", liveUpdateChipText("夜に駆ける"))
    }

    @Test
    fun `long titles are clipped with an ellipsis`() {
        assertEquals("Bohemi…", liveUpdateChipText("Bohemian Rhapsody"))
        assertEquals("As It…", liveUpdateChipText("As It Was"))
    }

    @Test
    fun `full-width titles get a smaller budget`() {
        assertEquals("残酷な天…", liveUpdateChipText("残酷な天使のテーゼ"))
        assertEquals("BTS…", liveUpdateChipText("BTS (방탄소년단) 봄날"))
    }

    @Test
    fun `clipping never splits a surrogate pair`() {
        assertEquals("abc…", liveUpdateChipText("abc😀 and more"))
    }
}
