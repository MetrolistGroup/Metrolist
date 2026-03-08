package com.metrolist.shared.ui.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CarouselScrollSettlerTest {
    @Test
    fun wheelBurstsSettleOnceAfterTheirLastDelta() =
        runTest {
            var calls = 0
            val settler = CarouselScrollSettler(backgroundScope) { calls++ }
            repeat(3) {
                settler.schedule()
                advanceTimeBy(100)
                assertEquals(0, calls)
            }
            advanceTimeBy(50)
            runCurrent()
            assertEquals(1, calls)
        }

    @Test
    fun disablingInputCancelsPendingAndRunningSettles() =
        runTest {
            var calls = 0
            var cancelled = false
            val settler =
                CarouselScrollSettler(backgroundScope) {
                    calls++
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            settler.schedule()
            settler.cancel()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(0, calls)
            settler.schedule()
            advanceTimeBy(150)
            runCurrent()
            assertEquals(1, calls)
            settler.cancel()
            runCurrent()
            assertTrue(cancelled)
        }
}
