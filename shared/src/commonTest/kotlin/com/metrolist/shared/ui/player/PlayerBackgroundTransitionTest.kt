package com.metrolist.shared.ui.player

import coil3.ColorImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlayerBackgroundTransitionTest {
    @Test
    fun preparedTrackChangesAnimateAndRetainTheOutgoingDecode() {
        val first = PlayerTrackArtwork("first", image = ColorImage())
        val second = PlayerTrackArtwork("second", image = ColorImage())

        assertSame(first, retainPlayerBackgroundArtwork("first", second, first))
        assertSame(second, retainPlayerBackgroundArtwork("second", second, null))
        val transition = playerBackgroundTransition("second", "first", PlayerArtworkCrossfade())
        assertEquals(PlayerBackgroundTransition.Animate("second"), transition)

        val third = PlayerTrackArtwork("third", image = ColorImage())
        assertSame(first, retainPlayerBackgroundArtwork("first", third, first))
        assertSame(second, retainPlayerBackgroundArtwork("second", third, second))
        assertEquals(
            PlayerBackgroundTransition.Animate("third"),
            playerBackgroundTransition("third", "second", PlayerArtworkCrossfade()),
        )
    }

    @Test
    fun audioCrossfadesSeekToPlaybackProgressAndFinishWithoutAnotherFade() {
        for (progress in listOf(-0.1f, 0f, 0.4f, 1f, 1.1f)) {
            val transition =
                playerBackgroundTransition("first", "first", PlayerArtworkCrossfade("second") { progress })
            assertEquals(PlayerBackgroundTransition.Seek("second", progress.coerceIn(0f, 1f)), transition)
        }
        assertEquals(
            PlayerBackgroundTransition.Snap("second"),
            playerBackgroundTransition("second", "second", PlayerArtworkCrossfade()),
        )
    }

    @Test
    fun matchingCoversDoNotRestartTheFadeAndResolutionUpdatesReuseTheLayer() {
        val first = PlayerTrackArtwork("same", image = ColorImage(width = 720, height = 720))
        val larger = PlayerTrackArtwork("same", image = ColorImage(width = 1600, height = 1600))
        assertSame(larger, retainPlayerBackgroundArtwork("same", larger, first))
        assertEquals(
            PlayerBackgroundTransition.Snap("same"),
            playerBackgroundTransition("same", "same", PlayerArtworkCrossfade("same") { 0.5f }),
        )
        assertNull(retainPlayerBackgroundArtwork("unrelated", larger, first))
    }
}
