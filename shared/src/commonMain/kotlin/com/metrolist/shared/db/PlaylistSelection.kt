package com.metrolist.shared.db

import com.metrolist.shared.api.isMixPlaylistId
import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.isEditablePlaylistId
import com.metrolist.shared.model.isSystemPlaylistId
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

fun List<PlaylistEntity>.selectablePlaylists(hiddenKeys: Set<HiddenLibraryItemKey> = emptySet()): List<PlaylistEntity> =
    filter {
        it.isEditable && it.bookmarkedAt != null && !it.isSystemPlaylist() &&
            (it.isLocal || it.browseId == null || it.browseId.isEditablePlaylistId()) &&
            HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, it.browseId ?: it.id).normalized() !in hiddenKeys
    }.distinctBy { it.localCopyKey() }

fun List<Playlist>.selectablePlaylistItems(
    signedIn: Boolean,
    hiddenKeys: Set<HiddenLibraryItemKey> = emptySet(),
    playtimes: Map<String, Long?> = emptyMap(),
): List<PlaylistItem> = selectablePlaylistDetails(signedIn, hiddenKeys).map { it.toChooserItem(playtimes, it.playlist.isEditable) }

fun List<Playlist>.readOnlyPlaylistItems(
    signedIn: Boolean,
    hiddenKeys: Set<HiddenLibraryItemKey> = emptySet(),
    playtimes: Map<String, Long?> = emptyMap(),
): List<PlaylistItem> = readOnlyPlaylistDetails(signedIn, hiddenKeys).map { it.toChooserItem(playtimes, isEditable = false) }

private fun Playlist.toChooserItem(
    playtimes: Map<String, Long?>,
    isEditable: Boolean,
): PlaylistItem {
    val entity = playlist
    return PlaylistItem(
        id = entity.browseId ?: entity.id,
        title = entity.name,
        thumbnail = entity.customThumbnailUrl ?: entity.thumbnailUrl,
        isEditable = isEditable,
        isLocal = entity.isLocal || entity.browseId == null,
        customThumbnail = entity.customThumbnailUrl,
        songCount = songCount.toString(),
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

// Saved YouTube playlists cannot be written while signed out; the chooser lists them only to show membership.
// Mixes are left out because their membership lookup never reaches the end of their continuations.
fun List<Playlist>.readOnlyPlaylistDetails(
    signedIn: Boolean,
    hiddenKeys: Set<HiddenLibraryItemKey> = emptySet(),
): List<Playlist> {
    if (signedIn) return emptyList()
    return filter {
        val entity = it.playlist
        entity.bookmarkedAt != null && !entity.isLocal && entity.browseId != null && !entity.isSystemPlaylist() &&
            !entity.browseId.isSystemPlaylistId() && !entity.browseId.isMixPlaylistId() &&
            HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, entity.browseId).normalized() !in hiddenKeys
    }.distinctBy { it.playlist.localCopyKey() }
}

fun List<PlaylistEntity>.localPlaylists(): List<PlaylistEntity> =
    filter { it.isEditable && it.bookmarkedAt != null && (it.isLocal || it.browseId == null) && !it.isSystemPlaylist() }
        .distinctBy { it.localCopyKey() }

// A local copy of a YouTube playlist is its own playlist, separate from the saved remote one.
private fun PlaylistEntity.localCopyKey(): String = if (isLocal) id else browseId?.removePrefix("VL") ?: id

fun List<PlaylistItem>.distinctPlaylistItems(): List<PlaylistItem> = distinctBy { it.id.removePrefix("VL") }

private fun PlaylistEntity.isSystemPlaylist(): Boolean =
    id == PlaylistEntity.LIKED_PLAYLIST_ID ||
        id == PlaylistEntity.DOWNLOADED_PLAYLIST_ID ||
        id == PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID ||
        id == PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID
