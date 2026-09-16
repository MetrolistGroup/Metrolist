/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@Immutable
@Entity(tableName = "artist")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val channelId: String? = null,
    val lastUpdateTime: LocalDateTime = LocalDateTime.now(),
    val bookmarkedAt: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = false.toString())
    val isLocal: Boolean = false,
    @ColumnInfo(name = "isPodcastChannel", defaultValue = false.toString())
    val isPodcastChannel: Boolean = false,
    // The artist page as YouTube returned it, so the screen has something to draw before the
    // network answers. Read only by ArtistViewModel, for the single artist on screen. Relations
    // that pull artists in bulk project the other columns explicitly, because Room builds one
    // ArtistEntity per pairing and would otherwise hold one copy of this page per song.
    @ColumnInfo(name = "cachedPageJson")
    val cachedPageJson: String? = null
) {
    val isYouTubeArtist: Boolean
        get() = id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist")

    val isPrivatelyOwnedArtist: Boolean
        get() = id.startsWith("FEmusic_library_privately_owned_artist")

    fun localToggleLike() = copy(
        bookmarkedAt = if (bookmarkedAt != null) null else LocalDateTime.now(),
    )

    fun toggleLike() = localToggleLike().also {
        CoroutineScope(Dispatchers.IO).launch {
            val targetChannelId = channelId ?: YouTube.getChannelId(id)
            if (targetChannelId.isNotEmpty()) {
                YouTube.subscribeChannel(targetChannelId, bookmarkedAt == null)
            }
        }
    }

    companion object {
        fun generateArtistId() = generateLocalId("LA")
    }
}
