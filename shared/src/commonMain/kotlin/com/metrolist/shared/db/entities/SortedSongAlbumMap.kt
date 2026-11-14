/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.DatabaseView

@DatabaseView(
    viewName = "sorted_song_album_map",
    value = "SELECT * FROM song_album_map ORDER BY `index`",
)
data class SortedSongAlbumMap(
    @ColumnInfo(index = true) val songId: String,
    @ColumnInfo(index = true) val albumId: String,
    val index: Int,
)
