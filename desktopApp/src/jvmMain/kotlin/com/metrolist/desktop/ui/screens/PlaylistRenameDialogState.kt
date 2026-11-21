package com.metrolist.desktop.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.model.PlaylistItem

internal class PlaylistRenameDialogState {
    var playlist by mutableStateOf<PlaylistItem?>(null)
        private set
    var name by mutableStateOf("")
    private var pending by mutableStateOf<Any?>(null)
    val isSaving: Boolean get() = pending != null
    var failed by mutableStateOf(false)
        private set

    fun open(playlist: PlaylistItem) {
        if (isSaving) return
        this.playlist = playlist
        name = playlist.title
        failed = false
    }

    fun dismiss() {
        pending = null
        playlist = null
    }

    fun submit(rename: (PlaylistItem, String, (Result<Unit>) -> Unit) -> Unit) {
        val target = playlist ?: return
        if (isSaving || name.isBlank()) return
        val request = Any()
        pending = request
        failed = false
        rename(target, name) { result ->
            if (pending !== request) return@rename
            pending = null
            if (result.isSuccess) {
                dismiss()
            } else {
                failed = true
            }
        }
    }
}
