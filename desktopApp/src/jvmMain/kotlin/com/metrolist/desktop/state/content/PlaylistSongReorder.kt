package com.metrolist.desktop.state.content

import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.selectionReorderMoves
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class PlaylistSongReorder(
    val sections: Map<String, List<YTItem>>,
    val setVideoId: String?,
    val successorSetVideoId: String?,
    val localMappingId: Int? = null,
    val successorLocalMappingId: Int? = null,
)

internal fun Map<String, List<YTItem>>.reorderPlaylistSong(
    song: SongItem,
    target: SongItem,
    afterTarget: Boolean,
): PlaylistSongReorder? {
    val local = song.playlistLocalMappingId != null

    fun SongItem.matches(other: SongItem): Boolean =
        if (local) playlistLocalMappingId == other.playlistLocalMappingId else playlistSetVideoId == other.playlistSetVideoId
    if (local) {
        if (target.playlistLocalMappingId == null) return null
    } else if (song.playlistSetVideoId == null || target.playlistSetVideoId == null) {
        return null
    }
    if (song.matches(target)) return null
    val songs = playlistSongs().toMutableList()
    val from = songs.indexOfFirst { it.matches(song) }
    if (from < 0 || songs.none { it.matches(target) }) return null
    val moving = songs.removeAt(from)
    val targetIndex = songs.indexOfFirst { it.matches(target) }
    val insertionIndex = targetIndex + if (afterTarget) 1 else 0
    songs.add(insertionIndex, moving)
    val successor = songs.getOrNull(insertionIndex + 1)
    // A null remote successor means the end, not an existing occurrence without a server ID.
    if (!local && successor != null && successor.playlistSetVideoId.isNullOrBlank()) return null
    val sections = filterKeys { it.equals("header", ignoreCase = true) || it.contains("suggest", ignoreCase = true) }
    return PlaylistSongReorder(
        sections = sections + ("songs" to songs),
        setVideoId = song.playlistSetVideoId,
        localMappingId = song.playlistLocalMappingId,
        successorLocalMappingId = successor?.playlistLocalMappingId,
        successorSetVideoId = successor?.playlistSetVideoId,
    )
}

internal fun Map<String, List<YTItem>>.reorderPlaylistSongs(
    reordered: List<SongItem>,
    moving: Set<SongItem>,
): List<PlaylistSongReorder>? {
    var preview = this
    return selectionReorderMoves(playlistSongs(), reordered, moving).map { (from, to) ->
        val items = preview.playlistSongs()
        val move = preview.reorderPlaylistSong(items[from], items[to], afterTarget = from < to) ?: return null
        preview = move.sections
        move
    }
}

internal suspend fun commitRemotePlaylistReorder(
    database: MusicDatabase,
    playlistId: String,
    move: PlaylistSongReorder,
    remoteMove: suspend () -> Result<Unit>,
): Result<Unit> =
    try {
        remoteMove().getOrThrow()
        withContext(NonCancellable) {
            database.withDatabaseTransaction {
                val dao = database.dao()
                val local =
                    dao.findPlaylistEntity(playlistId)
                        ?: dao.findPlaylistEntity("VL${playlistId.removePrefix("VL")}")
                        ?: dao.findPlaylistEntity(playlistId.removePrefix("VL"))
                if (local != null) {
                    val mappings = dao.playlistSongMaps(local.id, Int.MIN_VALUE)
                    val from = mappings.indexOfFirst { it.setVideoId == move.setVideoId }
                    val to =
                        if (move.successorSetVideoId != null) {
                            val successor = mappings.indexOfFirst { it.setVideoId == move.successorSetVideoId }
                            if (successor >= 0) (if (from < successor) successor - 1 else successor) else -1
                        } else {
                            mappings.lastIndex
                        }
                    if (from >= 0 && to >= 0 && from != to) dao.moveMapping(local.id, mappings[from].id, mappings[to].id)
                }
            }
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
