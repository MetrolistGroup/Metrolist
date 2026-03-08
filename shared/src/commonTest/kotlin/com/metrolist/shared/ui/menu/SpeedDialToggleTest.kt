package com.metrolist.shared.ui.menu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedDialToggleTest {
    @Test
    fun pinWriteStartsBeforeDismissalAndFinishesBeforeItsParent() =
        runTest {
            val parent = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + parent)
            val release = CompletableDeferred<Unit>()
            var started = false
            var finished = false
            val toggle =
                SpeedDialToggle(false, scope) {
                    started = true
                    release.await()
                    finished = true
                }

            toggle.toggle()
            scope.cancel()
            assertTrue(started)
            assertFalse(finished)
            assertFalse(parent.isCompleted)
            release.complete(Unit)
            parent.join()
            assertTrue(finished)
        }
}
