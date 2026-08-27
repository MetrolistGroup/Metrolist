package com.metrolist.music.features.canvas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** In-memory per-track selections made from the Player's Canvas picker. */
object CanvasArtworkSelectionStore {
    private val selected = ConcurrentHashMap<String, CanvasArtwork>()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun get(mediaId: String): CanvasArtwork? = selected[mediaId]

    fun put(mediaId: String, artwork: CanvasArtwork) {
        if (mediaId.isBlank()) return
        selected[mediaId] = artwork
        _changes.update { it + 1L }
    }
}
