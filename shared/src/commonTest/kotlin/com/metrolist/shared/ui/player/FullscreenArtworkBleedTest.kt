package com.metrolist.shared.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullscreenArtworkBleedTest {
    private val width = 64
    private val artHeight = 64
    private val height = 64 * 3

    private fun render(
        canvas: IntArray,
        tone: Int,
    ): IntArray {
        var rows = IntArray(0)
        runTest { rows = renderBleedRows(canvas, width, height, width.toFloat(), 0, height - 1, tone) }
        return rows
    }

    private fun stripes() = IntArray(width * artHeight) { if ((it % width) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }

    @Test
    fun toneStaysDarkEnoughForWhiteControlsOnLightCovers() {
        val white = IntArray(width * artHeight) { 0xFFFFFFFF.toInt() }

        assertTrue(Color(bleedTone(white)).luminance() < 0.2f)
    }

    @Test
    fun toneComesFromTheShadowsNotTheHighlights() {
        val art = IntArray(100) { if (it < 50) 0xFF200000.toInt() else 0xFFFFFFFF.toInt() }

        assertTrue(Color(bleedTone(art)).luminance() < 0.1f)
    }

    @Test
    fun belowTheCoverOnlyItsBottomRowsAreStretchedSoNothingLargeIsReflected() {
        val art = IntArray(width * artHeight) { 0xFF000000.toInt() or ((it / width) * 4 * 0x010101) }
        val canvas = bleedCanvas(art, width, artHeight, height)

        fun gray(row: Int) = canvas[row * width] and 0xFF
        assertEquals(gray(artHeight - 1), gray(artHeight))
        val stripTop = artHeight - (0.18f * artHeight).roundToInt()
        assertTrue((artHeight until height).all { gray(it) in (stripTop * 4 - 4)..((artHeight - 1) * 4 + 4) })
    }

    @Test
    fun rowsAboveTheBlurStartStaySharpAndTheBottomEndsNearTheTone() {
        val canvas = bleedCanvas(stripes(), width, artHeight, height)
        val tone = 0xFF336699.toInt()
        val out = render(canvas, tone)

        val sharpRows = (0.70f * width).toInt() * width
        assertContentEquals(canvas.copyOfRange(0, sharpRows), out.copyOfRange(0, sharpRows))
        val last = out[out.size - 1]
        listOf(16, 8, 0).forEach { shift ->
            assertTrue(abs(((last shr shift) and 0xFF) - ((tone shr shift) and 0xFF)) < 0.15f * 255)
        }
    }

    @Test
    fun blurGrowsGraduallyWithoutAVisibleStepWhereItStarts() {
        val edge = IntArray(width * artHeight) { if (it % width < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val canvas = bleedCanvas(edge, width, artHeight, height)
        val out = render(canvas, 0xFF000000.toInt())

        // The steepest slope across the edge is what blur removes; it must fall steadily from where the blur starts.
        val slope =
            IntArray(height) { y ->
                (0 until width - 1).maxOf { x -> abs((out[y * width + x] and 0xFF) - (out[y * width + x + 1] and 0xFF)) }
            }
        val start = (0.70f * width).toInt()
        assertEquals(slope[0], slope[start])
        assertTrue((start until height - 1).all { slope[it + 1] <= slope[it] + 1 })
        assertTrue((start until height - 1).all { slope[it] - slope[it + 1] < slope[0] / 8 })
        assertTrue(slope[height - 1] < slope[0] / 4)
    }

    @Test
    fun renderingOnlyTheLowerRowsMatchesTheSameRowsOfAFullRender() {
        val canvas = bleedCanvas(stripes(), width, artHeight, height)
        val tone = 0xFF336699.toInt()
        val full = render(canvas, tone)
        val first = (1.2f * width).toInt()
        var lower = IntArray(0)
        runTest { lower = renderBleedRows(canvas, width, height, width.toFloat(), first, height - 1, tone) }

        assertContentEquals(full.copyOfRange(first * width, full.size), lower)
    }
}
