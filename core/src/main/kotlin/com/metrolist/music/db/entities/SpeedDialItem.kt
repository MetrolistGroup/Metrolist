package com.metrolist.music.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem

@Entity(tableName = "speed_dial_item")
data class SpeedDialItem(
    @PrimaryKey val id: String,
    val secondaryId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val subtitleIds: String? = null,
    val thumbnailUrl: String? = null,
    val type: String, // "SONG", "ALBUM", "ARTIST", "PLAYLIST", "LOCAL_PLAYLIST"
    val explicit: Boolean = false,
    val createDate: Long = System.currentTimeMillis(),
    val albumId: String? = null,
    val albumName: String? = null
) {
    fun toYTItem(): YTItem {
        return when (type) {
            "SONG" -> SongItem(
                id = id,
                title = title,
                artists = subtitle?.split(", ")?.mapIndexed { index, name ->
                    Artist(name = name, id = subtitleIds?.split(", ")?.getOrNull(index))
                } ?: emptyList(),
                album = if (albumId != null && albumName != null) com.metrolist.innertube.models.Album(name = albumName, id = albumId) else null,
                thumbnail = thumbnailUrl ?: "",
                explicit = explicit
            )
            "ALBUM" -> AlbumItem(
                browseId = id,
                playlistId = secondaryId ?: "",
                title = title,
                artists = subtitle?.split(", ")?.mapIndexed { index, name ->
                    Artist(name = name, id = subtitleIds?.split(", ")?.getOrNull(index))
                },
                thumbnail = thumbnailUrl ?: "",
                explicit = explicit
            )
            "ARTIST" -> ArtistItem(
                id = id,
                title = title,
                thumbnail = thumbnailUrl,
                shuffleEndpoint = null,
                radioEndpoint = null
            )
            "PLAYLIST", "LOCAL_PLAYLIST" -> PlaylistItem(
                id = id,
                title = title,
                author = subtitle?.let { name ->
                    Artist(name = name, id = subtitleIds)
                },
                songCountText = null,
                thumbnail = thumbnailUrl,
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null
            )
            else -> throw IllegalArgumentException("Unknown type: $type")
        }
    }

    companion object {
        fun fromYTItem(item: YTItem): SpeedDialItem {
            return when (item) {
                is SongItem -> SpeedDialItem(
                    id = item.id,
                    title = item.title,
                    subtitle = item.artists.joinToString(", ") { it.name },
                    subtitleIds = item.artists.joinToString(", ") { it.id ?: "" },
                    thumbnailUrl = item.thumbnail,
                    type = "SONG",
                    explicit = item.explicit,
                    albumId = item.album?.id,
                    albumName = item.album?.name
                )
                is AlbumItem -> SpeedDialItem(
                    id = item.browseId,
                    secondaryId = item.playlistId,
                    title = item.title,
                    subtitle = item.artists?.joinToString(", ") { it.name },
                    subtitleIds = item.artists?.joinToString(", ") { it.id ?: "" },
                    thumbnailUrl = item.thumbnail,
                    type = "ALBUM",
                    explicit = item.explicit
                )
                is ArtistItem -> SpeedDialItem(
                    id = item.id,
                    title = item.title,
                    thumbnailUrl = item.thumbnail,
                    type = "ARTIST"
                )
                is PlaylistItem -> SpeedDialItem(
                    id = item.id,
                    title = item.title,
                    subtitle = item.author?.name,
                    subtitleIds = item.author?.id,
                    thumbnailUrl = item.thumbnail,
                    type = "PLAYLIST"
                )
                is PodcastItem -> SpeedDialItem(
                    id = item.id,
                    title = item.title,
                    subtitle = item.author?.name,
                    subtitleIds = item.author?.id,
                    thumbnailUrl = item.thumbnail,
                    type = "PLAYLIST"
                )
                is EpisodeItem -> SpeedDialItem(
                    id = item.id,
                    title = item.title,
                    subtitle = item.author?.name,
                    subtitleIds = item.author?.id,
                    thumbnailUrl = item.thumbnail,
                    type = "SONG",
                    explicit = item.explicit,
                    albumId = item.podcast?.id,
                    albumName = item.podcast?.name
                )
            }
        }
    }
}
