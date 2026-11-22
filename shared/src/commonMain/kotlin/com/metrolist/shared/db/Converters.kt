/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db

import androidx.room3.ColumnTypeConverter
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.PlaylistItem
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Instant

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @ColumnTypeConverter
    fun playlistHeaderToJson(header: PlaylistItem?): String? = header?.let { json.encodeToString(it) }

    @ColumnTypeConverter
    fun playlistHeaderFromJson(value: String?): PlaylistItem? = value?.let { json.decodeFromString<PlaylistItem>(it) }

    @ColumnTypeConverter
    fun albumListToJson(albums: List<AlbumItem>?): String? = albums?.let { json.encodeToString(it) }

    @ColumnTypeConverter
    fun albumListFromJson(value: String?): List<AlbumItem>? =
        value?.let { runCatching { json.decodeFromString<List<AlbumItem>>(it) }.getOrNull() }

    @ColumnTypeConverter
    fun fromTimestamp(value: Long?): LocalDateTime? =
        value?.let {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC)
        }

    @ColumnTypeConverter
    fun dateToTimestamp(date: LocalDateTime?): Long? = date?.toInstant(TimeZone.UTC)?.toEpochMilliseconds()
}
