package com.metrolist.shared.ui.utils

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarouselCaptionTest {
    @Test
    fun captionsFitInsideMasksAsItemsShrinkAndMoveInEitherDirection() {
        for (width in listOf(104, 128, 150, 160, 180, 200, 280, 320)) {
            for (visibleWidth in 0..width) {
                for (left in listOf(0f, (width - visibleWidth) / 2f, (width - visibleWidth).toFloat())) {
                    val mask = Rect(left, 0f, left + visibleWidth, 160f)
                    val caption = carouselCaptionLayout(mask, width)
                    assertTrue(caption.width >= width / 2)
                    if (caption.alpha > 0f) {
                        assertTrue(caption.left >= mask.left)
                        assertTrue(caption.left + caption.width <= mask.right)
                    } else {
                        assertTrue(visibleWidth <= width / 2 + 1)
                    }
                }
            }
        }
    }

    @Test
    fun captionsFadeOnlyWhenWideEnoughAndHandleEmptyInitialMasks() {
        assertEquals(CarouselCaptionLayout(0, 280, 1f), carouselCaptionLayout(Rect(0f, 0f, 280f, 160f), 280))
        assertEquals(CarouselCaptionLayout(35, 210, 0.5f), carouselCaptionLayout(Rect(35f, 0f, 245f, 160f), 280))
        assertEquals(CarouselCaptionLayout(70, 140, 0f), carouselCaptionLayout(Rect(70f, 0f, 210f, 160f), 280))
        assertEquals(CarouselCaptionLayout(0, 140, 0f), carouselCaptionLayout(Rect.Zero, 280))
        assertEquals(CarouselCaptionLayout(0, 0, 0f), carouselCaptionLayout(Rect.Zero, 0))
    }
}
