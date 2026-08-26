/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Podcast library entries with YTM "Save to Library" sync support.
 *
 * Podcasts are saved using the likePlaylist API (like/like endpoint with playlistId).
 * The podcast ID format is "MPSP<playlistId>", e.g., "MPSPPLxxx..." where the
 * playlistId is extracted by removing the "MPSP" prefix.
 *
 * Note: channelId, libraryAddToken, libraryRemoveToken are legacy fields kept
 * for backwards compatibility. The correct API is likePlaylist().
 */
@Immutable
@Entity(tableName = "podcast")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val bookmarkedAt: LocalDateTime? = null,
    val lastUpdateTime: LocalDateTime = LocalDateTime.now(),
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
) {
    fun toggleBookmark() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now(),
        lastUpdateTime = LocalDateTime.now(),
    )

    val inLibrary: Boolean get() = bookmarkedAt != null
}
