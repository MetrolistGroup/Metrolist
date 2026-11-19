package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsCardReconstructionTest {
    @Test fun selectedTemplateDoesNotEraseCustomizations() {
        val style = LyricsImageStyle().withTemplate(LyricsCardTemplate.EDITORIAL).copy(textScale = 0.75f)
        assertEquals(style, style.withTemplate(LyricsCardTemplate.EDITORIAL))
    }
    @Test fun templatesAreDistinct() {
        val base = LyricsImageStyle()
        assertTrue(base.withTemplate(LyricsCardTemplate.EDITORIAL).backgroundColor != base.backgroundColor)
        assertEquals(TextAlign.Left, base.withTemplate(LyricsCardTemplate.EDITORIAL).alignment)
        assertEquals(LyricsCardTemplate.SPOTLIGHT, base.withTemplate(LyricsCardTemplate.SPOTLIGHT).template)
    }
    @Test fun motionLoopsExactly() {
        assertEquals(lyricsGlowPositions(0, 6000), lyricsGlowPositions(6000, 6000))
        assertEquals(lyricsGlowPositions(1500, 6000), lyricsGlowPositions(7500, 6000))
        assertTrue(lyricsGlowPositions(0, 6000) != lyricsGlowPositions(1500, 6000))
    }
    @Test fun allLayoutsHavePositiveTextBounds() {
        for (template in LyricsCardTemplate.entries) for (scale in listOf(0.6f,1f,1.35f)) {
            val layout = lyricsImageLayout(scale, template)
            assertTrue(layout.lyricsWidth > 0 && layout.lyricsHeight > 0)
        }
    }
}
