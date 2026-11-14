/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "cached_audio")
data class CachedAudioEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val filePath: String,
    val mimeType: String? = null,
    val fileSize: Long,
    val cachedAt: Long,
    val lastAccessedAt: Long,
    val cacheLocation: String,
    val kind: String = KIND_CACHE,
)

const val KIND_CACHE = "CACHE"
const val KIND_DOWNLOAD = "DOWNLOAD"
