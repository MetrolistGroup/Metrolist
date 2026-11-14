package com.metrolist.shared.repository

import com.metrolist.shared.api.lyrics.LyricsEditorSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LyricsEditorAutosave(
    private val songId: String,
    private val repository: LyricsRepository,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {
    private val mutex = Mutex()
    private var timer: Job? = null
    private var pending: LyricsEditorSnapshot? = null
    private var reverted = false
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    fun changed(snapshot: LyricsEditorSnapshot) {
        pending = snapshot
        timer?.cancel()
        timer =
            scope.launch {
                delay(500)
                flush()
            }
    }

    suspend fun flush(): Boolean =
        withContext(NonCancellable) {
            mutex.withLock {
                val snapshot = pending ?: return@withLock true
                if (reverted) return@withLock true
                try {
                    repository.saveCustomLyrics(songId, snapshot.recoveryJson, snapshot.text.takeIf { !snapshot.isValid })
                    if (pending === snapshot) pending = null
                    _failed.value = false
                    onChanged()
                    true
                } catch (_: Exception) {
                    _failed.value = true
                    false
                }
            }
        }

    suspend fun revert(): Boolean =
        withContext(NonCancellable) {
            timer?.cancel()
            mutex.withLock {
                try {
                    repository.revertUserLyrics(songId)
                    pending = null
                    reverted = true
                    _failed.value = false
                    onChanged()
                    true
                } catch (_: Exception) {
                    _failed.value = true
                    false
                }
            }
        }
}
