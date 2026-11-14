package com.metrolist.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StartupMaintenanceTest {
    @Test
    fun failedAuditRetainsRetryStateAndSuccessRecordsCompletion() =
        runTest {
            var completed = 0L
            val failure = IOException("Storage unavailable")
            val failures = mutableListOf<Exception>()
            runStartupStorageAudit({ throw failure }, { completed = 100 }, failures::add)
            assertEquals(0L, completed)
            assertEquals(listOf(failure), failures)
            assertTrue(startupMaintenanceDue(completed, 100, STORAGE_AUDIT_INTERVAL_MS))
            runStartupStorageAudit({}, { completed = 100 }, failures::add)
            assertFalse(startupMaintenanceDue(completed, 100, STORAGE_AUDIT_INTERVAL_MS))
        }

    @Test
    fun cancellationDoesNotRecordCompletionOrReportStorageFailure() =
        runTest {
            val cancellation = CancellationException("Stopped")
            try {
                runStartupStorageAudit({ throw cancellation }, { error("Unexpected success") }, { error("Unexpected failure report") })
                error("Cancellation was swallowed")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
        }

    @Test
    fun successfulAuditSuppressesRepeatedColdStartsUntilItsDeadline() {
        val completed = 1_000_000L
        assertFalse(startupMaintenanceDue(completed, completed, STORAGE_AUDIT_INTERVAL_MS))
        assertFalse(startupMaintenanceDue(completed, completed + STORAGE_AUDIT_INTERVAL_MS - 1, STORAGE_AUDIT_INTERVAL_MS))
        assertTrue(startupMaintenanceDue(completed, completed + STORAGE_AUDIT_INTERVAL_MS, STORAGE_AUDIT_INTERVAL_MS))
    }

    @Test
    fun newRestoredAndClockRolledBackInstallationsAreAudited() {
        assertTrue(startupMaintenanceDue(0, 100, STORAGE_AUDIT_INTERVAL_MS))
        assertTrue(startupMaintenanceDue(200, 100, STORAGE_AUDIT_INTERVAL_MS))
        assertTrue(startupMaintenanceDue(-1, 100, STORAGE_AUDIT_INTERVAL_MS))
    }
}
