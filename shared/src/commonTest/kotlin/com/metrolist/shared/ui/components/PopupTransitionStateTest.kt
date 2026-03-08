package com.metrolist.shared.ui.components

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopupTransitionStateTest {
    @Test
    fun completionReleasesOpeningInputAndRemovesTheClosingPopup() =
        runTest {
            val state = PopupTransitionState()
            state.request(true)
            state.animate {
                assertFalse(state.acceptsInput)
                assertTrue(state.retained)
            }
            assertTrue(state.acceptsInput)
            state.request(false)
            state.animate {
                assertFalse(state.acceptsInput)
                assertTrue(state.retained)
            }
            assertFalse(state.running)
            assertFalse(state.retained)
        }

    @Test
    fun cancellingAnOpeningAnimationReleasesItsInputGuard() =
        runTest {
            val state = PopupTransitionState()
            state.request(true)
            val opening = launch(start = CoroutineStart.UNDISPATCHED) { state.animate { awaitCancellation() } }
            assertFalse(state.acceptsInput)
            opening.cancelAndJoin()
            assertTrue(state.acceptsInput)
            assertTrue(state.retained)
        }

    @Test
    fun dismissalDuringOpeningCannotReleaseTheClosingGuard() =
        runTest {
            val state = PopupTransitionState()
            state.request(true)
            val opening = launch(start = CoroutineStart.UNDISPATCHED) { state.animate { awaitCancellation() } }
            state.request(false)
            opening.cancelAndJoin()
            assertTrue(state.running)
            assertFalse(state.acceptsInput)
            val closing = launch(start = CoroutineStart.UNDISPATCHED) { state.animate { awaitCancellation() } }
            closing.cancelAndJoin()
            assertFalse(state.running)
            assertFalse(state.retained)
        }

    @Test
    fun reopeningDuringExitWaitsForTheNewAnimation() =
        runTest {
            val state = PopupTransitionState()
            state.request(true)
            state.animate {}
            state.request(false)
            val closing = launch(start = CoroutineStart.UNDISPATCHED) { state.animate { awaitCancellation() } }
            state.request(true)
            closing.cancelAndJoin()
            assertFalse(state.acceptsInput)
            state.animate {}
            assertTrue(state.acceptsInput)
        }

    @Test
    fun recompositionWithTheSameRequestDoesNotRestartOrLatchTheGuard() =
        runTest {
            val state = PopupTransitionState()
            state.request(true)
            state.animate {
                repeat(5) { assertFalse(state.request(true)) }
                assertFalse(state.acceptsInput)
            }
            assertFalse(state.request(true))
            assertTrue(state.acceptsInput)
        }

    @Test
    fun disposalAndLateAnimationCancellationCannotRetainAnOverlay() =
        runTest {
            val state = PopupTransitionState()
            state.request(true)
            val opening = launch(start = CoroutineStart.UNDISPATCHED) { state.animate { awaitCancellation() } }
            state.dispose()
            opening.cancelAndJoin()
            assertFalse(state.running)
            assertFalse(state.retained)
            assertFalse(state.targetVisible)
            state.request(true)
            state.animate {}
            assertTrue(state.acceptsInput)
        }

    @Test
    fun failedAnimationReleasesTheGuardAndPropagatesTheFailure() =
        runTest {
            val state = PopupTransitionState()
            state.request(true)
            assertFailsWith<IllegalStateException> { state.animate { error("animation failed") } }
            assertTrue(state.acceptsInput)
        }
}
