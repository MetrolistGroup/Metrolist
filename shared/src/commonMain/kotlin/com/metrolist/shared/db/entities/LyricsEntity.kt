/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val id: String,
    val lyrics: String,
    @ColumnInfo(defaultValue = "Unknown") val provider: String = "Unknown",
    @ColumnInfo(defaultValue = "") val translatedLyrics: String = "",
    @ColumnInfo(defaultValue = "") val translationLanguage: String = "",
    @ColumnInfo(defaultValue = "") val translationMode: String = "",
    @ColumnInfo(defaultValue = "") val translationSourceHash: String = "",
    @ColumnInfo(defaultValue = "") val translationConfigKey: String = "",
    @ColumnInfo(defaultValue = "0") val translationNotNeeded: Boolean = false,
    @ColumnInfo(defaultValue = "0") val offsetMs: Int = 0,
    @ColumnInfo(defaultValue = "0") val isUserSelected: Boolean = false,
    val userLyrics: String? = null,
    val userLyricsDraft: String? = null,
    // A User fork set aside by choosing a provider, kept so the user can restore it.
    val stashedUserLyrics: String? = null,
    val stashedUserLyricsDraft: String? = null,
    val missLookupKey: String? = null,
    val missExpiresAt: Long? = null,
) {
    companion object {
        const val LYRICS_NOT_FOUND = "LYRICS_NOT_FOUND_V2"
    }
}
