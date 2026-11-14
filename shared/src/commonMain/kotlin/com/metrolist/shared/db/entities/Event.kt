/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.datetime.LocalDateTime

@Entity(
    tableName = "event",
    indices = [
        Index(value = ["remoteFeedbackToken"], unique = true),
        Index(value = ["timestamp"]),
        Index(value = ["songId", "timestamp", "remoteOrder", "id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val songId: String,
    val timestamp: LocalDateTime,
    val playTime: Long,
    @ColumnInfo(defaultValue = "1") val playTimeKnown: Boolean = true,
    @ColumnInfo(defaultValue = "'LOCAL'") val source: String = "LOCAL",
    val remoteFeedbackToken: String? = null,
    @ColumnInfo(defaultValue = "0") val remoteSynced: Boolean = false,
    val remoteDateLabel: String? = null,
    val remoteAccountKey: String? = null,
    @ColumnInfo(defaultValue = "0") val remoteOrder: Int = 0,
)

val Event.knownPlayTimeMs: Long get() = if (playTimeKnown) playTime else 0L
