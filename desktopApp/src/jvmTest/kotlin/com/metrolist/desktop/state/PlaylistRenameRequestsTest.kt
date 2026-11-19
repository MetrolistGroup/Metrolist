package com.metrolist.desktop.state

import com.metrolist.desktop.state.content.PlaylistRenameRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaylistRenameRequestsTest {
    @Test
    fun overlappingRenamesForTheSamePlaylistCannotBothReachTheStore() =
        runTest {
            val requests = PlaylistRenameRequests()
            val firstStarted = CompletableDeferred<Unit>()
            val finishFirst = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            val first =
                async {
                    requests.save("VLPLone") {
                        firstStarted.complete(Unit)
                        finishFirst.await()
                        writes += "First"
                        Result.success(Unit)
                    }
                }
            firstStarted.await()

            assertTrue(
                requests
                    .save("PLone") {
                        writes += "Second"
                        Result.success(Unit)
                    }.isFailure,
            )
            assertTrue(writes.isEmpty())
            assertTrue(requests.save("PLother") { Result.success(Unit) }.isSuccess)

            finishFirst.complete(Unit)
            assertTrue(first.await().isSuccess)
            assertTrue(
                requests
                    .save("PLone") {
                        writes += "Second"
                        Result.success(Unit)
                    }.isSuccess,
            )
            assertEquals(listOf("First", "Second"), writes)
        }

    @Test
    fun bothOperationalErrorFormsReachTheCallerAndCancellationReleasesTheKey() =
        runTest {
            val requests = PlaylistRenameRequests()
            val rejection = IllegalStateException("Remote rejected rename")
            val ioFailure = IOException("Database unavailable")
            assertSame(rejection, requests.save("PLone") { Result.failure(rejection) }.exceptionOrNull())
            assertSame(ioFailure, requests.save("PLone") { throw ioFailure }.exceptionOrNull())
            assertFailsWith<CancellationException> {
                requests.save("PLone") { throw CancellationException("cancelled") }
            }
            assertTrue(requests.save("PLone") { Result.success(Unit) }.isSuccess)
        }
}
