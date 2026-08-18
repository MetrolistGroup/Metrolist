/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import androidx.compose.runtime.Immutable
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.ArtistNameAliases
import java.io.Serializable
import java.time.LocalDateTime

@Immutable
data class MediaMetadata(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val album: Album? = null,
    val setVideoId: String? = null,
    val musicVideoType: String? = null,
    val explicit: Boolean = false,
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,
    val inLibrary: LocalDateTime? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val suggestedBy: String? = null,
    val isEpisode: Boolean = false,
    val uploadEntityId: String? = null,
) : Serializable {
    val isVideoSong: Boolean
        get() = musicVideoType != null && musicVideoType != MUSIC_VIDEO_TYPE_ATV

    data class Artist(
        val id: String?,
        val name: String,
    ) : Serializable

    data class Album(
        val id: String,
        val title: String,
    ) : Serializable

    fun toSongEntity() =
        SongEntity(
            id = id,
            title = title,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            albumId = album?.id,
            albumName = album?.title,
            explicit = explicit,
            liked = liked,
            likedDate = likedDate,
            inLibrary = inLibrary,
            libraryAddToken = libraryAddToken,
            libraryRemoveToken = libraryRemoveToken,
            isVideo = isVideoSong,
            isEpisode = isEpisode,
            uploadEntityId = uploadEntityId
        )

    fun toYTItem() = SongItem(
        id = id,
        title = title,
        artists = artists.map { com.metrolist.innertube.models.Artist(name = it.name, id = it.id) },
        album = album?.let { com.metrolist.innertube.models.Album(name = it.title, id = it.id) },
        duration = duration,
        musicVideoType = musicVideoType,
        thumbnail = thumbnailUrl ?: "",
        explicit = explicit,
        setVideoId = setVideoId,
        isEpisode = isEpisode,
        uploadEntityId = uploadEntityId
    )
}

fun Song.toMediaMetadata() =
    MediaMetadata(
        id = song.id,
        title = song.title,
        artists =
        orderedArtists.map {
            MediaMetadata.Artist(
                id = it.id,
                name = ArtistNameAliases.resolve(it.id, it.name),
            )
        },
        duration = song.duration,
        thumbnailUrl = song.thumbnailUrl,
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.title,
            )
        } ?: song.albumId?.let { albumId ->
            MediaMetadata.Album(
                id = albumId,
                title = song.albumName.orEmpty(),
            )
        },
        explicit = song.explicit,
        // Use a non-ATV type if isVideo is true to indicate it's a video song
        musicVideoType = if (song.isVideo) "MUSIC_VIDEO_TYPE_OMV" else null,
        suggestedBy = null,
        isEpisode = song.isEpisode,
    )

/**
 * Converts an InnerTube [SongItem] into a [MediaMetadata] instance for use in the UI and player.
 * Thumbnails are resized to 1080x1080 for high-quality display.
 */
fun SongItem.toMediaMetadata() =
    MediaMetadata(
        id = id,
        title = title,
        artists =
        artists.map {
            MediaMetadata.Artist(
                id = it.id,
                name = ArtistNameAliases.resolve(it.id, it.name),
            )
        },
        duration = duration ?: -1,
        thumbnailUrl = thumbnail.resize(1080, 1080),
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.name,
            )
        },
        explicit = explicit,
        setVideoId = setVideoId,
        musicVideoType = musicVideoType,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
        suggestedBy = null,
        isEpisode = isEpisode,
        uploadEntityId = uploadEntityId
    )

/**
 * Converts an InnerTube [EpisodeItem] into a [MediaMetadata] instance.
 * The episode's podcast is mapped to [MediaMetadata.Album] and [MediaMetadata.isEpisode] is set.
 */
fun EpisodeItem.toMediaMetadata() =
    MediaMetadata(
        id = id,
        title = title,
        artists = listOfNotNull(author).map {
            MediaMetadata.Artist(
                id = it.id,
                name = ArtistNameAliases.resolve(it.id, it.name),
            )
        },
        duration = duration ?: -1,
        thumbnailUrl = thumbnail.resize(1080, 1080),
        album = podcast?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.name,
            )
        },
        explicit = explicit,
        suggestedBy = null,
        isEpisode = true,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
    )
