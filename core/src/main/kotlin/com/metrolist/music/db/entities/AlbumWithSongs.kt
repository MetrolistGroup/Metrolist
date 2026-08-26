/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

@Immutable
data class AlbumWithSongs(
    @Embedded
    val album: AlbumEntity,
    @Relation(
        entity = ArtistEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy =
        Junction(
            value = AlbumArtistMap::class,
            parentColumn = "albumId",
            entityColumn = "artistId",
        ),
    )
    val artists: List<ArtistEntity>,
    @Relation(
        entity = SongEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        associateBy =
        Junction(
            value = SortedSongAlbumMap::class,
            parentColumn = "albumId",
            entityColumn = "songId",
        ),
    )
    val songs: List<Song>,
)
