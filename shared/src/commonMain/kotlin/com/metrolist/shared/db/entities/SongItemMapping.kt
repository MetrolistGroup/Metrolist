package com.metrolist.shared.db.entities

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO
import com.metrolist.shared.model.SongItem

fun Song.toSongItem(): SongItem {
    val canonicalArtists =
        orderedArtists.map { artist ->
            ArtistTiny(
                id = if (artist.isLocal) null else artist.id.withoutHistoryArtistPlaceholder(),
                name = artist.name,
                thumbnail = artist.thumbnailUrl,
            )
        }
    return SongItem(
        id = song.id,
        title = song.customTitle ?: song.title,
        customTitle = song.customTitle,
        customArtist = song.customArtist,
        canonicalTitle = song.title,
        canonicalArtists = canonicalArtists,
        thumbnail = thumbnailUrl,
        artists =
            song.customArtist?.takeIf(String::isNotBlank)?.let { name ->
                listOf(canonicalArtists.firstOrNull()?.copy(name = name) ?: ArtistTiny(null, name))
            } ?: canonicalArtists,
        album = song.albumName?.let { AlbumTiny(song.albumId, it) } ?: song.albumId?.let { AlbumTiny(it, "") },
        duration = song.duration.takeIf { it > 0 }?.toLong(),
        isExplicit = song.explicit,
        isUnavailable = song.isUnavailable,
        endpointParams = song.id.takeIf { song.isLocal && it.startsWith("file:") },
        libraryAddToken = song.libraryAddToken,
        libraryRemoveToken = song.libraryRemoveToken,
        isUploaded = song.isUploaded,
        isLocal = song.isLocal,
        year = song.year ?: album?.year,
        uploadEntityId = song.uploadEntityId,
        musicVideoType =
            when {
                song.isEpisode -> "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
                song.isVideo -> MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO
                else -> null
            },
        podcastId = song.albumId.takeIf { song.isEpisode },
        explicitness = song.explicitness ?: song.explicit.takeIf { it },
    )
}

fun SongItem.withStoredDownloadMetadata(stored: Song?): SongItem =
    stored?.toSongItem()?.copy(thumbnail = thumbnail ?: stored.thumbnailUrl) ?: this
