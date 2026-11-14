package com.metrolist.shared.db.entities

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "import_track_match",
    primaryKeys = ["playlistId", "provider", "trackKey"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("songId")],
)
data class ImportTrackMatch(
    val playlistId: String,
    val provider: String,
    val trackKey: String,
    val songId: String,
)
