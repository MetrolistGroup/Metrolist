package com.metrolist.android.recognition

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionSessionControllerTest {
    @Test
    fun startDuringStoppingRunsAfterCancellationWithoutFinishingTheService() =
        runTest {
            var starts = 0
            var finishes = 0
            val controller =
                RecognitionSessionController(
                    backgroundScope,
                    recognize = {
                        starts++
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { delay(100) }
                        }
                    },
                    finish = { finishes++ },
                )
            controller.start()
            runCurrent()
            controller.start()
            assertEquals(1, starts)
            controller.stop()
            runCurrent()
            controller.start()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, starts)
            assertEquals(0, finishes)
            controller.stop()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, finishes)
        }
}
