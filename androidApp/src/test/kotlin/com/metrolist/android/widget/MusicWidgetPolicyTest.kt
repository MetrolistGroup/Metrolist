package com.metrolist.android.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicWidgetPolicyTest {
    @Test
    fun compactRowReservesTransportAndTextBeforeArtwork() {
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(240, 64, 1f).layout)
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(363, 64, 1f).layout)
        assertEquals(MusicWidgetLayout.WIDE, musicWidgetSizePolicy(364, 64, 1f).layout)
        assertFalse(musicWidgetSizePolicy(415, 64, 1f).showArtwork)
        assertTrue(musicWidgetSizePolicy(416, 64, 1f).showArtwork)
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(459, 100, 2f).layout)
        assertEquals(MusicWidgetLayout.WIDE, musicWidgetSizePolicy(460, 100, 2f).layout)
        assertFalse(musicWidgetSizePolicy(511, 100, 2f).showArtwork)
        assertTrue(musicWidgetSizePolicy(512, 100, 2f).showArtwork)
    }

    @Test
    fun tallLayoutsRequireRoomForTheirControlsAndScaledText() {
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(280, 149, 1f).layout)
        assertEquals(MusicWidgetLayout.PLAYER, musicWidgetSizePolicy(280, 150, 1f).layout)
        assertEquals(MusicWidgetLayout.SQUARE, musicWidgetSizePolicy(249, 150, 1f).layout)
        assertEquals(MusicWidgetLayout.SQUARE, musicWidgetSizePolicy(240, 150, 1f).layout)
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(378, 161, 2f).layout)
        assertEquals(MusicWidgetLayout.PLAYER, musicWidgetSizePolicy(378, 162, 2f).layout)
        assertFalse(musicWidgetSizePolicy(176, 150, 1f).showArtwork)
        assertTrue(musicWidgetSizePolicy(194, 150, 1f).showArtwork)
        assertFalse(musicWidgetSizePolicy(249, 174, 2f).showArtwork)
    }

    @Test
    fun twoRowPlayersFitNarrowerFourColumnHosts() {
        for (width in listOf(250, 276, 280, 300, 360)) {
            assertEquals(MusicWidgetLayout.PLAYER, musicWidgetSizePolicy(width, 150, 1f).layout)
            assertEquals(MusicWidgetLayout.PLAYER, musicWidgetSizePolicy(width, 162, 2f).layout)
        }
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(250, 110, 1f).layout)
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(300, 110, 1f).layout)
        assertEquals(MusicWidgetLayout.WIDE, musicWidgetSizePolicy(364, 110, 1f).layout)
        assertEquals(MusicWidgetLayout.CONTROLS, musicWidgetSizePolicy(250, 161, 2f).layout)
    }
}
