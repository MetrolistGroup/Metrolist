package com.metrolist.shared.db

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.artworkExplicitness
import kotlinx.datetime.LocalDateTime

internal suspend fun MusicDatabase.syncRemoteLibraryItems(
    items: List<YTItem>,
    now: LocalDateTime,
    preserveBookmarkKeys: Set<String> = emptySet(),
) {
    useWriterConnection { connection ->
        connection.immediateTransaction {
            val dao = dao()
            val songIds = items.filterIsInstance<SongItem>().map { it.id }.distinct()
            val albumItems = items.filterIsInstance<AlbumItem>()
            val artistIds =
                buildSet {
                    items.filterIsInstance<ArtistItem>().forEach { add(it.id) }
                    items.filterIsInstance<SongItem>().forEach { song -> song.artists.mapNotNullTo(this) { it.id } }
                    albumItems.forEach { album ->
                        album.artists.forEachIndexed { index, artist ->
                            add(artist.id?.takeIf(String::isNotBlank) ?: "LA:${album.id}:$index")
                        }
                    }
                }
            val songs =
                songIds
                    .chunked(999)
                    .flatMap { dao.songEntitiesByIds(it) }
                    .associateBy { it.id }
                    .toMutableMap()
            val artists =
                artistIds
                    .chunked(999)
                    .flatMap { dao.artistEntitiesByIds(it) }
                    .associateBy { it.id }
                    .toMutableMap()
            val albums =
                albumItems
                    .map { it.id }
                    .distinct()
                    .chunked(999)
                    .flatMap { dao.albumEntitiesByIds(it) }
                    .associateBy { it.id }
                    .toMutableMap()
            val songArtists = mutableListOf<SongArtistMap>()
            val albumArtists = mutableListOf<AlbumArtistMap>()
            for (item in items) {
                when (item) {
                    is PlaylistItem -> {
                        continue
                    }

                    is SongItem -> {
                        val existing = songs[item.id]
                        songs[item.id] =
                            existing?.copy(
                                title = item.title,
                                duration = item.duration?.toInt() ?: existing.duration,
                                thumbnailUrl = item.thumbnail ?: existing.thumbnailUrl,
                                albumId = item.album?.id ?: existing.albumId,
                                albumName = item.album?.name ?: existing.albumName,
                                explicit = item.isExplicit,
                                explicitness = item.artworkExplicitness,
                                inLibrary = existing.inLibrary ?: now,
                                libraryAddToken = item.libraryAddToken,
                                libraryRemoveToken = item.libraryRemoveToken,
                                isUploaded = existing.isUploaded || item.isUploaded,
                                isLocal = item.isLocal,
                                uploadEntityId = item.uploadEntityId ?: existing.uploadEntityId,
                            ) ?: SongEntity(
                                id = item.id,
                                title = item.title,
                                duration = item.duration?.toInt() ?: -1,
                                thumbnailUrl = item.thumbnail,
                                albumId = item.album?.id,
                                albumName = item.album?.name,
                                explicit = item.isExplicit,
                                explicitness = item.artworkExplicitness,
                                inLibrary = now,
                                libraryAddToken = item.libraryAddToken,
                                libraryRemoveToken = item.libraryRemoveToken,
                                isUploaded = item.isUploaded,
                                isLocal = item.isLocal,
                                uploadEntityId = item.uploadEntityId,
                            )
                        item.artists.forEachIndexed { index, artist ->
                            val artistId = artist.id ?: return@forEachIndexed
                            val old = artists[artistId]
                            artists[artistId] =
                                old?.copy(name = artist.name, thumbnailUrl = artist.thumbnail ?: old.thumbnailUrl)
                                    ?: ArtistEntity(artistId, artist.name, artist.thumbnail)
                            songArtists += SongArtistMap(item.id, artistId, index)
                        }
                    }

                    is ArtistItem -> {
                        val old = artists[item.id]
                        artists[item.id] =
                            old?.copy(
                                name = item.title,
                                thumbnailUrl = item.thumbnail ?: old.thumbnailUrl,
                                bookmarkedAt =
                                    old.bookmarkedAt ?: now,
                            )
                                ?: ArtistEntity(item.id, item.title, item.thumbnail, bookmarkedAt = now)
                    }

                    is AlbumItem -> {
                        val old = albums[item.id]
                        val bookmark = if ("album:${item.id}" in preserveBookmarkKeys) old?.bookmarkedAt else old?.bookmarkedAt ?: now
                        albums[item.id] =
                            old?.copy(
                                playlistId = item.playlistId,
                                title = item.title,
                                year = item.year?.toIntOrNull(),
                                thumbnailUrl = item.thumbnail ?: old.thumbnailUrl,
                                explicit = item.isExplicit,
                                bookmarkedAt = bookmark,
                            ) ?: AlbumEntity(
                                id = item.id,
                                playlistId = item.playlistId,
                                title = item.title,
                                year = item.year?.toIntOrNull(),
                                thumbnailUrl = item.thumbnail,
                                songCount = 0,
                                duration = 0,
                                explicit = item.isExplicit,
                                bookmarkedAt = bookmark,
                            )
                        item.artists.forEachIndexed { index, artist ->
                            val artistId = artist.id?.takeIf(String::isNotBlank) ?: "LA:${item.id}:$index"
                            val existingArtist = artists[artistId]
                            artists[artistId] =
                                existingArtist?.copy(name = artist.name, thumbnailUrl = artist.thumbnail ?: existingArtist.thumbnailUrl)
                                    ?: ArtistEntity(artistId, artist.name, artist.thumbnail, isLocal = artist.id.isNullOrBlank())
                            albumArtists += AlbumArtistMap(item.id, artistId, index)
                        }
                    }

                    is PodcastItem, is EpisodeItem, is BrowseItem -> {}
                }
            }

            dao.upsertSongEntities(songs.values.toList())
            dao.upsertArtistEntities(artists.values.toList())
            dao.upsertAlbumEntities(albums.values.toList())
            dao.upsertSongArtists(songArtists)
            dao.upsertAlbumArtists(albumArtists)
        }
    }
}
