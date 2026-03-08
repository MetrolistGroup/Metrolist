/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Embedded
import androidx.room3.Relation
import com.metrolist.shared.model.SongItem

data class PlaylistSong(
    @Embedded val map: PlaylistSongMap,
    @Relation(
        parentColumns = ["songId"],
        entityColumns = ["id"],
        entity = SongEntity::class,
    )
    val song: Song,
)

fun PlaylistSong.toSongItem(): SongItem =
    song.toSongItem().copy(
        playlistSetVideoId = map.setVideoId,
        playlistLocalMappingId = map.id,
        playlistAddedOrder = map.id,
    )
