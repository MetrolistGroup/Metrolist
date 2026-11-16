package com.metrolist.shared.ui.navigation

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumArtworkOverlayBoundsTest {
    private val resting = Rect(100f, 400f, 260f, 560f)
    private val visible = Rect(130f, 400f, 230f, 560f)

    @Test
    fun verticalMotionKeepsTheCarouselMaskWidth() {
        assertEquals(
            Rect(130f, 380f, 230f, 560f),
            albumArtworkOverlayBounds(resting.translate(0f, -20f), resting, visible),
        )
    }

    @Test
    fun flyingArtworkCanExpandBeyondItsRestingSlot() {
        assertEquals(
            Rect(50f, 100f, 390f, 560f),
            albumArtworkOverlayBounds(Rect(20f, 100f, 420f, 500f), resting, visible),
        )
    }

    @Test
    fun settledArtworkUsesTheExactVisibleMask() {
        assertEquals(visible, albumArtworkOverlayBounds(resting, resting, visible))
    }
}
