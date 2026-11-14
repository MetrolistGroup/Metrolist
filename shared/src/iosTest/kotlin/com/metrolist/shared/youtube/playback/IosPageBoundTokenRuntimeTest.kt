package com.metrolist.shared.youtube.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPageBoundTokenRuntimeTest {
    @Test
    fun javaScriptErrorsCompleteWithoutCancellingTheOperation() =
        runTest {
            var cancelled = false
            val failure =
                assertFailsWith<IllegalStateException> {
                    suspendCancellableCoroutine<String> { continuation ->
                        continuation.invokeOnCancellation { cancelled = true }
                        continuation.completeJavaScriptEvaluation(null, NSError("WKErrorDomain", 4, null))
                    }
                }
            assertFalse(cancelled)
            assertFalse(failure is CancellationException)
            assertTrue(isActive)
            assertEquals("ready", suspendCancellableCoroutine { it.completeJavaScriptEvaluation("ready", null) })
        }
}
