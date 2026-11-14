/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.artworkExplicitness
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Entity(tableName = "speed_dial")
data class SpeedDialItem(
    @PrimaryKey val id: String,
    val secondaryId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val subtitleIds: String? = null,
    val thumbnailUrl: String? = null,
    val type: String,
    val explicit: Boolean = false,
    val index: Int = 0,
    val createDate: Long = Clock.System.now().toEpochMilliseconds(),
    val albumId: String? = null,
    val albumName: String? = null,
    val musicVideoType: String? = null,
    val explicitness: Boolean? = null,
) {
    fun toYTItem(): YTItem =
        when (type) {
            "SONG" -> {
                SongItem(
                    id = id,
                    title = title,
                    thumbnail = thumbnailUrl,
                    artists = decodedArtists(),
                    album =
                        if (albumId != null && albumName != null) {
                            AlbumTiny(id = albumId, name = albumName)
                        } else {
                            null
                        },
                    isExplicit = explicit,
                    musicVideoType = musicVideoType,
                    explicitness = explicitness,
                )
            }

            "ALBUM" -> {
                AlbumItem(
                    id = id,
                    title = title,
                    thumbnail = thumbnailUrl,
                    artists = decodedArtists(),
                    playlistId = secondaryId,
                    isExplicit = explicit,
                )
            }

            "ARTIST" -> {
                ArtistItem(
                    id = id,
                    title = title,
                    thumbnail = thumbnailUrl,
                )
            }

            "PLAYLIST" if id.startsWith("MPSP") -> {
                PodcastItem(
                    id = id,
                    title = title,
                    thumbnail = thumbnailUrl,
                    author = subtitle,
                )
            }

            "PLAYLIST", "LOCAL_PLAYLIST" -> {
                PlaylistItem(
                    id = id,
                    title = title,
                    thumbnail = thumbnailUrl,
                    author = subtitle,
                    isLocal = type == "LOCAL_PLAYLIST",
                )
            }

            "PODCAST" -> {
                PodcastItem(
                    id = id,
                    title = title,
                    thumbnail = thumbnailUrl,
                    author = subtitle,
                )
            }

            else -> {
                throw IllegalArgumentException("Unknown type: $type")
            }
        }

    companion object {
        fun fromYTItem(item: YTItem): SpeedDialItem =
            when (item) {
                is SongItem -> {
                    SpeedDialItem(
                        id = item.id,
                        title = item.title,
                        subtitle = Json.encodeToString(item.artists.map { it.name }),
                        subtitleIds = Json.encodeToString(item.artists.map { it.id }),
                        thumbnailUrl = item.thumbnail,
                        type = "SONG",
                        explicit = item.isExplicit,
                        explicitness = item.artworkExplicitness,
                        albumId = item.album?.id,
                        albumName = item.album?.name,
                        musicVideoType = item.musicVideoType,
                    )
                }

                is AlbumItem -> {
                    SpeedDialItem(
                        id = item.id,
                        secondaryId = item.playlistId,
                        title = item.title,
                        subtitle = Json.encodeToString(item.artists.map { it.name }),
                        subtitleIds = Json.encodeToString(item.artists.map { it.id }),
                        thumbnailUrl = item.thumbnail,
                        type = "ALBUM",
                        explicit = item.isExplicit,
                    )
                }

                is ArtistItem -> {
                    SpeedDialItem(
                        id = item.id,
                        title = item.title,
                        thumbnailUrl = item.thumbnail,
                        type = "ARTIST",
                    )
                }

                is PlaylistItem -> {
                    SpeedDialItem(
                        id = item.id,
                        title = item.title,
                        subtitle = item.author,
                        thumbnailUrl = item.thumbnail,
                        type = if (item.isLocal) "LOCAL_PLAYLIST" else "PLAYLIST",
                    )
                }

                is PodcastItem -> {
                    SpeedDialItem(
                        id = item.id,
                        title = item.title,
                        subtitle = item.author,
                        thumbnailUrl = item.thumbnail,
                        type = "PODCAST",
                    )
                }

                else -> {
                    throw IllegalArgumentException("Unsupported YTItem type: ${item::class.simpleName}")
                }
            }
    }

    private fun decodedArtists(): List<ArtistTiny> {
        val names = subtitle.decodeStringList()
        val ids = subtitleIds.decodeNullableStringList()
        return names.mapIndexed { index, name ->
            ArtistTiny(id = ids.getOrNull(index).withoutHistoryArtistPlaceholder(), name = name)
        }
    }
}

internal fun String?.withoutHistoryArtistPlaceholder(): String? =
    this?.takeIf { it.isNotBlank() && it != "unknown" && !it.startsWith("LH:") }

private fun String?.decodeStringList(): List<String> =
    when {
        this == null -> emptyList()
        startsWith("[") -> runCatching { Json.decodeFromString<List<String>>(this) }.getOrElse { split(", ") }
        else -> split(", ")
    }

private fun String?.decodeNullableStringList(): List<String?> =
    when {
        this == null -> emptyList()
        startsWith("[") -> runCatching { Json.decodeFromString<List<String?>>(this) }.getOrElse { split(", ") }
        else -> split(", ")
    }
