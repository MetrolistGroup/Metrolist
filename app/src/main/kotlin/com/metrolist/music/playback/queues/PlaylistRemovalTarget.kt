/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.db.entities.PlaylistSongMap
import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata

internal fun resolvePlaylistMenuMetadata(original: MediaMetadata, items: List<MediaItem>): MediaMetadata {
    if (original.playlistBrowseId == null && original.localPlaylistId == null) return original
    return items.firstOrNull {
        val live = it.metadata
        live != null && live.id == original.id && live.setVideoId == original.setVideoId &&
            live.playlistSongMapId == original.playlistSongMapId
    }?.metadata ?: original.copy(playlistBrowseId = null, localPlaylistId = null)
}

/**
 * Identifies the single playlist row that a "remove from playlist" action should delete.
 *
 * [setVideoId] is what distinguishes one occurrence of a song from another: YouTube assigns a
 * distinct setVideoId to every row, so a song added twice to the same playlist has two targets.
 */
data class PlaylistRemovalTarget(
    val playlistId: String,
    val setVideoId: String,
    val localPlaylistId: String?,
    val source: String,
)

/**
 * Resolves the removal target for the currently playing item, or null when the current queue has
 * no editable playlist context or the occurrence cannot be identified.
 */
fun resolvePlaylistRemovalTarget(
    queue: Queue?,
    setVideoId: String?,
    playlistBrowseId: String?,
): PlaylistRemovalTarget? {
    val occurrenceId = setVideoId?.takeIf { it.isNotBlank() } ?: return null
    val sourcePlaylistId = playlistBrowseId?.takeIf { it.isNotBlank() } ?: return null
    return when (queue) {
        is YouTubePlaylistQueue ->
            if (queue.isEditable && queue.playlistId == sourcePlaylistId) {
                PlaylistRemovalTarget(
                    playlistId = queue.playlistId,
                    setVideoId = occurrenceId,
                    localPlaylistId = null,
                    source = "queue",
                )
            } else {
                null
            }

        is ListQueue -> {
            val browseId = queue.playlistBrowseId?.takeIf { it.isNotBlank() }
            if (queue.playlistIsEditable && browseId != null && browseId == sourcePlaylistId) {
                PlaylistRemovalTarget(
                    playlistId = browseId,
                    setVideoId = occurrenceId,
                    localPlaylistId = queue.playlistId,
                    source = "listQueue",
                )
            } else {
                null
            }
        }

        else -> null
    }
}

/**
 * Picks the local playlist row matching a removal target. Matching on setVideoId (never on position
 * or song id alone) keeps duplicate occurrences of the same song independent.
 */
fun selectLocalPlaylistRowToRemove(
    maps: List<PlaylistSongMap>,
    localPlaylistId: String,
    setVideoId: String,
): PlaylistSongMap? =
    maps.firstOrNull { it.playlistId == localPlaylistId && it.setVideoId == setVideoId }
