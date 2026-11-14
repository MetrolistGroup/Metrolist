package com.metrolist.shared.db

import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.model.isEditablePlaylistId
import com.metrolist.shared.model.parseDisplayCount
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.playlist.PlaylistChanges
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

suspend fun MusicDatabase.preserveKnownPlaylistEditability(
    items: List<YTItem>,
    unresolvedPlaylistEditabilityIds: Set<String>,
): List<YTItem> {
    if (unresolvedPlaylistEditabilityIds.isEmpty()) return items
    val dao = dao()
    return items.map { item ->
        if (
            item is PlaylistItem &&
            item.id in unresolvedPlaylistEditabilityIds &&
            item.id.isEditablePlaylistId() &&
            dao.findPlaylistEntity(item.id)?.isEditable == true
        ) {
            item.copy(isEditable = true)
        } else {
            item
        }
    }
}

suspend fun MusicDatabase.syncRemotePlaylists(
    remotePlaylists: Iterable<PlaylistItem>,
    preserveBookmarkKeys: Set<String> = emptySet(),
) = withDatabaseTransaction {
    val dao = dao()
    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
    remotePlaylists.forEach { item ->
        if (LibraryDeletionTracker.isDeleted(item)) return@forEach
        val existingPlaylists = dao.findRemotePlaylistEntities(item.id)
        for (existing in existingPlaylists.ifEmpty { listOf(null) }) {
            val bookmark =
                if ("playlist:${item.id.removePrefix("VL")}" in
                    preserveBookmarkKeys
                ) {
                    existing?.bookmarkedAt
                } else {
                    existing?.bookmarkedAt ?: now
                }
            dao.upsert(
                existing?.copy(
                    name = item.title,
                    browseId = item.id.removePrefix("VL"),
                    thumbnailUrl = item.thumbnail ?: existing.thumbnailUrl,
                    remoteSongCount = parseDisplayCount(item.songCount) ?: existing.remoteSongCount,
                    playEndpointParams = item.playEndpointParams ?: existing.playEndpointParams,
                    radioEndpointParams = item.radioEndpointParams ?: existing.radioEndpointParams,
                    isEditable = item.canEdit,
                    description = item.description ?: existing.description,
                    bookmarkedAt = bookmark,
                ) ?: PlaylistEntity(
                    name = item.title,
                    browseId = item.id.removePrefix("VL"),
                    thumbnailUrl = item.thumbnail,
                    remoteSongCount = parseDisplayCount(item.songCount),
                    playEndpointParams = item.playEndpointParams,
                    radioEndpointParams = item.radioEndpointParams,
                    isEditable = item.canEdit,
                    description = item.description,
                    bookmarkedAt = bookmark,
                ),
            )
        }
    }
}

suspend fun MusicDatabase.cacheRemotePlaylist(
    requestedId: String,
    playlist: PlaylistItem,
    songs: List<SongItem>,
) = withDatabaseTransaction {
    val dao = dao()
    if (LibraryDeletionTracker.isDeleted(playlist) || LibraryDeletionTracker.isDeleted("playlist:${requestedId.removePrefix("VL")}")) {
        return@withDatabaseTransaction
    }
    val existing = dao.findPlaylistEntity(requestedId)
    if (existing != null && existing.browseId == null) return@withDatabaseTransaction
    val entity =
        (existing ?: PlaylistEntity(id = requestedId, name = playlist.title, bookmarkedAt = null)).copy(
            name = playlist.title,
            browseId = (existing?.browseId ?: requestedId).removePrefix("VL"),
            thumbnailUrl = playlist.thumbnail.takeUnless { it == playlist.customThumbnail } ?: existing?.thumbnailUrl,
            isEditable = playlist.canEdit,
            remoteSongCount = songs.size,
            description = playlist.description,
            lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
            isAutoSync = true,
        )
    dao.upsert(entity)
    dao.clearPlaylist(entity.id)
    songs.forEachIndexed { index, song ->
        dao.upsertHistorySong(song)
        dao.insert(PlaylistSongMap(playlistId = entity.id, songId = song.id, position = index, setVideoId = song.playlistSetVideoId))
    }
}

internal suspend fun MusicDatabase.repairRemotePlaylistMembership(
    repository: YouTubeRepository,
    playlists: List<PlaylistItem>,
    isCurrent: () -> Boolean,
) {
    val dao = dao()
    var complete = true
    for (playlist in playlists.distinctBy { it.id.removePrefix("VL") }) {
        if (!isCurrent()) return
        if (playlist.isLocal || LibraryDeletionTracker.isDeleted(playlist)) continue
        val entity = dao.findPlaylistEntity(playlist.id) ?: continue
        if (entity.isLocal || entity.browseId == null) continue
        val count = dao.localPlaylistSongCount(entity.id)
        val expectedCount = entity.remoteSongCount
        if (expectedCount != null && expectedCount > 0 && count >= expectedCount) continue
        if (expectedCount == 0 && count == 0 && entity.isAutoSync) continue
        try {
            val firstPage = repository.getPlaylistPageData(entity.browseId, applyContentFilters = false)
            val page = repository.completePlaylistPage(firstPage, applyContentFilters = false)
            if (!isCurrent()) return
            if (page.isOffline) continue
            val header = page.sections["header"]?.firstOrNull() as? PlaylistItem ?: continue
            withDatabaseTransaction {
                if (isCurrent() && dao.findPlaylistEntity(entity.id) != null) {
                    cacheRemotePlaylist(entity.id, header, page.songs)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            complete = false
            YtLogger.e("LibrarySync", "Playlist membership repair failed type=${error::class.simpleName}")
            if (isCurrent()) {
                YouTubeSyncState.failed("playlist-membership") {
                    if (isCurrent()) repairRemotePlaylistMembership(repository, playlists, isCurrent)
                }
            }
        }
    }
    if (complete && isCurrent()) YouTubeSyncState.succeeded("playlist-membership")
}

suspend fun MusicDatabase.cachedRemotePlaylistSections(id: String): Map<String, List<YTItem>>? {
    val entity = dao().findPlaylistEntity(id) ?: return null
    val browseId = entity.browseId ?: return null
    val rows = dao().playlistSongs(entity.id).first()
    if (rows.isEmpty()) return null
    val songs = rows.map { it.song.toSongItem().copy(playlistSetVideoId = it.map.setVideoId) }
    val header =
        PlaylistItem(
            id = browseId,
            title = entity.name,
            thumbnail = entity.thumbnailUrl,
            songCount = (entity.remoteSongCount ?: songs.size).toString(),
            isEditable = entity.isEditable,
            description = entity.description,
        )
    return mapOf("header" to listOf(header), "Tracks" to songs)
}

suspend fun MusicDatabase.loadAndCachePlaylist(
    repository: YouTubeRepository,
    id: String,
    onFirstPage: suspend (PlaylistPage) -> Unit = {},
): Map<String, List<YTItem>> = loadAndCachePlaylistPage(repository, id, onFirstPage).sections

suspend fun MusicDatabase.loadAndCachePlaylistPage(
    repository: YouTubeRepository,
    id: String,
    onFirstPage: suspend (PlaylistPage) -> Unit = {},
): PlaylistPage {
    val remoteId = dao().findPlaylistEntity(id)?.browseId ?: id
    val firstPage = repository.getPlaylistPageData(remoteId, applyContentFilters = false)
    onFirstPage(firstPage)
    val page = repository.completePlaylistPage(firstPage, applyContentFilters = false)
    val header = checkNotNull(page.sections["header"]?.firstOrNull() as? PlaylistItem)
    if (!page.isOffline) cacheRemotePlaylist(id, header, page.songs)
    return page.copy(sections = page.sections + ("header" to listOf(header)))
}

suspend fun MusicDatabase.removeUnavailableRemotePlaylist(remoteId: String) {
    val normalizedId = remoteId.removePrefix("VL")
    val removed =
        withDatabaseTransaction {
            val dao = dao()
            val matches =
                dao.allPlaylists().first().filter {
                    !it.isLocal && it.browseId?.removePrefix("VL") == normalizedId
                }
            LibraryDeletionTracker.trackDeletedPlaylist(normalizedId)
            matches.forEach {
                dao.clearPlaylist(it.id)
                dao.delete(it)
            }
            matches.isNotEmpty()
        }
    if (removed) PlaylistChanges.notifyChanged(normalizedId)
}
