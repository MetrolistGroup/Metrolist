/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val id: String,
    val lyrics: String,
    @ColumnInfo(defaultValue = "Unknown") val provider: String = "Unknown",
    @ColumnInfo(defaultValue = "") val translatedLyrics: String = "",
    @ColumnInfo(defaultValue = "") val translationLanguage: String = "",
    @ColumnInfo(defaultValue = "") val translationMode: String = "",
) {
    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND"
    }
}
