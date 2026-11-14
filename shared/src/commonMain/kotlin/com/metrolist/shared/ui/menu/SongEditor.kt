package com.metrolist.shared.ui.menu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class SongEditor(
    private val scope: CoroutineScope,
    title: String,
    artist: String,
    private val save: suspend (String, String) -> Unit,
    private val onSaved: () -> Unit,
    private val onDismiss: () -> Unit,
    private val reset: (suspend () -> Unit)? = null,
) {
    var title by mutableStateOf(title)
    var artist by mutableStateOf(artist)
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun dismiss() {
        if (!busy) onDismiss()
    }

    fun confirm() {
        val requestedTitle = title.trim()
        val requestedArtist = artist.trim()
        if (busy || requestedTitle.isEmpty() || requestedArtist.isEmpty()) return
        submit { save(requestedTitle, requestedArtist) }
    }

    fun reset() {
        val action = reset ?: return
        if (!busy) submit(action)
    }

    private fun submit(action: suspend () -> Unit) {
        busy = true
        failed = false
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
                return@launch
            } finally {
                busy = false
            }
            onSaved()
        }
    }
}
