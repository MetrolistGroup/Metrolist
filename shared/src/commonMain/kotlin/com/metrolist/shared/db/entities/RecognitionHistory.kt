/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.recognition.recognitionSongItem
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@Entity(
    tableName = "recognition_history",
    indices = [
        Index(
            value = ["trackId"],
            unique = false,
        ),
    ],
)
data class RecognitionHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val coverArtUrl: String? = null,
    val coverArtHqUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val shazamUrl: String? = null,
    val appleMusicUrl: String? = null,
    val spotifyUrl: String? = null,
    val isrc: String? = null,
    val youtubeVideoId: String? = null,
    val recognizedAt: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    val liked: Boolean = false,
)

fun RecognitionHistory.toSongItem(): SongItem? =
    recognitionSongItem(youtubeVideoId, title, artist, album, coverArtUrl, coverArtHqUrl, genre)
