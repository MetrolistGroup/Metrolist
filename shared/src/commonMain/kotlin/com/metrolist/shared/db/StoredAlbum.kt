package com.metrolist.shared.db

import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.first

data class CachedAlbum(
    val page: AlbumPage,
    val isComplete: Boolean,
    val hasCachedDescription: Boolean,
    val isOfflineReady: Boolean,
    val hasRequiredMetadata: Boolean = true,
) {
    val canReuseWithoutRefresh: Boolean
        get() = isComplete && hasCachedDescription && isOfflineReady && hasRequiredMetadata
}

suspend fun MusicDatabase.loadCachedAlbum(
    albumId: String,
    availableSongIds: Set<String>? = null,
): CachedAlbum? {
    val stored = dao().album(albumId).first()
    val songs = dao().storedAlbumSongs(albumId).first()
    if (songs.isEmpty()) return null
    val album = stored?.album
    val title = album?.title ?: songs.first().song.albumName ?: return null
    val isComplete = album != null && album.songCount > 0 && songs.size >= album.songCount
    val hasCachedDescription = album?.description != null
    val playableSongs = songs.filterNot { it.song.isUnavailable }
    val isOfflineReady =
        album?.isLocal == true ||
            (playableSongs.isNotEmpty() && playableSongs.all { it.isAvailableOffline(availableSongIds) })
    val hasRequiredMetadata =
        album?.isLocal == true ||
            albumId.startsWith("FEmusic_library_privately_owned_release") ||
            !album?.playlistId.isNullOrBlank()
    return CachedAlbum(
        page =
            AlbumPage(
                album =
                    AlbumItem(
                        id = albumId,
                        title = title,
                        thumbnail = album?.thumbnailUrl ?: songs.first().thumbnailUrl,
                        artists =
                            (stored?.artists?.takeIf { it.isNotEmpty() } ?: songs.first().orderedArtists).map { artist ->
                                ArtistTiny(artist.id.takeUnless { artist.isLocal }, artist.name, artist.thumbnailUrl)
                            },
                        year = album?.year?.toString(),
                        playlistId = album?.playlistId,
                        isExplicit = album?.explicit ?: songs.any { it.song.explicit },
                        songCount = album?.songCount?.takeIf { it > 0 },
                        duration = album?.duration?.takeIf { it > 0 }?.toLong(),
                    ),
                songs = songs.map { it.toSongItem() },
                description = album?.description?.takeIf(String::isNotBlank),
            ),
        isComplete = isComplete,
        hasCachedDescription = hasCachedDescription,
        isOfflineReady = isOfflineReady,
        hasRequiredMetadata = hasRequiredMetadata,
    )
}

suspend fun MusicDatabase.loadStoredAlbum(albumId: String): AlbumPage? = loadCachedAlbum(albumId)?.page

suspend fun MusicDatabase.storeAlbumPage(
    page: AlbumPage,
    incrementalSongs: List<SongItem>? = null,
    updateDescription: Boolean = false,
) {
    withDatabaseTransaction {
        val dao = dao()
        val album = page.album
        val existing = dao.album(album.id).first()?.album
        val complete = page.playlistPage?.hasMore != true
        val songCount =
            if (complete) {
                page.songs.size
            } else {
                album.songCount?.takeIf { it > 0 } ?: existing?.songCount?.takeIf { it > 0 } ?: 0
            }
        val duration =
            album.duration
                ?: if (complete) {
                    page.songs.sumOf { it.duration ?: 0L }
                } else {
                    existing
                        ?.duration
                        ?.takeIf { it > 0 }
                        ?.toLong() ?: 0L
                }
        // Empty string distinguishes a fetched album with no description from uncached rows (null).
        val description =
            if (updateDescription) {
                page.description?.trim().orEmpty()
            } else {
                page.description?.trim()?.takeIf(String::isNotEmpty) ?: existing?.description
            }
        dao.upsert(
            (existing ?: AlbumEntity(id = album.id, title = album.title, songCount = 0, duration = 0)).copy(
                title = album.title,
                thumbnailUrl = album.thumbnail ?: existing?.thumbnailUrl,
                playlistId = album.playlistId ?: existing?.playlistId,
                year = album.year?.toIntOrNull() ?: existing?.year,
                explicit = album.isExplicit,
                songCount = songCount,
                duration = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                description = description,
            ),
        )
        val savedPositions = if (complete) emptyMap() else dao.albumSongMaps(album.id).associate { it.songId to it.index }
        var nextPosition = (savedPositions.values.maxOrNull() ?: -1) + 1
        if (complete) dao.clearAlbumSongs(album.id)
        (if (complete) page.songs else incrementalSongs ?: page.songs).forEachIndexed { index, song ->
            dao.upsertHistorySong(song)
            val position = if (complete) index else savedPositions[song.id] ?: nextPosition++
            dao.upsert(SongAlbumMap(song.id, album.id, position))
        }
        storeAlbumArtists(album)
    }
}

internal suspend fun MusicDatabase.storeAlbumArtists(album: AlbumItem) {
    val dao = dao()
    if (album.artists.isNotEmpty()) {
        dao.clearAlbumArtists(album.id)
        album.artists.forEachIndexed { index, artist ->
            val artistId = artist.id?.takeIf(String::isNotBlank) ?: "LA:${album.id}:$index"
            val storedArtist = dao.getArtistById(artistId)
            dao.upsert(
                storedArtist?.copy(name = artist.name, thumbnailUrl = artist.thumbnail ?: storedArtist.thumbnailUrl)
                    ?: ArtistEntity(
                        id = artistId,
                        name = artist.name,
                        thumbnailUrl = artist.thumbnail,
                        isLocal = artist.id.isNullOrBlank(),
                    ),
            )
            dao.upsert(AlbumArtistMap(album.id, artistId, index))
        }
    }
}
