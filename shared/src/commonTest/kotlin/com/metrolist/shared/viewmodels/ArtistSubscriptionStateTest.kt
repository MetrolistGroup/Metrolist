package com.metrolist.shared.viewmodels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistSubscriptionStateTest {
    @Test
    fun toggleIsImmediateIgnoresRepeatedTapsAndCelebratesOnlyConfirmedSubscribe() =
        runTest {
            val state = ArtistSubscriptionState()
            for (subscribing in listOf(true, false)) {
                val response = CompletableDeferred<Boolean>()
                var calls = 0
                val before = state.subscribeSuccessCount
                val job =
                    state.toggle(this, {
                        calls++
                        assertEquals(subscribing, it)
                        response.await()
                    }, { error("Unexpected failure") })!!
                assertEquals(subscribing, state.isSubscribed)
                assertEquals(before, state.subscribeSuccessCount)
                assertNull(state.toggle(this, { error("Duplicate request") }, { error("Unexpected failure") }))
                response.complete(true)
                job.join()
                assertEquals(1, calls)
                assertEquals(subscribing, state.isSubscribed)
                assertEquals(1, state.subscribeSuccessCount)
            }
        }

    @Test
    fun failuresRestoreStateBeforeReportingAnErrorAndAllowRetry() =
        runTest {
            for (initiallySubscribed in listOf(false, true)) {
                for (throws in listOf(false, true)) {
                    val state = ArtistSubscriptionState()
                    state.applyBrowse(initiallySubscribed, state.revision)
                    val response = CompletableDeferred<Boolean>()
                    var errors = 0
                    val job =
                        state.toggle(this, {
                            response.await()
                            if (throws) error("Offline") else false
                        }, {
                            assertEquals(initiallySubscribed, state.isSubscribed)
                            errors++
                        })!!
                    assertEquals(!initiallySubscribed, state.isSubscribed)
                    response.complete(false)
                    job.join()
                    assertEquals(initiallySubscribed, state.isSubscribed)
                    assertEquals(1, errors)
                    assertEquals(0, state.subscribeSuccessCount)
                    state.toggle(this, { true }, { error("Unexpected failure") })!!.join()
                    assertEquals(!initiallySubscribed, state.isSubscribed)
                }
            }
        }

    @Test
    fun cancellationRevertsWithoutErrorOrCelebrationAndAllowsRetry() =
        runTest {
            val state = ArtistSubscriptionState()
            val response = CompletableDeferred<Boolean>()
            val job = state.toggle(this, { response.await() }, { error("Cancellation is not a failure") })!!
            assertTrue(state.isSubscribed)
            job.cancelAndJoin()
            assertFalse(state.isSubscribed)
            assertEquals(0, state.subscribeSuccessCount)
            state.toggle(this, { true }, { error("Unexpected failure") })!!.join()
            assertTrue(state.isSubscribed)
            assertEquals(1, state.subscribeSuccessCount)
        }

    @Test
    fun overlappingBrowseCannotReplaceSuccessfulToggleInEitherResponseOrder() {
        for (initiallySubscribed in listOf(false, true)) {
            for (browseStartsBeforeToggle in listOf(false, true)) {
                for (browseFinishesBeforeToggle in listOf(false, true)) {
                    val state = ArtistSubscriptionState()
                    state.applyBrowse(initiallySubscribed, state.revision)
                    val beforeToggle = state.revision
                    val requested = state.beginToggle()!!
                    val browseRevision = if (browseStartsBeforeToggle) beforeToggle else state.revision
                    assertEquals(!initiallySubscribed, requested)
                    assertNull(state.beginToggle())

                    if (browseFinishesBeforeToggle) {
                        state.applyBrowse(initiallySubscribed, browseRevision)
                        assertEquals(requested, state.isSubscribed)
                        state.finishToggle(requested, succeeded = true)
                    } else {
                        state.finishToggle(requested, succeeded = true)
                        state.applyBrowse(initiallySubscribed, browseRevision)
                    }

                    assertEquals(requested, state.isSubscribed)
                    assertEquals(initiallySubscribed, state.beginToggle())
                }
            }
        }
    }

    @Test
    fun failedToggleRollsBackAndRejectsOverlappingBrowseInEitherResponseOrder() {
        for (initiallySubscribed in listOf(false, true)) {
            for (browseFinishesBeforeToggle in listOf(false, true)) {
                val state = ArtistSubscriptionState()
                state.applyBrowse(initiallySubscribed, state.revision)
                val requested = state.beginToggle()!!
                val browseRevision = state.revision

                if (browseFinishesBeforeToggle) {
                    state.applyBrowse(requested, browseRevision)
                    state.finishToggle(requested, succeeded = false)
                } else {
                    state.finishToggle(requested, succeeded = false)
                    state.applyBrowse(requested, browseRevision)
                }

                assertEquals(initiallySubscribed, state.isSubscribed)
                assertEquals(requested, state.beginToggle())
            }
        }
    }

    @Test
    fun browseStartedAfterMutationCompletesCanReconcileRemoteChanges() {
        val state = ArtistSubscriptionState()
        val requested = state.beginToggle()!!
        state.finishToggle(requested, succeeded = true)
        state.applyBrowse(false, state.revision)
        assertEquals(false, state.isSubscribed)
        assertEquals(true, state.beginToggle())
    }
}
