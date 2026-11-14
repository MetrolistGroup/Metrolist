package com.metrolist.shared.db

import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.isEditablePlaylistId
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

fun List<PlaylistEntity>.selectablePlaylists(hiddenKeys: Set<HiddenLibraryItemKey> = emptySet()): List<PlaylistEntity> =
    filter {
        it.isEditable && it.bookmarkedAt != null && !it.isSystemPlaylist() &&
            (it.isLocal || it.browseId == null || it.browseId.isEditablePlaylistId()) &&
            HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, it.browseId ?: it.id).normalized() !in hiddenKeys
    }.distinctBy { it.browseId?.removePrefix("VL") ?: it.id }

fun List<Playlist>.selectablePlaylistItems(
    signedIn: Boolean,
    hiddenKeys: Set<HiddenLibraryItemKey> = emptySet(),
    playtimes: Map<String, Long?> = emptyMap(),
): List<PlaylistItem> =
    selectablePlaylistDetails(signedIn, hiddenKeys)
        .map { details ->
            val entity = details.playlist
            PlaylistItem(
                id = entity.browseId ?: entity.id,
                title = entity.name,
                thumbnail = entity.customThumbnailUrl ?: entity.thumbnailUrl,
                isEditable = entity.isEditable,
                isLocal = entity.isLocal || entity.browseId == null,
                customThumbnail = entity.customThumbnailUrl,
                songCount = details.songCount.toString(),
                playtime = playtimes[entity.id],
                libraryAddedAt = (entity.bookmarkedAt ?: entity.createdAt)?.toInstant(TimeZone.UTC)?.toEpochMilliseconds(),
                libraryUpdatedAt = entity.lastUpdateTime?.toInstant(TimeZone.UTC)?.toEpochMilliseconds(),
            )
        }

fun List<Playlist>.selectablePlaylistDetails(
    signedIn: Boolean,
    hiddenKeys: Set<HiddenLibraryItemKey> = emptySet(),
): List<Playlist> {
    val details = associateBy { it.id }
    return map { it.playlist }
        .selectablePlaylists(hiddenKeys)
        .filter { signedIn || it.isLocal || it.browseId == null }
        .map { details.getValue(it.id) }
}

fun List<PlaylistEntity>.localPlaylists(): List<PlaylistEntity> =
    filter { it.isEditable && it.bookmarkedAt != null && (it.isLocal || it.browseId == null) && !it.isSystemPlaylist() }
        .distinctBy { it.browseId?.removePrefix("VL") ?: it.id }

fun List<PlaylistItem>.distinctPlaylistItems(): List<PlaylistItem> = distinctBy { it.id.removePrefix("VL") }

private fun PlaylistEntity.isSystemPlaylist(): Boolean =
    id == PlaylistEntity.LIKED_PLAYLIST_ID ||
        id == PlaylistEntity.DOWNLOADED_PLAYLIST_ID ||
        id == PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID ||
        id == PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID
