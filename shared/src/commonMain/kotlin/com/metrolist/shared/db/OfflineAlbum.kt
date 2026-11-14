package com.metrolist.shared.db

import com.metrolist.shared.db.entities.Album
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistTiny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

suspend fun MusicDatabase.loadOfflineAlbum(
    albumId: String,
    availableSongIds: Set<String>? = null,
): AlbumPage? {
    val dao = dao()
    val stored = dao.album(albumId).first()
    val songs =
        dao.storedAlbumSongs(albumId).first().filter {
            it.isAvailableOffline(availableSongIds)
        }
    val album = offlineAlbumHeader(albumId, stored, songs) ?: return null
    return AlbumPage(
        album = album,
        songs = songs.map { it.toSongItem() },
    )
}

internal suspend fun MusicDatabase.loadOfflineAlbumHeader(
    albumId: String,
    availableSongIds: Set<String>?,
): AlbumItem? =
    withContext(Dispatchers.Default) {
        val dao = dao()
        val stored = dao.album(albumId).first()
        val songs = dao.storedAlbumSongs(albumId).first().filter { it.isAvailableOffline(availableSongIds) }
        offlineAlbumHeader(albumId, stored, songs)
    }

private fun offlineAlbumHeader(
    albumId: String,
    stored: Album?,
    songs: List<Song>,
): AlbumItem? {
    val firstSong = songs.firstOrNull() ?: return null
    val album = stored?.album
    val title = album?.title ?: firstSong.song.albumName ?: return null
    return AlbumItem(
        id = albumId,
        title = title,
        thumbnail = album?.thumbnailUrl ?: firstSong.song.thumbnailUrl,
        artists =
            (stored?.artists?.takeIf { it.isNotEmpty() } ?: firstSong.orderedArtists).map {
                ArtistTiny(
                    it.id,
                    it.name,
                    it.thumbnailUrl,
                )
            },
        year = album?.year?.toString(),
        playlistId = album?.playlistId,
        isExplicit = album?.explicit ?: songs.any { it.song.explicit },
        songCount = album?.songCount?.takeIf { it > 0 },
        duration = album?.duration?.takeIf { it > 0 }?.toLong(),
    )
}
