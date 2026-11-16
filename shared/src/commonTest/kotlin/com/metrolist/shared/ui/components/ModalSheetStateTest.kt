package com.metrolist.shared.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
class ModalSheetStateTest {
    @Test
    fun retainedSheetNotifiesAndReopensAfterEveryUserDismissal() =
        runTest {
            val sheet = expandedSheet()
            val popup = PopupSession().apply { state.request(true) }
            popup.state.animate {}
            val window = ModalSheetWindowState()
            assertTrue(window.shouldMount(popup.state.targetVisible, sheet.isVisible))
            var dismissals = 0
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                sheet.observeDismissals {
                    if (popup.state.targetVisible) {
                        dismissals++
                        popup.state.request(false)
                        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            popup.state.animate { awaitCancellation() }
                        }
                    }
                }
            }

            repeat(3) { cycle ->
                var windowRemovedBeforeSpringFinished = false
                withContext(
                    frameClock {
                        if (!window.shouldMount(popup.state.targetVisible, sheet.isVisible) && sheet.isAnimationRunning) {
                            windowRemovedBeforeSpringFinished = true
                        }
                    },
                ) {
                    when (cycle) {
                        0 -> {
                            sheet.drag(MutatePriority.UserInput) { dragBy(700f) }
                            sheet.settle(0f)
                        }

                        1 -> {
                            sheet.scrollBy(700f)
                            sheet.settle(0f)
                        }

                        else -> {
                            assertTrue(sheet.dismiss())
                        }
                    }
                }
                runCurrent()
                assertEquals(cycle + 1, dismissals)
                assertTrue(windowRemovedBeforeSpringFinished)
                assertFalse(popup.state.targetVisible)
                assertTrue(popup.state.retained)
                assertTrue(popup.state.running)
                assertFalse(window.shouldMount(popup.state.targetVisible, sheet.isVisible))

                // Reopen during the popup's exit, before composition disposes the sheet state or its observer.
                popup.state.request(true)
                assertTrue(window.shouldMount(popup.state.targetVisible, sheet.isVisible))
                withContext(frameClock()) { sheet.show() }
                popup.state.animate {}
                assertTrue(sheet.isVisible)
                assertTrue(sheet.acceptsInput)
                assertFalse(sheet.isDismissedByUser)
                assertEquals(cycle + 1, dismissals)
            }
        }

    @Test
    fun programmaticHideKeepsDetailsAndEqualizerHandoffsAlive() =
        runTest {
            for (destination in listOf("details", "equalizer")) {
                val sheet = expandedSheet()
                val popup = PopupSession().apply { state.request(true) }
                popup.state.animate {}
                val window = ModalSheetWindowState()
                assertTrue(window.shouldMount(popup.state.targetVisible, sheet.isVisible))
                var windowRemovedDuringHandoff = false
                val handoff =
                    async(
                        frameClock {
                            if (!window.shouldMount(popup.state.targetVisible, sheet.isVisible) && sheet.isAnimationRunning) {
                                windowRemovedDuringHandoff = true
                            }
                        },
                    ) { if (sheet.hide()) destination else null }
                val parent =
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        sheet.observeDismissals { handoff.cancel() }
                    }
                assertEquals(destination, handoff.await())
                assertFalse(sheet.isDismissedByUser)
                assertTrue(windowRemovedDuringHandoff)
                assertFalse(window.shouldMount(popup.state.targetVisible, sheet.isVisible))
                assertTrue(popup.state.targetVisible)
                assertTrue(popup.state.retained)
                parent.cancel()
            }
        }

    @Test
    fun retainedPopupReleasesItsHiddenSheetWindowBeforeTheParentExitFinishes() =
        runTest {
            val sheet = expandedSheet()
            val popup = PopupSession().apply { state.request(true) }
            popup.state.animate {}
            val window = ModalSheetWindowState()
            assertTrue(window.shouldMount(popup.state.targetVisible, sheet.isVisible))
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                sheet.observeDismissals {
                    popup.state.request(false)
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        popup.state.animate { awaitCancellation() }
                    }
                }
            }
            sheet.drag(MutatePriority.UserInput) { dragBy(700f) }
            var windowRemovedWhileRetained = false
            withContext(
                frameClock {
                    if (!window.shouldMount(popup.state.targetVisible, sheet.isVisible) && popup.state.retained) {
                        windowRemovedWhileRetained = true
                    }
                },
            ) {
                sheet.settle(0f)
            }
            assertTrue(windowRemovedWhileRetained)
            assertFalse(popup.state.targetVisible)
            assertTrue(popup.state.retained)
            assertTrue(popup.state.running)
            assertFalse(window.shouldMount(popup.state.targetVisible, sheet.isVisible))
        }

    @Test
    fun sheetWindowMountsForMeasurementAndRemountsForANewPresentation() {
        val window = ModalSheetWindowState()
        assertFalse(window.shouldMount(requestedVisible = false, sheetVisible = false))
        assertTrue(window.shouldMount(requestedVisible = true, sheetVisible = false))
        assertTrue(window.shouldMount(requestedVisible = true, sheetVisible = true))
        assertFalse(window.shouldMount(requestedVisible = true, sheetVisible = false))
        assertFalse(window.shouldMount(requestedVisible = false, sheetVisible = false))
        assertTrue(window.shouldMount(requestedVisible = true, sheetVisible = false))
    }

    @Test
    fun closingBeforeTheFirstVisibleFrameDoesNotRetainAWindow() {
        val window = ModalSheetWindowState()
        assertTrue(window.shouldMount(requestedVisible = true, sheetVisible = false))
        assertFalse(window.shouldMount(requestedVisible = false, sheetVisible = false))
    }

    @Test
    fun explicitDismissNotifiesTheParentBeforeTheSpringTailEnds() =
        runTest {
            val sheet = expandedSheet()
            var dismissedWhileSettling = false
            withContext(
                frameClock {
                    if (sheet.isAnimationRunning && sheet.isDismissedByUser) dismissedWhileSettling = true
                },
            ) {
                assertTrue(sheet.dismiss())
            }
            assertTrue(dismissedWhileSettling)
        }

    @Test
    fun releasedDragNotifiesTheParentBeforeTheSpringTailEnds() =
        runTest {
            val sheet = expandedSheet()
            sheet.drag(MutatePriority.UserInput) {
                dragBy(700f)
                assertFalse(sheet.isDismissedByUser)
            }
            var dismissedWhileSettling = false
            withContext(
                frameClock {
                    if (sheet.isAnimationRunning && sheet.isDismissedByUser) dismissedWhileSettling = true
                },
            ) {
                sheet.settle(0f)
            }
            assertTrue(dismissedWhileSettling)
        }

    @Test
    fun rejectedDismissDoesNotNotifyTheParent() =
        runTest {
            val sheet =
                ModalSheetState(
                    SheetValue.Expanded,
                    setOf(SheetValue.Hidden, SheetValue.Expanded),
                    density = 1f,
                    spatialSpec = MotionScheme.standard().defaultSpatialSpec(),
                    confirmValueChange = { it != SheetValue.Hidden },
                ).apply { updateBounds(fullHeight = 1000f, sheetHeight = 800f) }
            assertFalse(sheet.dismiss())
            assertFalse(sheet.isDismissedByUser)
        }

    private fun frameClock(afterFrame: () -> Unit = {}) =
        object : MonotonicFrameClock {
            private var timeNanos = 0L

            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                timeNanos += 16_000_000
                return onFrame(timeNanos).also {
                    afterFrame()
                    Snapshot.sendApplyNotifications()
                }
            }
        }

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
            var hiddenWhileSettling = false
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
                                if (target == SheetValue.Hidden && !sheet.isVisible) hiddenWhileSettling = true
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
                assertTrue(visibleOffsets.filter { it.first < 999f }.all { it.second })
                assertTrue(hiddenWhileSettling, "the offscreen spring tail must not retain the modal window")
                assertFalse(sheet.isVisible)
                assertFalse(sheet.isAnimationRunning)
            }
        }
}
