/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Entity

@Entity(
    tableName = "playCount",
    primaryKeys = ["song", "year", "month"],
)
data class PlayCountEntity(
    val song: String,
    val year: Int = -1,
    val month: Int = -1,
    val count: Int = -1,
)
