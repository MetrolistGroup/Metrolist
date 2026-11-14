package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.metrolist.shared.db.entities.ImportTrackMatch

@Dao
interface ImportTrackMatchDao {
    @Query(
        "SELECT DISTINCT import_track_match.* FROM import_track_match JOIN playlist_song_map ON playlist_song_map.playlistId = import_track_match.playlistId AND playlist_song_map.songId = import_track_match.songId WHERE import_track_match.playlistId = :playlistId AND provider = :provider",
    )
    suspend fun importTrackMatches(
        playlistId: String,
        provider: String,
    ): List<ImportTrackMatch>

    @Upsert
    suspend fun upsert(matches: List<ImportTrackMatch>)
}
