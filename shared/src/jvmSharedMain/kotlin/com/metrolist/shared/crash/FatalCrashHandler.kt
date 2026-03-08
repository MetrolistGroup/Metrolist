package com.metrolist.shared.crash

import java.util.concurrent.atomic.AtomicBoolean

class FatalCrashHandler(
    private val fallback: Thread.UncaughtExceptionHandler?,
    private val handoff: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val handling = AtomicBoolean()

    override fun uncaughtException(
        thread: Thread,
        error: Throwable,
    ) {
        if (!handling.compareAndSet(false, true)) {
            fallback?.uncaughtException(thread, error)
            return
        }
        try {
            handoff(thread, error)
        } catch (failure: Throwable) {
            fallback?.uncaughtException(thread, failure)
        }
    }
}
