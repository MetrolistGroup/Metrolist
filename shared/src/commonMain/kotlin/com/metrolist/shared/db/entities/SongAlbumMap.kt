/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "song_album_map",
    primaryKeys = ["songId", "albumId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SongAlbumMap(
    @ColumnInfo(index = true) val songId: String,
    @ColumnInfo(index = true) val albumId: String,
    val index: Int,
)
