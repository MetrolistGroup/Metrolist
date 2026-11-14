package com.metrolist.shared.db

import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

suspend fun MusicDatabase.refreshUploadedSongs(
    repository: YouTubeRepository,
    force: Boolean = false,
): List<SongItem> {
    check(repository.canManagePlaylists()) { "Upload refresh requires a signed-in account" }
    val songs = repository.getLibraryUploadSongsResult(limit = null, force = force).getOrThrow()
    synchronizeUploadedSongs(songs)
    return dao().uploadedSongsByNameAsc().first().map { it.toSongItem() }
}

suspend fun MusicDatabase.synchronizeUploadedSongs(songs: List<SongItem>) {
    val current = songs.filterNot { LibraryDeletionTracker.isDeleted(it) }
    val currentIds = current.mapTo(mutableSetOf()) { it.id }
    withDatabaseTransaction {
        syncRemoteLibraryItems(current, Clock.System.now().toLocalDateTime(TimeZone.UTC))
        dao().uploadedSongsByNameAsc().first().forEach { stored ->
            if (!stored.song.isLocal && stored.id !in currentIds) {
                dao().inLibrary(stored.id, null)
            }
        }
    }
}

suspend fun MusicDatabase.deleteUploadedSong(
    song: SongItem,
    repository: YouTubeRepository,
): Boolean {
    val stored = dao().getSongById(song.id)?.song
    if (song.isLocal || stored?.isLocal == true || !repository.canManagePlaylists()) return false
    val entityId =
        song.uploadEntityId ?: stored?.uploadEntityId ?: run {
            val uploads = repository.getLibraryUploadSongsResult(limit = null).getOrThrow()
            synchronizeUploadedSongs(uploads)
            val upload = uploads.firstOrNull { it.id == song.id }
            if (upload == null) {
                LibraryDeletionTracker.trackDeletedSong(song.id)
                dao().inLibrary(song.id, null)
                return true
            }
            upload.uploadEntityId ?: return false
        }
    if (!repository.deleteUploadedSong(entityId)) return false
    LibraryDeletionTracker.trackDeletedSong(song.id)
    dao().inLibrary(song.id, null)
    return true
}
