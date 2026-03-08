package com.metrolist.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadSpaceBudgetTest {
    @Test
    fun parallelWorkersShareOneBudgetAndRecheckBeforeCrossingIt() {
        var queries = 0
        val budget =
            DownloadSpaceBudget(16L * 1024 * 1024) {
                queries++
                100L * 1024 * 1024
            }
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results =
                (0 until 4).map {
                    workers.submit { repeat(128) { budget.beforeWrite(64 * 1024) } }
                }
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(4, queries)
            budget.beforeWrite(1)
            assertEquals(5, queries)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun unknownLengthsKeepTheReserveAndNoticeExternalSpaceConsumption() {
        var space = 116L
        var queries = 0
        val budget =
            DownloadSpaceBudget(100) {
                queries++
                space
            }
        budget.beforeWrite(10)
        budget.beforeWrite(5)
        assertEquals(1, queries)
        space = 101
        assertThrows(InsufficientAudioStorageException::class.java) { budget.beforeWrite(1) }
        assertEquals(2, queries)
    }
}
