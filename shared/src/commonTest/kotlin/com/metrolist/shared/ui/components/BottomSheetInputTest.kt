package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BottomSheetInputTest {
    @Test
    fun containerMorphPreservesMiniPlayerBoundsAndBottomSpacing() {
        val window = Size(420f, 900f)
        val miniPlayer = Rect(24f, 0f, 396f, 66f)
        val collapsedBound = 150f
        for (step in 0..100) {
            val progress = step / 100f
            val bounds = playerSheetBounds(miniPlayer, window, progress)
            val translationY = (window.height - collapsedBound) * (1f - progress)
            assertEquals(24f * (1f - progress), bounds.left, 0.0001f)
            assertEquals(window.width - bounds.left, bounds.right, 0.0001f)
            assertEquals(window.height - 84f * (1f - progress), bounds.bottom + translationY, 0.0001f)
            assertTrue(bounds.width >= miniPlayer.width && bounds.height >= miniPlayer.height)
        }
        assertEquals(miniPlayer, playerSheetBounds(miniPlayer, window, -1f))
        assertEquals(Rect(Offset.Zero, window), playerSheetBounds(miniPlayer, window, 2f))
    }

    @Test
    fun materialFadeThroughUsesTheSameProgressInBothDirections() {
        for (scheme in listOf(MotionScheme.standard(), MotionScheme.expressive())) {
            val motion = PlayerSheetMotion(scheme)
            assertEquals(0f, motion.expandedAlpha(0f))
            assertEquals(1f, motion.collapsedAlpha(0f))
            assertEquals(1f, motion.expandedAlpha(1f))
            assertEquals(0f, motion.collapsedAlpha(1f))
            assertEquals(0f, motion.expandedAlpha(0.5f))
            assertEquals(0f, motion.collapsedAlpha(0.5f))
            assertTrue(motion.expandedAlpha(0.75f) in 0f..1f)
            assertTrue(motion.expandedAlpha(0.75f) > 0f)
            assertTrue(motion.collapsedAlpha(0.25f) > 0f)
            for (step in 0..100) {
                val progress = step / 100f
                assertEquals(motion.expandedAlpha(progress), motion.collapsedAlpha(1f - progress), 0.0001f)
                assertTrue(motion.expandedAlpha(progress) == 0f || motion.collapsedAlpha(progress) == 0f)
            }
        }
    }

    @Test
    fun handleDragsRemainInteractiveWhileOpeningAndClosingAnimationsBlockActions() =
        runTest {
            val sheet = expandedSheet()
            assertTrue(sheet.acceptsInput)
            sheet.isDragging = true
            sheet.dispatchRawDelta(40f)
            runCurrent()
            assertFalse(sheet.isSettledExpanded)
            assertTrue(sheet.acceptsInput)

            sheet.isDragging = false
            sheet.performFling(0f, null)
            assertFalse(sheet.acceptsInput)
            advanceUntilIdle()
            assertTrue(sheet.acceptsInput)

            sheet.collapseSoft()
            assertFalse(sheet.acceptsInput)
            advanceUntilIdle()
            assertFalse(sheet.acceptsInput)
            sheet.expandSoft()
            assertFalse(sheet.acceptsInput)
            advanceUntilIdle()
            assertTrue(sheet.acceptsInput)
            sheet.dismiss()
            assertFalse(sheet.acceptsInput)
            advanceUntilIdle()
            assertFalse(sheet.acceptsInput)
        }

    @Test
    fun listHandsTheRemainingDownwardDragToTheSheetWhenItReachesTheTop() =
        runTest {
            val sheet = expandedSheet()
            val scroll = sheet.preUpPostDownNestedScrollConnection
            assertEquals(Offset.Zero, scroll.onPreScroll(Offset(0f, 60f), NestedScrollSource.UserInput))
            assertEquals(Offset.Zero, scroll.onPostScroll(Offset(0f, 60f), Offset.Zero, NestedScrollSource.UserInput))
            assertFalse(sheet.isDragging)

            val remaining = Offset(0f, 40f)
            assertEquals(remaining, scroll.onPostScroll(Offset(0f, 20f), remaining, NestedScrollSource.UserInput))
            runCurrent()
            assertEquals(960.dp, sheet.value)
            assertTrue(sheet.isDragging)
            assertTrue(sheet.acceptsInput)

            assertEquals(remaining, scroll.onPostScroll(Offset.Zero, remaining, NestedScrollSource.UserInput))
            runCurrent()
            assertEquals(920.dp, sheet.value)
            assertTrue(sheet.acceptsInput)

            scroll.onPreFling(Velocity(0f, 500f))
            assertFalse(sheet.isDragging)
            assertEquals(COLLAPSED_ANCHOR, sheet.targetAnchor)
            assertFalse(sheet.acceptsInput)
            scroll.onPostFling(Velocity.Zero, Velocity.Zero)
            assertEquals(COLLAPSED_ANCHOR, sheet.targetAnchor)
            advanceUntilIdle()
            assertEquals(100.dp, sheet.value)
        }

    @Test
    fun aShortListDragSettlesBackToExpanded() =
        runTest {
            val sheet = expandedSheet()
            val scroll = sheet.preUpPostDownNestedScrollConnection
            scroll.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput)
            runCurrent()
            scroll.onPreFling(Velocity.Zero)
            scroll.onPostFling(Velocity.Zero, Velocity.Zero)
            advanceUntilIdle()
            assertEquals(EXPANDED_ANCHOR, sheet.targetAnchor)
            assertEquals(1000.dp, sheet.value)
            assertFalse(sheet.isDragging)
            assertTrue(sheet.acceptsInput)
        }

    @Test
    fun upwardDragsExpandTheSheetBeforeScrollingTheListAndIgnoreSideEffects() =
        runTest {
            val sheet = expandedSheet()
            val scroll = sheet.preUpPostDownNestedScrollConnection
            assertEquals(Offset.Zero, scroll.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.SideEffect))
            assertFalse(sheet.isDragging)
            scroll.onPostScroll(Offset.Zero, Offset(0f, 80f), NestedScrollSource.UserInput)
            runCurrent()
            assertEquals(920.dp, sheet.value)

            val upward = Offset(0f, -80f)
            assertEquals(upward, scroll.onPreScroll(upward, NestedScrollSource.UserInput))
            runCurrent()
            assertEquals(1000.dp, sheet.value)
            assertEquals(Offset.Zero, scroll.onPreScroll(upward, NestedScrollSource.UserInput))
            assertTrue(sheet.acceptsInput)
            scroll.onPreFling(Velocity.Zero)
            scroll.onPostFling(Velocity.Zero, Velocity.Zero)
            assertFalse(sheet.isDragging)
            assertTrue(sheet.acceptsInput)
        }

    @Test
    fun reversingPlayerMotionKeepsTheCurrentPositionAndTheSameSheet() =
        runTest {
            val sheet = expandedSheet(MotionScheme.expressive().defaultSpatialSpec())
            sheet.collapseSoft()
            advanceTimeBy(96)
            runCurrent()
            val collapsingPosition = sheet.value
            assertTrue(collapsingPosition > sheet.collapsedBound && collapsingPosition < sheet.expandedBound)

            sheet.expandSoft()
            assertEquals(collapsingPosition, sheet.value)
            assertEquals(EXPANDED_ANCHOR, sheet.targetAnchor)
            advanceUntilIdle()
            assertTrue(sheet.isExpanded)

            sheet.collapseSoft()
            advanceUntilIdle()
            assertTrue(sheet.isCollapsed)
            sheet.expandSoft()
            advanceTimeBy(96)
            runCurrent()
            val expandingPosition = sheet.value
            assertTrue(expandingPosition > sheet.collapsedBound && expandingPosition < sheet.expandedBound)

            sheet.collapseSoft()
            assertEquals(expandingPosition, sheet.value)
            advanceUntilIdle()
            assertTrue(sheet.isCollapsed)
        }

    @Test
    fun resizingDuringPlayerMotionPreservesProgressAndSettlesAtTheNewAnchor() =
        runTest {
            val sheet = expandedSheet(MotionScheme.expressive().defaultSpatialSpec())
            sheet.collapseSoft()
            advanceTimeBy(96)
            runCurrent()
            val progress = sheet.progress
            assertTrue(progress > 0f && progress < 1f)

            sheet.updateBounds(dismissed = 0.dp, expanded = 600.dp, collapsed = 80.dp, targetAnchor = COLLAPSED_ANCHOR)
            assertEquals(progress, sheet.progress, absoluteTolerance = 0.0001f)
            advanceUntilIdle()
            assertEquals(80.dp, sheet.value)
            assertTrue(sheet.isCollapsed)
            sheet.expandSoft()
            advanceUntilIdle()
            assertEquals(600.dp, sheet.value)
            assertTrue(sheet.isExpanded)
        }

    @Test
    fun queueSpringAndDragsStopAtThePeekHeightWithoutDismissingTheToolbar() =
        runTest {
            for (scheme in listOf(MotionScheme.standard(), MotionScheme.expressive())) {
                val sheet = expandedSheet(scheme.defaultSpatialSpec(), isDismissible = false)
                sheet.collapseSoft()
                repeat(80) {
                    advanceTimeBy(16)
                    runCurrent()
                    assertTrue(sheet.value >= sheet.collapsedBound)
                    assertFalse(sheet.isDismissed)
                }
                assertTrue(sheet.isCollapsed)

                sheet.isDragging = true
                sheet.dispatchRawDelta(200f)
                runCurrent()
                assertEquals(100.dp, sheet.value)
                sheet.isDragging = false
                var dismissed = false
                sheet.dismiss { dismissed = true }
                advanceUntilIdle()
                assertEquals(COLLAPSED_ANCHOR, sheet.targetAnchor)
                assertTrue(sheet.isCollapsed)
                assertFalse(sheet.isDismissed)
                assertFalse(dismissed)
            }
        }

    @Test
    fun resizingTheQueueUpdatesItsPeekBoundAndPreservesMotion() =
        runTest {
            val sheet = expandedSheet(MotionScheme.expressive().defaultSpatialSpec(), isDismissible = false)
            sheet.collapseSoft()
            advanceTimeBy(96)
            runCurrent()
            val progress = sheet.progress
            assertTrue(progress > 0f && progress < 1f)

            sheet.updateBounds(dismissed = 0.dp, expanded = 600.dp, collapsed = 80.dp, targetAnchor = COLLAPSED_ANCHOR)
            assertEquals(progress, sheet.progress, absoluteTolerance = 0.0001f)
            repeat(80) {
                advanceTimeBy(16)
                runCurrent()
                assertTrue(sheet.value >= 80.dp)
                assertFalse(sheet.isDismissed)
            }
            assertEquals(80.dp, sheet.value)

            sheet.updateBounds(dismissed = 0.dp, expanded = 600.dp, collapsed = 0.dp, targetAnchor = COLLAPSED_ANCHOR)
            assertTrue(sheet.isCollapsed)
            assertFalse(sheet.isDismissed)
            sheet.expandSoft()
            advanceUntilIdle()
            assertTrue(sheet.isExpanded)
        }

    private suspend fun TestScope.expandedSheet(
        spatialSpec: AnimationSpec<Dp> = snap(),
        isDismissible: Boolean = true,
    ): BottomSheetState {
        val position = Animatable(1000.dp, Dp.VectorConverter).apply { updateBounds(0.dp, 1000.dp) }
        val clock =
            object : MonotonicFrameClock {
                private var timeNanos = 0L

                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                    delay(16)
                    timeNanos += 16_000_000
                    return onFrame(timeNanos)
                }
            }
        val scope = CoroutineScope(coroutineContext + clock)
        return BottomSheetState(
            draggableState = DraggableState { delta -> scope.launch { position.snapTo(position.value - delta.dp) } },
            coroutineScope = scope,
            animatable = position,
            onAnchorChanged = {},
            initialCollapsedBound = 100.dp,
            initialAnchor = EXPANDED_ANCHOR,
            spatialSpec = spatialSpec,
            isDismissible = isDismissible,
        ).also { it.updateBounds(dismissed = 0.dp, expanded = 1000.dp, collapsed = 100.dp, targetAnchor = EXPANDED_ANCHOR) }
    }
}
