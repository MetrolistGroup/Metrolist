package com.metrolist.shared.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.carousel.CarouselState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HorizontalScrollStateTest {
    @Test
    fun unmeasuredCarouselCannotScroll() {
        val state = horizontalScrollState(CarouselState { 0 })

        assertFalse(state.canScrollBackward)
        assertFalse(state.canScrollForward)
    }

    @Test
    fun nonCarouselScrollLimitsFollowScrollPosition() =
        runTest {
            val state = ScrollState(0)
            val limits = horizontalScrollState(state)
            assertFalse(limits.canScrollBackward)
            assertTrue(limits.canScrollForward)

            state.scrollTo(50)
            assertTrue(limits.canScrollBackward)
            assertTrue(limits.canScrollForward)

            state.scrollTo(0)
            assertFalse(limits.canScrollBackward)
            assertTrue(limits.canScrollForward)
        }
}
