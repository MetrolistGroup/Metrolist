package com.metrolist.shared.ui.menu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SongEditorTest {
    @Test
    fun resetWaitsForPersistenceAndReportsFailuresBeforeRetrying() =
        runTest {
            val pending = CompletableDeferred<Unit>()
            var attempts = 0
            var completed = false
            val editor =
                SongEditor(backgroundScope, "", "", { _, _ -> error("Unexpected save") }, { completed = true }, {
                    error("Unexpected dismissal")
                }, reset = {
                    pending.await()
                    if (++attempts == 1) error("Database write failed")
                })
            editor.reset()
            editor.reset()
            editor.dismiss()
            runCurrent()
            assertTrue(editor.busy)
            assertFalse(completed)
            pending.complete(Unit)
            runCurrent()
            assertTrue(editor.failed)
            assertFalse(editor.busy)
            assertFalse(completed)
            editor.reset()
            runCurrent()
            assertEquals(2, attempts)
            assertTrue(completed)
            assertFalse(editor.failed)
        }

    @Test
    fun pendingSaveBlocksDismissalAndDuplicateSubmissionsUntilPersistenceCompletes() =
        runTest {
            val scope = CoroutineScope(coroutineContext + Job())
            val pending = CompletableDeferred<Unit>()
            val saved = mutableListOf<Pair<String, String>>()
            var completed = false
            var dismissed = false
            val editor =
                SongEditor(scope, " Title ", " Artist ", { title, artist ->
                    pending.await()
                    saved += title to artist
                }, {
                    completed = true
                    scope.cancel()
                }, {
                    dismissed = true
                    scope.cancel()
                })
            try {
                editor.confirm()
                editor.confirm()
                editor.dismiss()
                runCurrent()
                assertTrue(editor.busy)
                assertFalse(completed)
                assertFalse(dismissed)
                assertTrue(saved.isEmpty())

                pending.complete(Unit)
                runCurrent()
                assertEquals(listOf("Title" to "Artist"), saved)
                assertTrue(completed)
                assertFalse(editor.busy)
                assertFalse(editor.failed)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun failedSaveRetainsInputAndCanBeRetried() =
        runTest {
            var attempts = 0
            var completed = false
            val editor =
                SongEditor(backgroundScope, "Original", "Original artist", { title, artist ->
                    assertEquals("Edited", title)
                    assertEquals("Edited artist", artist)
                    if (++attempts == 1) error("Database write failed")
                }, { completed = true }, { error("Unexpected dismissal") })
            editor.title = " Edited "
            editor.artist = " Edited artist "
            editor.confirm()
            runCurrent()
            assertTrue(editor.failed)
            assertFalse(editor.busy)
            assertFalse(completed)
            assertEquals(" Edited ", editor.title)
            assertEquals(" Edited artist ", editor.artist)

            editor.confirm()
            runCurrent()
            assertTrue(completed)
            assertFalse(editor.failed)
            assertFalse(editor.busy)
            assertEquals(2, attempts)
        }

    @Test
    fun cancellationDoesNotReportSuccessOrFailure() =
        runTest {
            val scope = CoroutineScope(coroutineContext + Job())
            val pending = CompletableDeferred<Unit>()
            val editor =
                SongEditor(scope, "Title", "Artist", { _, _ -> pending.await() }, {
                    error("Cancelled save must not complete")
                }, { error("Unexpected dismissal") })
            editor.confirm()
            runCurrent()
            scope.cancel()
            runCurrent()
            assertFalse(editor.busy)
            assertFalse(editor.failed)
        }

    @Test
    fun blankInputDoesNotSaveAndStillAllowsDismissal() =
        runTest {
            var dismissed = false
            val editor =
                SongEditor(backgroundScope, " ", "Artist", { _, _ -> error("Unexpected save") }, {
                    error("Unexpected completion")
                }, { dismissed = true })
            editor.confirm()
            editor.title = "Title"
            editor.artist = " "
            editor.confirm()
            runCurrent()
            assertFalse(editor.busy)
            editor.dismiss()
            assertTrue(dismissed)
        }
}
