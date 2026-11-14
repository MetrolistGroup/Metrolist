package com.metrolist.shared.playlistimport

import com.metrolist.shared.db.entities.PlaylistEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistImportStatusManagerTest {
    @AfterTest
    fun tearDown() {
        PlaylistImportStatusManager.activeImport.value
            ?.id
            ?.let(PlaylistImportStatusManager::clear)
    }

    @Test
    fun staleUpdatesDoNotReplaceTheActiveImport() {
        val first = PlaylistImportStatusManager.startImport("first", "file")
        val second = PlaylistImportStatusManager.startImport("second", "file")

        PlaylistImportStatusManager.updateProgress(first, ImportProgressState.Completed("first", 1, 1, 1, "first"))
        PlaylistImportStatusManager.cancel(first)

        assertEquals(second, PlaylistImportStatusManager.activeImport.value?.id)
        assertTrue(PlaylistImportStatusManager.activeImport.value?.state is ImportProgressState.FetchingMetadata)
    }

    @Test
    fun replacingAnImportCancelsItsAttachedJob() {
        val job = Job()
        PlaylistImportStatusManager.startImport("first", "file", job)

        PlaylistImportStatusManager.startImport("second", "file")

        assertFalse(job.isActive)
    }

    @Test
    fun batchReusesItsOuterJobWithoutCancellingTheNextPlaylist() {
        val batchJob = Job()

        val first = PlaylistImportStatusManager.startImport("first", "file", batchJob)
        val second = PlaylistImportStatusManager.startImport("second", "file", batchJob)

        assertTrue(batchJob.isActive)
        assertEquals(second, PlaylistImportStatusManager.activeImport.value?.id)
        PlaylistImportStatusManager.complete(
            second,
            ImportProgressState.Completed("second", 1, 1, 1, "second"),
        )
        PlaylistImportStatusManager.clear(first)
        batchJob.cancel()
    }

    @Test
    fun cancellationKeepsConfirmedPartialDestinationUntilDismissed() {
        val job = Job()
        val id = PlaylistImportStatusManager.startImport("source", "file", job)
        PlaylistImportStatusManager.updateProgress(id, ImportProgressState.SavingToLibrary(2, 10))
        PlaylistImportStatusManager.cancel(id)
        assertFalse(job.isActive)
        PlaylistImportStatusManager.finishCancellation(
            id,
            PlaylistImportManager.CancelledImportException(
                PlaylistEntity(name = "destination", isLocal = true),
                2,
                CancellationException(),
            ),
        )
        assertEquals(ImportProgressState.Cancelled(2, "destination"), PlaylistImportStatusManager.activeImport.value?.state)
        assertFalse(PlaylistImportStatusManager.activeImport.value!!.isRunning)
        PlaylistImportStatusManager.cancel(id)
        assertTrue(PlaylistImportStatusManager.activeImport.value != null)
        PlaylistImportStatusManager.dismiss(id)
        assertEquals(null, PlaylistImportStatusManager.activeImport.value)
    }

    @Test
    fun cancellationBeforeAttachmentCancelsTheLateJob() {
        val id = PlaylistImportStatusManager.startImport("first", "file")
        PlaylistImportStatusManager.cancel(id)
        val job = Job()

        PlaylistImportStatusManager.attachJob(id, job)

        assertFalse(job.isActive)
    }
}
