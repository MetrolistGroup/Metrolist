package com.metrolist.shared.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HomeRequestFailureTest {
    @Test
    fun failedParallelBrowseDoesNotCrashTheSupervisor() =
        runTest {
            supervisorScope {
                val request = async { homeRequestResult<Int> { error("browse failed") } }
                assertEquals("browse failed", request.await().exceptionOrNull()?.message)
            }
        }

    @Test
    fun cancelledBrowseIsNotConvertedToAnEmptySection() =
        runTest {
            assertFailsWith<CancellationException> {
                homeRequestResult<Int> { throw CancellationException("cancelled") }
            }
        }
}
