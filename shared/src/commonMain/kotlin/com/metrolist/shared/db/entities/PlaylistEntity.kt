/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Clock

@Entity(tableName = "playlist", indices = [Index("browseId")])
data class PlaylistEntity(
    @PrimaryKey val id: String = generatePlaylistId(),
    val name: String,
    val browseId: String? = null,
    val createdAt: LocalDateTime? = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    val lastUpdateTime: LocalDateTime? = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    @ColumnInfo(name = "isEditable", defaultValue = "1")
    val isEditable: Boolean = true,
    val bookmarkedAt: LocalDateTime? = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    val remoteSongCount: Int? = null,
    val playEndpointParams: String? = null,
    val thumbnailUrl: String? = null,
    val shuffleEndpointParams: String? = null,
    val radioEndpointParams: String? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "0")
    val isLocal: Boolean = false,
    @ColumnInfo(name = "isAutoSync", defaultValue = "0")
    val isAutoSync: Boolean = false,
    val description: String? = null,
    val customThumbnailUrl: String? = null,
) {
    companion object {
        const val LIKED_PLAYLIST_ID = "LP_LIKED"
        const val DOWNLOADED_PLAYLIST_ID = "LP_DOWNLOADED"
        const val WEEKLY_MOST_PLAYLIST_ID = "LP_WEEKLY_MOST"
        const val MONTHLY_MOST_PLAYLIST_ID = "LP_MONTHLY_MOST"

        fun generatePlaylistId(): String {
            val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            val randomString =
                (1..8)
                    .map { Random.nextInt(0, charPool.size).let { charPool[it] } }
                    .joinToString("")
            return "LP$randomString"
        }
    }
}
