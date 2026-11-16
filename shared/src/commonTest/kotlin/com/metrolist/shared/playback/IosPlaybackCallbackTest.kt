package com.metrolist.shared.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class IosPlaybackCallbackTest {
    @Test
    fun missingCallbackTimesOutAndLateSuccessCannotChangeTheResult() =
        runTest {
            lateinit var complete: (Boolean) -> Unit
            val result = async { awaitIosPlaybackCallback<Boolean>(100L) { complete = it } }
            runCurrent()
            advanceTimeBy(99L)
            assertFalse(result.isCompleted)
            advanceTimeBy(1L)
            runCurrent()
            assertNull(result.await())
            complete(true)
            assertNull(result.await())
        }

    @Test
    fun cancellationDoesNotWaitForTheNativeCallback() =
        runTest {
            lateinit var complete: (Boolean) -> Unit
            val result = async { awaitIosPlaybackCallback<Boolean> { complete = it } }
            runCurrent()
            result.cancelAndJoin()
            complete(true)
            assertEquals(true, result.isCancelled)
        }

    @Test
    fun firstCallbackWinsIncludingFailure() =
        runTest {
            assertEquals(
                false,
                awaitIosPlaybackCallback<Boolean> { complete ->
                    complete(false)
                    complete(true)
                },
            )
            assertEquals(true, awaitIosPlaybackCallback<Boolean> { complete -> complete(true) })
        }
}
