package com.metrolist.music.ui.menu

import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.utils.isGenuineEmptyPlaylist
import com.metrolist.music.utils.setVideoIdUpdatesForAddedSongs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal fun localPlaylistRemovalOccurrences(
    rows: List<PlaylistSongMap>,
    playlistId: String,
    rowId: Int? = null,
): List<PlaylistSongMap> = rows.filter {
    it.playlistId == playlistId && (rowId == null || it.id == rowId)
}

internal fun remotePlaylistRemovalOccurrences(
    localRows: List<PlaylistSongMap>,
    playlistId: String,
    songId: String,
    remoteSongs: List<SongItem>,
): List<PlaylistSongMap> {
    val rows = localRows.filter { it.playlistId == playlistId && it.songId == songId }
    val pending = rows.filter { it.setVideoId == null }
    val updates = setVideoIdUpdatesForAddedSongs(pending.map { it.songId }, remoteSongs, rows).associateBy { it.id }
    val repairedRows = rows.map { updates[it.id] ?: it }
    val matches = remoteSongs.mapIndexedNotNull { index, song ->
        if (song.id != songId) null else {
            check(!song.setVideoId.isNullOrBlank()) { "Playlist occurrence is missing" }
            PlaylistSongMap(
                id = repairedRows.firstOrNull { it.setVideoId == song.setVideoId }?.id ?: 0,
                playlistId = playlistId, songId = songId, position = index, setVideoId = song.setVideoId,
            )
        }
    }
    val claimedIds = repairedRows.mapNotNull { it.setVideoId }.toSet()
    return matches + if (matches.all { it.setVideoId in claimedIds }) {
        repairedRows.filter { it.setVideoId == null }
    } else emptyList()
}

internal data class PlaylistRemovalCandidates(
    val playlists: List<Playlist>,
    val hasFailures: Boolean,
) {
    fun automaticPlaylist(initialBrowseId: String? = null, initialLocalPlaylistId: String? = null): Playlist? =
        if (hasFailures || initialBrowseId != null || initialLocalPlaylistId != null) null else playlists.singleOrNull()
}

internal suspend fun findPlaylistRemovalCandidates(
    playlists: List<Playlist>,
    songId: String,
    localRows: List<PlaylistSongMap>,
    loadRemote: suspend (String) -> PlaylistPage,
): PlaylistRemovalCandidates = coroutineScope {
    val localPlaylistIds = localRows.filter { it.songId == songId }.map { it.playlistId }.toSet()
    val pendingPlaylistIds = localRows.filter { it.songId == songId && it.setVideoId == null }.map { it.playlistId }.toSet()
    val requests = Semaphore(4)
    val checked = playlists.filter { it.playlist.isEditable }.map { playlist ->
        async {
            try {
                val browseId = playlist.playlist.browseId
                val containsSong = if (browseId == null) {
                    playlist.id in localPlaylistIds
                } else {
                    requests.withPermit {
                        val page = loadRemote(browseId)
                        check(page.songsContinuation == null) { "Playlist response is incomplete" }
                        check(page.songs.isNotEmpty() || isGenuineEmptyPlaylist(page)) { "Playlist response is incomplete" }
                        page.playlist.isEditable && (page.songs.any { it.id == songId } || playlist.id in pendingPlaylistIds)
                    }
                }
                Result.success(playlist.takeIf { containsSong })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure<Playlist?>(e)
            }
        }
    }.awaitAll()
    PlaylistRemovalCandidates(
        playlists = checked.mapNotNull { it.getOrNull() },
        hasFailures = checked.any { it.isFailure },
    )
}
