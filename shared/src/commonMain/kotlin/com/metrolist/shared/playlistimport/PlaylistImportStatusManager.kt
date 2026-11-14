package com.metrolist.shared.playlistimport

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActivePlaylistImport(
    val id: String,
    val playlistTitle: String,
    val source: String,
    val state: ImportProgressState,
    val job: Job? = null,
) {
    val isRunning: Boolean
        get() = state !is ImportProgressState.Completed && state !is ImportProgressState.Failed && state !is ImportProgressState.Cancelled
}

object PlaylistImportStatusManager {
    private val lock = SynchronizedObject()
    private val _activeImport = MutableStateFlow<ActivePlaylistImport?>(null)
    val activeImport: StateFlow<ActivePlaylistImport?> = _activeImport.asStateFlow()
    private var idCounter = 0L

    fun startImport(
        playlistTitle: String,
        source: String,
        job: Job? = null,
    ): String =
        synchronized(lock) {
            val id = (++idCounter).toString()
            val previous = _activeImport.value
            _activeImport.value =
                ActivePlaylistImport(
                    id = id,
                    playlistTitle = playlistTitle,
                    source = source,
                    state = ImportProgressState.FetchingMetadata(source),
                    job = job,
                )
            if (previous?.job !== job) previous?.job?.cancel()
            id
        }

    fun attachJob(
        id: String,
        job: Job,
    ) = synchronized(lock) {
        val current = _activeImport.value
        if (current?.id == id && current.isRunning) {
            _activeImport.value = current.copy(job = job)
        } else {
            job.cancel()
        }
    }

    fun updateProgress(
        id: String,
        state: ImportProgressState,
    ): Unit =
        synchronized(lock) {
            val current = _activeImport.value ?: return@synchronized
            if (current.id == id && current.isRunning) _activeImport.value = current.copy(state = state)
        }

    fun complete(
        id: String,
        completed: ImportProgressState.Completed,
    ) {
        updateProgress(id, completed)
    }

    fun fail(
        id: String,
        failed: ImportProgressState.Failed,
    ) {
        updateProgress(id, failed)
    }

    fun cancel(id: String): Unit =
        synchronized(lock) {
            val current = _activeImport.value ?: return@synchronized
            if (current.id == id && current.isRunning) {
                if (current.state !is ImportProgressState.SavingToLibrary) _activeImport.value = null
                current.job?.cancel()
            }
        }

    fun finishCancellation(
        id: String,
        error: Throwable,
    ): Unit =
        synchronized(lock) {
            val current = _activeImport.value ?: return@synchronized
            if (current.id != id || current.state is ImportProgressState.Cancelled) return@synchronized
            val partial = error as? PlaylistImportManager.CancelledImportException
            _activeImport.value =
                partial?.let {
                    current.copy(state = ImportProgressState.Cancelled(it.importedSongCount, it.playlist.name), job = null)
                }
        }

    fun dismiss(id: String): Unit =
        synchronized(lock) {
            val current = _activeImport.value ?: return@synchronized
            if (current.id == id && !current.isRunning) _activeImport.value = null
        }

    fun clear(id: String) =
        synchronized(lock) {
            if (_activeImport.value?.id == id) _activeImport.value = null
        }
}
