package com.metrolist.shared.api.artwork

import com.metrolist.shared.settings.AnimatedCoverResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimatedCoverSelectionTest {
    private fun select(
        canvas: Boolean,
        covers: Boolean,
        order: List<CanvasContentSource> = CanvasContentSource.defaultOrder,
    ) = selectAnimatedCoverUrl(
        canvasUrl = "spotify",
        musicVideoUrl = "youtube",
        animatedCoverUrl = "apple",
        canvasState = CanvasArtworkState.AVAILABLE,
        canvasEnabled = canvas,
        animatedCoversEnabled = covers,
        contentOrder = order,
    )

    @Test
    fun canvasPlaysWithoutAnimatedArtwork() {
        assertEquals("youtube", select(canvas = true, covers = false))
        assertEquals(
            "spotify",
            select(canvas = true, covers = false, order = listOf(CanvasContentSource.SPOTIFY_CANVAS, CanvasContentSource.APPLE_MUSIC)),
        )
    }

    @Test
    fun animatedArtworkPlaysWithoutCanvas() {
        assertEquals("apple", select(canvas = false, covers = true))
    }

    @Test
    fun nothingPlaysWhenBothAreOff() {
        assertNull(select(canvas = false, covers = false))
    }

    @Test
    fun contentOrderDecidesWhenBothAreOn() {
        assertEquals("youtube", select(canvas = true, covers = true))
        assertEquals(
            "apple",
            select(canvas = true, covers = true, order = listOf(CanvasContentSource.APPLE_MUSIC, CanvasContentSource.YOUTUBE_MUSIC_VIDEO)),
        )
    }

    @Test
    fun tallClipUsesItsOwnCacheKey() {
        val resolution = AnimatedCoverResolution.MEDIUM_720
        assertEquals(resolution.toString(), animatedCoverCacheQuality(resolution, tall = false))
        assertEquals("$resolution:tall", animatedCoverCacheQuality(resolution, tall = true))
    }
}
