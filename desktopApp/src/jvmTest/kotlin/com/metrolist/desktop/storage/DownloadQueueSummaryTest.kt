package com.metrolist.desktop.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadQueueSummaryTest {
    @Test
    fun mixedBatchCountsOnlyPublishedSongsAsCompletedAndSumsParallelProgress() {
        val summary =
            downloadQueueSummary(
                mapOf(
                    "old" to DownloadState.DOWNLOADED,
                    "done" to DownloadState.DOWNLOADED,
                    "failed" to DownloadState.FAILED,
                    "queued" to DownloadState.QUEUED,
                    "first" to DownloadState.DOWNLOADING,
                    "second" to DownloadState.DOWNLOADING,
                ),
                mapOf("failed" to 1f, "queued" to 1f, "first" to 0.25f, "second" to 0.75f),
                completed = 1,
            )
        assertEquals(1, summary.completed)
        assertEquals(3, summary.active)
        assertEquals(1, summary.failed)
        assertEquals(5, summary.total)
        assertEquals(0.4f, summary.progress)
    }

    @Test
    fun failedBatchNeverReachesCompletionEvenWithStaleTransferProgress() {
        val summary =
            downloadQueueSummary(
                mapOf("first" to DownloadState.FAILED, "second" to DownloadState.FAILED),
                mapOf("first" to 1f),
                completed = 0,
            )
        assertEquals(0, summary.completed)
        assertEquals(0, summary.active)
        assertEquals(2, summary.failed)
        assertEquals(2, summary.total)
        assertEquals(0f, summary.progress)
    }

    @Test
    fun retryAndDismissDoNotTurnFailuresIntoSuccessfulDownloads() {
        val states = mutableMapOf("done" to DownloadState.DOWNLOADED, "retry" to DownloadState.FAILED, "dismiss" to DownloadState.FAILED)
        val failed = downloadQueueSummary(states, emptyMap(), completed = 1)
        assertEquals(3, failed.total)
        assertEquals(2, failed.failed)

        states["retry"] = DownloadState.QUEUED
        val queued = downloadQueueSummary(states, emptyMap(), completed = 1)
        assertEquals(3, queued.total)
        assertEquals(1, queued.completed)
        assertEquals(1, queued.active)
        assertEquals(1, queued.failed)

        states.remove("dismiss")
        states["retry"] = DownloadState.DOWNLOADING
        val retrying = downloadQueueSummary(states, mapOf("retry" to 0.5f), completed = 1)
        assertEquals(2, retrying.total)
        assertEquals(1, retrying.completed)
        assertEquals(0, retrying.failed)
        assertEquals(0.75f, retrying.progress)

        states["retry"] = DownloadState.DOWNLOADED
        val completed = downloadQueueSummary(states, emptyMap(), completed = 2)
        assertEquals(2, completed.completed)
        assertEquals(2, completed.total)
        assertEquals(1f, completed.progress)
    }

    @Test
    fun cancelledSongsAndEarlierDownloadsDoNotCountAsSuccesses() {
        val summary =
            downloadQueueSummary(
                mapOf("earlier" to DownloadState.DOWNLOADED, "cancelled" to DownloadState.NOT_DOWNLOADED),
                mapOf("cancelled" to 0.9f),
                completed = 0,
            )
        assertEquals(DownloadQueueSummary(0, 0, 0, 0f), summary)
        assertEquals(0, summary.total)
        assertEquals(summary, downloadQueueSummary(emptyMap(), emptyMap(), completed = 0))
    }
}
