/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity

@Immutable
@Entity(
    tableName = "playCount",
    primaryKeys = ["song", "year", "month"]
)
class PlayCountEntity(
    val song: String, // song id
    val year: Int = -1,
    val month: Int = -1,
    val count: Int = -1,
)
