package com.metrolist.shared.db

import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.utils.getCurrentTimeMillis
import kotlinx.coroutines.CancellationException

internal suspend fun MusicDatabase.refreshSpeedDialArtwork(
    knownThumbnails: Map<String, String> = emptyMap(),
    refreshedAt: MutableMap<String, Long> = mutableMapOf(),
    now: Long = getCurrentTimeMillis(),
    loadPlaylist: suspend (String) -> PlaylistItem?,
) {
    val dao = speedDialDao()
    val pins = dao.getAllOnce().filter { it.type == "PLAYLIST" && !it.id.startsWith("MPSP") && BuiltinPlaylist.fromId(it.id) == null }
    refreshedAt.keys.retainAll(pins.mapTo(mutableSetOf()) { it.id })
    pins.forEach { pin ->
        try {
            val known = knownThumbnails[pin.id.removePrefix("VL")]?.takeIf(String::isNotBlank)
            val fresh = refreshedAt[pin.id]?.let { now - it in 0 until 86_400_000L } == true
            if (known == null && !pin.thumbnailUrl.isNullOrBlank() && fresh) return@forEach
            val thumbnail = known ?: loadPlaylist(pin.id)?.thumbnail?.takeIf(String::isNotBlank)
            if (thumbnail != null) {
                if (thumbnail != pin.thumbnailUrl) dao.updatePlaylistThumbnail(pin.id, thumbnail)
                refreshedAt[pin.id] = now
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}
