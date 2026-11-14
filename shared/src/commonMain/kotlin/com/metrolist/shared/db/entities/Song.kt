/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Embedded
import androidx.room3.Junction
import androidx.room3.Relation

data class Song(
    @Embedded val song: SongEntity,
    @Relation(
        entity = ArtistEntity::class,
        entityColumns = ["id"],
        parentColumns = ["id"],
        associateBy =
            Junction(
                value = SortedSongArtistMap::class,
                parentColumns = ["songId"],
                entityColumns = ["artistId"],
            ),
    )
    val artists: List<ArtistEntity>,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["songId"],
    )
    val artistMaps: List<SongArtistMap> = emptyList(),
    @Relation(
        entity = AlbumEntity::class,
        entityColumns = ["id"],
        parentColumns = ["id"],
        associateBy =
            Junction(
                value = SongAlbumMap::class,
                parentColumns = ["songId"],
                entityColumns = ["albumId"],
            ),
    )
    val album: AlbumEntity? = null,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["id"],
    )
    val format: FormatEntity? = null,
) : LocalItem() {
    override val id: String
        get() = song.id
    override val title: String
        get() = song.title
    override val thumbnailUrl: String?
        get() = song.thumbnailUrl?.takeIf(String::isNotBlank) ?: album?.thumbnailUrl?.takeIf(String::isNotBlank)
    val romanizeLyrics: Boolean
        get() = song.romanizeLyrics

    val orderedArtists: List<ArtistEntity>
        get() {
            if (artistMaps.isEmpty()) return artists

            val artistsById = artists.associateBy { it.id }
            val sorted =
                artistMaps
                    .sortedBy { it.position }
                    .mapNotNull { map -> artistsById[map.artistId] }

            return sorted.ifEmpty { artists }
        }
}
