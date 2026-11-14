package com.metrolist.shared.db

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.albums
import com.metrolist.shared.generated.resources.songs
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

internal fun Song.isAvailableOffline(availableSongIds: Set<String>?): Boolean =
    song.isLocal || if (availableSongIds != null) id in availableSongIds else song.isDownloaded || song.isCached

suspend fun MusicDatabase.loadOfflineArtist(
    artistId: String,
    availableSongIds: Set<String>? = null,
): ArtistPageResult? {
    val dao = dao()
    val artist = dao.getArtistById(artistId) ?: return null
    val songs = dao.storedArtistSongs(artistId).first().filter { it.isAvailableOffline(availableSongIds) }
    if (songs.isEmpty()) return null
    val albums =
        songs
            .mapNotNull { it.song.albumId }
            .distinct()
            .mapNotNull { loadOfflineAlbumHeader(it, availableSongIds) }
    return ArtistPageResult(
        buildMap {
            put(
                "header",
                listOf(
                    ArtistItem(
                        artistId,
                        artist.name,
                        artist.thumbnailUrl,
                        channelId = artist.channelId,
                        isSubscribed =
                            artist.bookmarkedAt != null,
                    ),
                ),
            )
            put(getString(Res.string.songs), songs.map { it.toSongItem() })
            if (albums.isNotEmpty()) put(getString(Res.string.albums), albums)
        },
    )
}

suspend fun MusicDatabase.loadOfflinePlaylist(
    playlistId: String,
    availableSongIds: Set<String>? = null,
): Map<String, List<YTItem>>? {
    refreshHistoryPlaylist(playlistId)
    val dao = dao()
    val playlist = dao.findPlaylistEntity(playlistId) ?: dao.findPlaylistEntity(playlistId.removePrefix("VL")) ?: return null
    val songs =
        dao
            .playlistSongs(playlist.id)
            .first()
            .map { it.song }
            .filter { it.isAvailableOffline(availableSongIds) }
    if (songs.isEmpty()) return null
    return mapOf(
        "header" to listOf(playlist.toOfflinePlaylistHeader(playlistId)),
        getString(Res.string.songs) to songs.map { it.toSongItem() },
    )
}

private suspend fun MusicDatabase.loadOfflinePlaylistHeader(
    playlistId: String,
    availableSongIds: Set<String>?,
): PlaylistItem? =
    withContext(Dispatchers.Default) {
        val dao = dao()
        val playlist =
            dao.findPlaylistEntity(playlistId) ?: dao.findPlaylistEntity(playlistId.removePrefix("VL")) ?: return@withContext null
        if (dao.playlistSongs(playlist.id).first().none { it.song.isAvailableOffline(availableSongIds) }) return@withContext null
        playlist.toOfflinePlaylistHeader(playlistId)
    }

private fun PlaylistEntity.toOfflinePlaylistHeader(playlistId: String): PlaylistItem =
    PlaylistItem(
        playlistId,
        name,
        thumbnailUrl,
        isEditable = isEditable,
        isLocal = isLocal,
    )

suspend fun MusicDatabase.searchOfflineMedia(
    query: String,
    availableSongIds: Set<String>? = null,
): List<YTItem> {
    if (query.isBlank()) return emptyList()
    return withContext(Dispatchers.Default) {
        val dao = dao()
        val songs = dao.storedSearchSongs(query).first().filter { it.isAvailableOffline(availableSongIds) }
        val albums =
            songs
                .mapNotNull { it.song.albumId }
                .distinct()
                .mapNotNull { loadOfflineAlbumHeader(it, availableSongIds) }
                .filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.artists.any { artist -> artist.name.contains(query, ignoreCase = true) }
                }
        val artists =
            songs
                .flatMap { it.orderedArtists }
                .distinctBy { it.id }
                .filter { it.name.contains(query, ignoreCase = true) }
                .map { ArtistItem(it.id, it.name, it.thumbnailUrl, channelId = it.channelId, isSubscribed = it.bookmarkedAt != null) }
        val playlists =
            dao
                .allPlaylists()
                .first()
                .filter { it.name.contains(query, ignoreCase = true) }
                .mapNotNull { loadOfflinePlaylistHeader(it.id, availableSongIds) }
        songs.map { it.toSongItem() } + albums + artists + playlists
    }
}
