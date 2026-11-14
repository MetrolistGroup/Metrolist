/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val playlistId: String? = null,
    val title: String,
    val year: Int? = null,
    val thumbnailUrl: String? = null,
    val themeColor: Int? = null,
    val songCount: Int,
    val duration: Int,
    @ColumnInfo(defaultValue = "0")
    val explicit: Boolean = false,
    val lastUpdateTime: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    val bookmarkedAt: LocalDateTime? = null,
    val likedDate: LocalDateTime? = null,
    val inLibrary: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "0")
    val isLocal: Boolean = false,
    @ColumnInfo(name = "isUploaded", defaultValue = "0")
    val isUploaded: Boolean = false,
)
