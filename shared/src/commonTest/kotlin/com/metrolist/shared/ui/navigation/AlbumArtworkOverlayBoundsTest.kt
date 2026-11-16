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
            Rect(130f, 380f, 230f, 540f),
            albumArtworkOverlayBounds(resting.translate(0f, -20f), resting, visible),
        )
    }

    @Test
    fun flightKeepsTheFullCoverThenGraduallyRestoresTheCarouselMask() {
        val flight = Rect(20f, 100f, 420f, 500f)
        assertEquals(flight, albumArtworkOverlayBounds(flight, resting, visible))
        assertEquals(
            Rect(90f, 350f, 270f, 550f),
            albumArtworkOverlayBounds(Rect(80f, 350f, 280f, 550f), resting, visible),
        )
        assertEquals(
            Rect(110f, 390f, 250f, 570f),
            albumArtworkOverlayBounds(Rect(90f, 390f, 270f, 570f), resting, visible),
        )
        assertEquals(visible, albumArtworkOverlayBounds(resting, resting, visible))
    }

    @Test
    fun artworkAwayFromItsSlotKeepsTheFullCoverAtTheRestingSize() {
        for (flight in listOf(resting.translate(300f, -300f), resting.translate(0f, -300f))) {
            assertEquals(flight, albumArtworkOverlayBounds(flight, resting, visible))
        }
    }

    @Test
    fun verticallyAlignedFlightRestoresTheMaskAsItEntersTheCarouselRow() {
        assertEquals(
            Rect(115f, 305f, 245f, 465f),
            albumArtworkOverlayBounds(resting.translate(0f, -95f), resting, visible),
        )
        assertEquals(
            Rect(130f, 320f, 230f, 480f),
            albumArtworkOverlayBounds(resting.translate(0f, -80f), resting, visible),
        )
    }

    @Test
    fun settledArtworkUsesTheExactVisibleMask() {
        assertEquals(visible, albumArtworkOverlayBounds(resting, resting, visible))
    }
}
