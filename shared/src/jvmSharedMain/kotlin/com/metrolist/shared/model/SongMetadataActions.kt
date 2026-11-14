package com.metrolist.shared.model

import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.daos.SongCustomMetadata
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongMetadataState

suspend fun resetSongMetadata(
    database: MusicDatabase,
    songId: String,
) {
    database.withDatabaseTransaction {
        val dao = database.dao()
        val song = dao.getSongById(songId)?.song ?: return@withDatabaseTransaction
        dao.upsertSongEntity(song.copy(customTitle = null, customArtist = null))
    }
}

suspend fun editSongMetadata(
    database: MusicDatabase,
    song: SongItem,
    title: String,
    artistName: String,
) {
    database.withDatabaseTransaction {
        val dao = database.dao()
        val existing = dao.getSongById(song.id)
        dao.upsert(
            (existing?.song ?: song.toEntity()).copy(
                customTitle = title,
                customArtist = artistName,
            ),
        )
        ensureSongArtists(database, song, existing == null)
    }
}

suspend fun refetchSongMetadata(
    database: MusicDatabase,
    musicVideoResolver: MusicVideoResolver,
    song: SongItem,
    metadataLoader: suspend (String) -> SongItem? = GlobalYouTubeRepository.instance::getSongMetadata,
): SongItem? {
    val initialCustomMetadata =
        database.dao().songCustomMetadata(song.id)
            ?: SongCustomMetadata(song.customTitle, song.customArtist)
    val refreshed = metadataLoader(song.id) ?: return null
    musicVideoResolver.evictMapping(song)
    val stored =
        database.withDatabaseTransaction {
            val dao = database.dao()
            val current = dao.getSongById(song.id)?.song ?: song.toEntity()
            val customMetadata = SongCustomMetadata(current.customTitle, current.customArtist)
            val updated =
                current.copy(
                    title = refreshed.title.ifBlank { current.title },
                    customTitle = current.customTitle.takeUnless { customMetadata == initialCustomMetadata },
                    customArtist = current.customArtist.takeUnless { customMetadata == initialCustomMetadata },
                    duration = refreshed.duration?.toInt() ?: current.duration,
                    thumbnailUrl = refreshed.thumbnail ?: current.thumbnailUrl,
                    albumId = refreshed.album?.id ?: current.albumId,
                    albumName = refreshed.album?.name ?: current.albumName,
                    explicit = refreshed.isExplicit,
                    explicitness = refreshed.artworkExplicitness,
                    libraryAddToken = refreshed.libraryAddToken ?: current.libraryAddToken,
                    libraryRemoveToken = refreshed.libraryRemoveToken ?: current.libraryRemoveToken,
                    isVideo = isVideoType(refreshed.musicVideoType),
                )
            dao.upsertSongEntity(updated)

            if (refreshed.artists.isNotEmpty()) {
                dao.songArtistMap(song.id).forEach { dao.delete(it) }
                refreshed.artists.forEachIndexed { index, artist ->
                    val artistId = artist.id ?: ArtistEntity.generateArtistId()
                    val storedArtist = dao.getArtistById(artistId)
                    dao.upsert(
                        storedArtist?.copy(
                            name = artist.name,
                            thumbnailUrl = artist.thumbnail ?: storedArtist.thumbnailUrl,
                        ) ?: ArtistEntity(
                            id = artistId,
                            name = artist.name,
                            thumbnailUrl = artist.thumbnail,
                            isLocal = artist.id == null,
                        ),
                    )
                    dao.upsert(SongArtistMap(song.id, artistId, index))
                }
            }
            updated
        }
    val canonicalTitle = refreshed.title.ifBlank { stored.title }
    val canonicalArtists = refreshed.artists.ifEmpty { song.canonicalArtists ?: song.artists }
    val customArtist = stored.customArtist?.takeIf(String::isNotBlank)
    val result =
        refreshed.copy(
            id = song.id,
            title = stored.customTitle?.takeIf(String::isNotBlank) ?: canonicalTitle,
            customTitle = stored.customTitle,
            customArtist = stored.customArtist,
            canonicalTitle = canonicalTitle,
            canonicalArtists = canonicalArtists,
            thumbnail = refreshed.thumbnail ?: song.thumbnail,
            artists =
                customArtist?.let { name ->
                    listOf(canonicalArtists.firstOrNull()?.copy(name = name) ?: ArtistTiny(null, name))
                } ?: canonicalArtists,
            album = refreshed.album ?: song.album,
            duration = refreshed.duration?.takeIf { it > 0 } ?: song.duration,
            endpointParams = refreshed.endpointParams ?: song.endpointParams,
            libraryAddToken = refreshed.libraryAddToken ?: song.libraryAddToken,
            libraryRemoveToken = refreshed.libraryRemoveToken ?: song.libraryRemoveToken,
            musicVideoType = refreshed.musicVideoType ?: song.musicVideoType,
            year = refreshed.year ?: song.year,
        )
    SongMetadataState.publish(database, result, replacedSong = song)
    return result
}

private suspend fun ensureSongArtists(
    database: MusicDatabase,
    song: SongItem,
    shouldInsert: Boolean,
) {
    if (!shouldInsert) return
    val dao = database.dao()
    (song.canonicalArtists ?: song.artists).forEachIndexed { index, artist ->
        val artistId = artist.id ?: ArtistEntity.generateArtistId()
        dao.upsert(
            dao.getArtistById(artistId)?.copy(
                name = artist.name,
                thumbnailUrl = artist.thumbnail ?: dao.getArtistById(artistId)?.thumbnailUrl,
            ) ?: ArtistEntity(
                id = artistId,
                name = artist.name,
                thumbnailUrl = artist.thumbnail,
                isLocal = artist.id == null,
            ),
        )
        dao.upsert(SongArtistMap(song.id, artistId, index))
    }
}

fun SongItem.toEntity() =
    SongEntity(
        id = id,
        title = canonicalTitle ?: title,
        customTitle = customTitle,
        customArtist = customArtist,
        duration = duration?.toInt() ?: -1,
        thumbnailUrl = thumbnail,
        albumId = album?.id,
        albumName = album?.name,
        explicit = isExplicit,
        explicitness = artworkExplicitness,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
        isUploaded = isUploaded,
        isLocal = isLocal,
        uploadEntityId = uploadEntityId,
        isVideo = isVideoType(musicVideoType),
        isEpisode = musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
    )
