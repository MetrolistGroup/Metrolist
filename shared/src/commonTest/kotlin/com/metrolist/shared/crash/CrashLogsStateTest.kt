package com.metrolist.shared.crash

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CrashLogsStateTest {
    @Test
    fun constructionDoesNotReadAndLoadingAndClearingUseTheStoreDispatcher() =
        runTest {
            val report = CrashReport(0, "test", "test", "trace")
            var stored = listOf(report)
            var reads = 0
            var clears = 0
            val store =
                object : CrashStore {
                    override fun save(report: CrashReport) = error("unused")

                    override fun list(): List<CrashReport> = stored.also { reads++ }

                    override fun clear() {
                        clears++
                        stored = emptyList()
                    }
                }
            val state = CrashLogsState(store, StandardTestDispatcher(testScheduler, "store"))
            assertEquals(0, reads)
            assertEquals(emptyList(), state.reports.value)

            launch(start = CoroutineStart.UNDISPATCHED) { state.load() }
            assertEquals(0, reads)
            runCurrent()
            assertEquals(listOf(report), state.reports.value)

            launch(start = CoroutineStart.UNDISPATCHED) { state.clear() }
            assertEquals(0, clears)
            runCurrent()
            assertEquals(1, clears)
            assertEquals(2, reads)
            assertEquals(emptyList(), state.reports.value)
        }
}
