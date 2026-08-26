/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "format")
data class FormatEntity(
    @PrimaryKey val id: String,
    val itag: Int,
    val mimeType: String,
    val codecs: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val contentLength: Long,
    val loudnessDb: Double?,
    val perceptualLoudnessDb: Double? = null,
    @Deprecated("playbackTrackingUrl should be retrieved from a fresh player request")
    val playbackUrl: String?
)
