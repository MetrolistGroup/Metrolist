package com.metrolist.shared.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalMaterial3Api::class)
class ModalSheetStateTest {
    @Test
    fun heldDragCanReverseAfterHiddenBecomesTheNearestAnchor() =
        runTest {
            val sheet = expandedSheet()
            sheet.drag(MutatePriority.UserInput) {
                dragBy(700f)
                assertEquals(SheetValue.Hidden, sheet.anchorsState.targetValue)
                assertTrue(sheet.isDragging)
                assertTrue(sheet.isVisible)
                assertTrue(sheet.acceptsInput)
                dragBy(-700f)
                assertEquals(200f, sheet.offset)
                assertTrue(sheet.acceptsInput)
            }
            assertFalse(sheet.isDragging)
        }

    @Test
    fun nestedScrollRemainsInteractiveAtTheHiddenAnchorUntilRelease() =
        runTest {
            val sheet = expandedSheet()
            sheet.scrollBy(800f)
            assertEquals(SheetValue.Hidden, sheet.anchorsState.targetValue)
            assertTrue(sheet.isDragging)
            assertTrue(sheet.isVisible)
            assertTrue(sheet.acceptsInput)
            sheet.scrollBy(-800f)
            assertEquals(200f, sheet.offset)
            assertTrue(sheet.acceptsInput)
        }

    private fun expandedSheet() =
        ModalSheetState(
            SheetValue.Expanded,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
            density = 1f,
            spatialSpec = MotionScheme.standard().defaultSpatialSpec(),
        ).apply { updateBounds(fullHeight = 1000f, sheetHeight = 800f) }

    @Test
    fun sheetSlidesThroughItsOffsetsToShowExpandAndHide() =
        runTest {
            val sheet =
                ModalSheetState(
                    SheetValue.Hidden,
                    setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
                    density = 1f,
                    spatialSpec = MotionScheme.standard().defaultSpatialSpec(),
                )
            sheet.updateBounds(fullHeight = 1000f, sheetHeight = 800f)
            assertEquals(1000f, sheet.offset)
            val offsets = mutableListOf<Float>()
            val visibleOffsets = mutableListOf<Pair<Float, Boolean>>()
            var acceptedWhileSettling = false
            val clock =
                object : MonotonicFrameClock {
                    private var timeNanos = 0L

                    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                        delay(16)
                        timeNanos += 16_000_000
                        return onFrame(timeNanos).also {
                            if (sheet.isAnimationRunning) {
                                val target = sheet.anchorsState.targetValue
                                val distance = abs(sheet.offset - sheet.anchorsState.anchors.positionOf(target))
                                if (target == SheetValue.Hidden || distance > 16f) assertFalse(sheet.acceptsInput)
                                if (sheet.acceptsInput) acceptedWhileSettling = true
                            }
                            offsets += sheet.offset
                            visibleOffsets += sheet.offset to sheet.isVisible
                        }
                    }
                }
            withContext(clock) {
                sheet.show()
                assertEquals(500f, sheet.offset)
                assertTrue(offsets.any { it > 500f && it < 1000f })
                assertTrue(sheet.isVisible)
                assertFalse(sheet.isAnimationRunning)
                assertTrue(acceptedWhileSettling, "taps should land during the spring's settling tail")

                offsets.clear()
                sheet.expand()
                assertEquals(200f, sheet.offset)
                assertTrue(offsets.any { it > 200f && it < 500f })

                offsets.clear()
                visibleOffsets.clear()
                assertTrue(sheet.hide())
                assertEquals(1000f, sheet.offset)
                assertTrue(offsets.any { it > 200f && it < 1000f })
                assertTrue(visibleOffsets.filter { it.first < 1000f }.all { it.second })
                assertFalse(sheet.isVisible)
                assertFalse(sheet.isAnimationRunning)
            }
        }
}
