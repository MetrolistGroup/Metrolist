package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFailureAutoSkipTest {
    @Test
    fun disablingAutoSkipRetainsEveryTerminalFailure() {
        for (category in PlaybackFailureCategory.entries) {
            assertFalse(shouldSkipAfterPlaybackFailure(false, true, true, false, category))
        }
    }

    @Test
    fun enabledAutoSkipPreservesNetworkPauseAndRepeatOneRules() {
        for (category in PlaybackFailureCategory.entries) {
            val skip = !PlaybackNetworkRecovery.handles(category)
            assertTrue(shouldSkipAfterPlaybackFailure(true, true, true, false, category) == skip)
            assertFalse(shouldSkipAfterPlaybackFailure(true, false, true, false, category))
            assertFalse(shouldSkipAfterPlaybackFailure(true, true, false, false, category))
            assertFalse(shouldSkipAfterPlaybackFailure(true, true, true, true, category))
            assertFalse(shouldSkipAfterPlaybackFailure(true, true, true, false, category, PlaybackFailureCategory.NETWORK))
            assertFalse(shouldSkipAfterPlaybackFailure(true, true, true, false, category, PlaybackFailureCategory.TIMEOUT))
        }
    }

    @Test
    fun repeatedCallbacksAdvanceOnceAndFailingQueuesStopAfterThreeSkips() {
        val budget = PlaybackFailureSkipBudget()
        assertTrue(budget.tryAdvance(1))
        assertFalse(budget.tryAdvance(1))
        assertTrue(budget.tryAdvance(2))
        assertTrue(budget.tryAdvance(3))
        assertFalse(budget.tryAdvance(4))
        budget.observeProgress(4, 0)
        budget.observeProgress(4, 29_999)
        assertFalse(budget.tryAdvance(4))
        budget.observeProgress(4, 30_000)
        assertTrue(budget.tryAdvance(4))
        budget.observeProgress(4, 31_000)
        assertTrue(budget.tryAdvance(5))
        assertTrue(budget.tryAdvance(6))
        assertFalse(budget.tryAdvance(7))
    }

    @Test
    fun restoringALatePositionDoesNotRearmTheFailureLoop() {
        val budget = PlaybackFailureSkipBudget()
        repeat(3) { assertTrue(budget.tryAdvance(it.toLong())) }
        budget.observeProgress(3, 90_000L)
        assertFalse(budget.tryAdvance(3))
        budget.observeProgress(3, 120_000L)
        assertTrue(budget.tryAdvance(3))
    }
}
