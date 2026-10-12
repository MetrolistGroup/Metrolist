/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.db.entities

import androidx.room.Embedded
import androidx.room.Relation

data class PlaylistSong(
    @Embedded val map: PlaylistSongMap,
    @Relation(
        parentColumn = "songId",
        entityColumn = "id",
        entity = SongEntity::class,
    )
    val song: Song,
)
