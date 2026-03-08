package com.metrolist.android

import com.metrolist.shared.crash.CrashReport
import com.metrolist.shared.crash.FileCrashStore
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.db.checkDatabaseVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun recovery(store: FileCrashStore) =
        StartupRecovery(store.pendingReportId()) { error ->
            val report = CrashReport.create("test", "Android test", error)
            store.save(report)
            report.id
        }

    @Test
    fun pendingReportSkipsAllInitializationUntilVisibleLauncherCanHandOffOnce() {
        val store = FileCrashStore(temporary.newFolder())
        val first = recovery(store)
        first.run { error("Application startup probe") }
        val next = recovery(store)
        var initialized = false
        next.run { initialized = true }
        assertFalse(initialized)
        assertNull(next.claimReport(hasWindowFocus = false))
        val id = next.claimReport(hasWindowFocus = true)
        assertEquals(store.pendingReportId(), id)
        assertNull(next.claimReport(hasWindowFocus = true))
        store.acknowledge(requireNotNull(id))
        assertFalse(recovery(store).isRecovering)
        next.run { initialized = true }
        assertFalse(initialized)
    }

    @Test
    fun downgradeRoutesEveryLaunchEvenAfterAcknowledgementWithoutOpeningDatabase() {
        val store = FileCrashStore(temporary.newFolder())
        repeat(3) {
            val recovery = recovery(store)
            var opened = false
            recovery.run {
                checkDatabaseVersion(DATABASE_VERSION + 1)
                opened = true
            }
            assertFalse(opened)
            assertTrue(recovery.isRecovering)
            assertNull(recovery.claimReport(hasWindowFocus = false))
            val id = requireNotNull(recovery.claimReport(hasWindowFocus = true))
            assertTrue(
                store
                    .list()
                    .first { it.id == id }
                    .stackTrace
                    .contains("DatabaseDowngradeException"),
            )
            store.acknowledge(id)
        }
    }

    @Test
    fun activityCreationFailureAfterApplicationStartupStillRoutesOnSameLaunch() {
        val store = FileCrashStore(temporary.newFolder())
        val recovery = recovery(store)
        var applicationStarted = false
        recovery.run { applicationStarted = true }
        assertTrue(applicationStarted)
        assertNull(recovery.claimReport(hasWindowFocus = true))
        recovery.run { error("Activity creation probe") }
        assertEquals(store.pendingReportId(), recovery.claimReport(hasWindowFocus = true))
    }

    @Test
    fun asynchronousStartupFailureCancelsReadersAndRoutesTheOriginalError() =
        runTest {
            val store = FileCrashStore(temporary.newFolder())
            val recovery = recovery(store)
            val startup = AppStartup()
            val hydrate = CompletableDeferred<Unit>()
            val initialization =
                launch {
                    try {
                        startup.initialize({}, {
                            hydrate.await()
                            error("Database initialization probe")
                        })
                    } catch (error: Throwable) {
                        recovery.capture(error)
                    }
                }
            var read = false
            val reader =
                launch {
                    startup.await()
                    read = true
                }
            yield()
            assertNull(recovery.claimReport(hasWindowFocus = true))
            hydrate.complete(Unit)
            initialization.join()
            reader.join()
            assertTrue(reader.isCancelled)
            assertFalse(read)
            assertFalse(startup.isReady)
            assertEquals(store.pendingReportId(), recovery.claimReport(hasWindowFocus = true))
            assertTrue(
                store
                    .list()
                    .single()
                    .stackTrace
                    .contains("Database initialization probe"),
            )
        }

    @Test
    fun repeatedFailuresKeepTheFirstReportAndNeverRetryInitialization() {
        val store = FileCrashStore(temporary.newFolder())
        val recovery = recovery(store)
        recovery.run { error("first failure") }
        val id = recovery.report.value
        recovery.capture(IllegalStateException("secondary failure"))
        recovery.run { error("must not run") }
        assertEquals(id, recovery.report.value)
        assertEquals(1, store.list().size)
        assertTrue(
            store
                .list()
                .single()
                .stackTrace
                .contains("first failure"),
        )
        assertNull(recovery.claimReport(hasWindowFocus = false))
        assertEquals(id, recovery.claimReport(hasWindowFocus = true))
        assertNull(recovery.claimReport(hasWindowFocus = true))
    }

    @Test
    fun successfulStartupAndAcknowledgedRecoveryDoNotRouteOrWriteAnotherReport() {
        val store = FileCrashStore(temporary.newFolder())
        val initial = recovery(store)
        initial.capture(IllegalStateException("transient failure"))
        store.acknowledge(requireNotNull(initial.report.value))
        val restart = recovery(store)
        var initialized = false
        restart.run { initialized = true }
        assertTrue(initialized)
        assertFalse(restart.isRecovering)
        assertNull(restart.claimReport(hasWindowFocus = true))
        assertEquals(1, store.list().size)
    }
}
