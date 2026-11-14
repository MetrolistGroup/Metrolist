package com.metrolist.android.viewmodels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistAddOperationsTest {
    @Test
    fun repeatedAddsAreIgnoredWhileOtherTargetsAndRetriesStillWork() =
        runTest {
            val operations = PlaylistAddOperations()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    operations.run("playlist", "song") {
                        writes++
                        release.await()
                        AddSongToPlaylistResult.Added
                    }
                }
            assertEquals(
                AddSongToPlaylistResult.InProgress,
                operations.run("playlist", "song") {
                    error("A second write must not run")
                },
            )
            assertEquals(AddSongToPlaylistResult.Added, operations.run("other", "song") { AddSongToPlaylistResult.Added })
            first.cancel()
            first.join()
            assertEquals(
                AddSongToPlaylistResult.Added,
                operations.run("playlist", "song") {
                    writes++
                    AddSongToPlaylistResult.Added
                },
            )
            assertEquals(2, writes)
            try {
                operations.run("playlist", "song") { error("failed write") }
            } catch (_: IllegalStateException) {
            }
            assertEquals(
                AddSongToPlaylistResult.AlreadyPresent,
                operations.run("playlist", "song") { AddSongToPlaylistResult.AlreadyPresent },
            )
        }
}
