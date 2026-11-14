package com.metrolist.shared.ui.screens

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtistActionWidthsTest {
    @Test
    fun widthsFitAtEveryPixelIncludingNarrowRowsAndHugeLabels() {
        for (scale in listOf(0.75f, 1f, 1.25f, 1.5f, 2.625f, 3f, 3.5f)) {
            with(Density(scale)) {
                for (available in 0..2048) {
                    for ((subscribeLabel, radioLabel) in listOf(0 to 0, 80 to 40, 652 to 40, 80 to 974, 10_000 to 10_000)) {
                        for (showSubscribe in listOf(false, true)) {
                            val widths = artistActionWidths(available, subscribeLabel, radioLabel, showSubscribe)
                            val itemWidths =
                                listOf(
                                    widths.subscribeWidth,
                                    widths.separation,
                                    widths.radioWidth,
                                    widths.shuffleWidth,
                                    widths.playWidth,
                                ).map { it.roundToPx() }
                            val total = itemWidths.sum() + widths.spacing.roundToPx() * if (showSubscribe) 4 else 2
                            assertTrue(
                                itemWidths.all { it >= 0 },
                                "$scale, $available, $subscribeLabel, $radioLabel, $showSubscribe: $widths",
                            )
                            assertTrue(total <= available, "$scale, $available, $subscribeLabel, $radioLabel, $showSubscribe: $total")
                            if (!showSubscribe) {
                                assertEquals(0.dp, widths.subscribeWidth)
                                assertEquals(0.dp, widths.separation)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun normalWidthsKeepTheExistingAppearance() =
        with(Density(1f)) {
            assertEquals(
                ArtistActionWidths(104.dp, 176.dp, 96.dp, 48.dp, 160.dp, 4.dp, true),
                artistActionWidths(600, 80, 40, showSubscribe = true),
            )
            assertEquals(
                ArtistActionWidths(104.dp, 28.dp, 48.dp, 48.dp, 156.dp, 4.dp, false),
                artistActionWidths(400, 80, 40, showSubscribe = true),
            )
        }

    @Test
    fun longLabelsShrinkSubscribeAndHideTheRadioLabelBeforeShrinkingPlayback() =
        with(Density(1f, fontScale = 3f)) {
            val widths = artistActionWidths(320, 652, 974, showSubscribe = true)
            assertFalse(widths.labelRadio)
            assertEquals(116.dp, widths.subscribeWidth)
            assertEquals(28.dp, widths.separation)
            assertEquals(48.dp, widths.radioWidth)
            assertEquals(48.dp, widths.shuffleWidth)
            assertEquals(64.dp, widths.playWidth)
        }
}
