package com.metrolist.shared.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FatalCrashHandlerTest {
    @Test
    fun recoveryFailureFallsBackAndNeverTriesRecoveryAgain() {
        var attempts = 0
        val failures = mutableListOf<Throwable>()
        val recoveryFailure = IllegalStateException("recovery failed")
        val handler =
            FatalCrashHandler({ _, error -> failures += error }) { _, _ ->
                attempts++
                throw recoveryFailure
            }
        val second = IllegalStateException("another crash")
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("original"))
        handler.uncaughtException(Thread.currentThread(), second)
        assertEquals(1, attempts)
        assertEquals(2, failures.size)
        assertSame(recoveryFailure, failures[0])
        assertSame(second, failures[1])
    }

    @Test
    fun recursiveCrashGoesStraightToOriginalHandler() {
        var received: Throwable? = null
        lateinit var handler: FatalCrashHandler
        val recursive = IllegalStateException("recursive")
        handler = FatalCrashHandler({ _, error -> received = error }) { thread, _ -> handler.uncaughtException(thread, recursive) }
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("original"))
        assertSame(recursive, received)
    }
}
