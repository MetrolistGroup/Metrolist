/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@Entity(tableName = "podcast")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val bookmarkedAt: LocalDateTime? = null,
    val lastUpdateTime: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
) {
    val inLibrary: Boolean get() = bookmarkedAt != null
}
