package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import com.metrolist.shared.constants.PlaylistSortType
import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSong
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.extensions.reversed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

data class PlaylistPreviewThumbnail(
    val playlistId: String,
    val thumbnailUrl: String,
)

data class PlaylistDownloadMember(
    val songId: String,
    val isLocal: Boolean,
)

data class PlaylistLibraryStats(
    val playlistId: String,
    val songCount: Int,
    val playtime: Long?,
)

private const val SQLITE_MAX_BIND_PARAMETERS = 999

private const val PLAYLIST_SONG_COUNT =
    "CASE WHEN isLocal OR browseId IS NULL " +
        "THEN (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) " +
        "ELSE COALESCE(NULLIF(remoteSongCount, 0), " +
        "NULLIF((SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id), 0), remoteSongCount, 0) END"

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id, p.browseId, p.name, p.thumbnailUrl, p.customThumbnailUrl, p.isLocal FROM playlist p
        WHERE p.bookmarkedAt IS NOT NULL AND (
            p.rowid IN (
                SELECT MIN(rowid) FROM playlist WHERE bookmarkedAt IS NOT NULL
                GROUP BY CASE WHEN browseId IS NOT NULL AND NOT isLocal THEN 'online:' || browseId ELSE 'local:' || id END
                ORDER BY (browseId IS NOT NULL AND NOT isLocal), MIN(rowid) LIMIT 8
            ) OR p.id IN (
                SELECT id FROM speed_dial WHERE type IN ('PLAYLIST', 'LOCAL_PLAYLIST') ORDER BY `index`, createDate, id LIMIT 8
            ) OR p.browseId IN (
                SELECT id FROM speed_dial WHERE type IN ('PLAYLIST', 'LOCAL_PLAYLIST') ORDER BY `index`, createDate, id LIMIT 8
            )
        ) ORDER BY p.rowid
        """,
    )
    fun widgetPlaylists(): Flow<List<WidgetPlaylist>>

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position")
    fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>>

    @Query(
        "SELECT playlist_song_map.songId, song.isLocal FROM playlist_song_map " +
            "JOIN song ON song.id = playlist_song_map.songId WHERE playlistId = :playlistId ORDER BY position",
    )
    fun playlistDownloadMembers(playlistId: String): Flow<List<PlaylistDownloadMember>>

    @Transaction
    @Query(
        "SELECT *, $PLAYLIST_SONG_COUNT AS songCount FROM playlist WHERE bookmarkedAt IS NOT NULL ORDER BY rowId",
    )
    fun playlistsByCreateDateAsc(): Flow<List<Playlist>>

    @Transaction
    @Query(
        "SELECT *, $PLAYLIST_SONG_COUNT AS songCount FROM playlist WHERE bookmarkedAt IS NOT NULL ORDER BY lastUpdateTime",
    )
    fun playlistsByUpdatedDateAsc(): Flow<List<Playlist>>

    @Transaction
    @Query(
        "SELECT *, $PLAYLIST_SONG_COUNT AS songCount FROM playlist WHERE bookmarkedAt IS NOT NULL ORDER BY name",
    )
    fun playlistsByNameAsc(): Flow<List<Playlist>>

    @Transaction
    @Query(
        "SELECT *, $PLAYLIST_SONG_COUNT AS songCount FROM playlist WHERE bookmarkedAt IS NOT NULL ORDER BY songCount",
    )
    fun playlistsBySongCountAsc(): Flow<List<Playlist>>

    fun playlists(
        sortType: PlaylistSortType,
        descending: Boolean,
    ): Flow<List<Playlist>> =
        when (sortType) {
            PlaylistSortType.CREATE_DATE -> playlistsByCreateDateAsc()
            PlaylistSortType.NAME -> playlistsByNameAsc()
            PlaylistSortType.SONG_COUNT -> playlistsBySongCountAsc()
            PlaylistSortType.LAST_UPDATED -> playlistsByUpdatedDateAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query(
        "SELECT *, $PLAYLIST_SONG_COUNT AS songCount FROM playlist WHERE id = :playlistId",
    )
    fun playlist(playlistId: String): Flow<Playlist?>

    @Transaction
    @Query(
        "SELECT *, $PLAYLIST_SONG_COUNT AS songCount FROM playlist WHERE isEditable AND bookmarkedAt IS NOT NULL ORDER BY rowId",
    )
    fun editablePlaylistsByCreateDateAsc(): Flow<List<Playlist>>

    @Transaction
    @Query(
        "SELECT *, $PLAYLIST_SONG_COUNT AS songCount FROM playlist WHERE browseId = :browseId",
    )
    fun playlistByBrowseId(browseId: String): Flow<Playlist?>

    @Transaction
    @Query("SELECT COUNT(*) from playlist_song_map WHERE playlistId = :playlistId AND songId = :songId LIMIT 1")
    suspend fun checkInPlaylist(
        playlistId: String,
        songId: String,
    ): Int

    @Query("UPDATE playlist SET lastUpdateTime = :now WHERE id = :playlistId")
    suspend fun updatePlaylistLastUpdated(
        playlistId: String,
        now: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
    )

    @Transaction
    suspend fun addSongToPlaylist(
        playlist: Playlist,
        songIds: List<String>,
    ) {
        var position = localPlaylistSongCount(playlist.id)
        songIds.forEach { id ->
            insert(
                PlaylistSongMap(
                    songId = id,
                    playlistId = playlist.id,
                    position = position++,
                ),
            )
        }
        updatePlaylistLastUpdated(playlist.id)
    }

    @Query("SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = :playlistId")
    suspend fun localPlaylistSongCount(playlistId: String): Int

    @Query(
        """
        SELECT playlist.id AS playlistId,
               COUNT(playlist_song_map.position) AS songCount,
               CASE WHEN COUNT(playlist_song_map.position) = 0 THEN NULL
                    ELSE SUM(CASE WHEN song.totalPlayTime > 0 THEN song.totalPlayTime ELSE 0 END)
               END AS playtime
        FROM playlist
        LEFT JOIN playlist_song_map ON playlist_song_map.playlistId = playlist.id
        LEFT JOIN song ON song.id = playlist_song_map.songId
        WHERE playlist.id IN (:playlistIds)
        GROUP BY playlist.id
        """,
    )
    suspend fun playlistLibraryStatsChunk(playlistIds: List<String>): List<PlaylistLibraryStats>

    @Transaction
    suspend fun playlistLibraryStats(playlistIds: List<String>): List<PlaylistLibraryStats> =
        playlistIds.distinct().chunked(SQLITE_MAX_BIND_PARAMETERS).flatMap { playlistLibraryStatsChunk(it) }

    @Transaction
    @Query(
        """
        UPDATE playlist_song_map SET position =
            CASE
                WHEN position < :fromPosition THEN position + 1
                WHEN position > :fromPosition THEN position - 1
                ELSE :toPosition
            END
        WHERE playlistId = :playlistId AND position BETWEEN MIN(:fromPosition, :toPosition) AND MAX(:fromPosition, :toPosition)
    """,
    )
    suspend fun move(
        playlistId: String,
        fromPosition: Int,
        toPosition: Int,
    )

    @Transaction
    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: String)

    @Transaction
    @Query("UPDATE playlist_song_map SET position = position - 1 WHERE playlistId = :playlistId AND position > :fromPosition")
    suspend fun decrementPositionsAfter(
        playlistId: String,
        fromPosition: Int,
    )

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE songId = :songId")
    suspend fun playlistSongMaps(songId: String): List<PlaylistSongMap>

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId AND position >= :from ORDER BY position")
    suspend fun playlistSongMaps(
        playlistId: String,
        from: Int,
    ): List<PlaylistSongMap>

    @Query(
        "DELETE FROM playlist WHERE id = :id OR browseId = :id OR browseId = :normalizedId OR browseId = 'VL' || :normalizedId",
    )
    suspend fun deletePlaylistById(
        id: String,
        normalizedId: String = id.removePrefix("VL"),
    )

    @Query(
        "SELECT * FROM playlist WHERE id = :id OR browseId = :id OR browseId = :normalizedId OR browseId = 'VL' || :normalizedId LIMIT 1",
    )
    suspend fun findPlaylistEntity(
        id: String,
        normalizedId: String = id.removePrefix("VL"),
    ): PlaylistEntity?

    @Query(
        "SELECT * FROM playlist WHERE NOT isLocal AND (id = :id OR browseId = :id OR browseId = :normalizedId OR browseId = 'VL' || :normalizedId)",
    )
    suspend fun findRemotePlaylistEntities(
        id: String,
        normalizedId: String = id.removePrefix("VL"),
    ): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: PlaylistEntity)

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(map: PlaylistSongMap)

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlistSongMap: PlaylistSongMap)

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId AND id IN (:mappingIds)")
    suspend fun deletePlaylistMappingsChunk(
        playlistId: String,
        mappingIds: List<Int>,
    )

    suspend fun deletePlaylistMappings(
        playlistId: String,
        mappingIds: List<Int>,
    ) {
        mappingIds.chunked(SQLITE_MAX_BIND_PARAMETERS - 1).forEach { deletePlaylistMappingsChunk(playlistId, it) }
    }

    @Query("UPDATE playlist_song_map SET position = :position WHERE id = :mappingId")
    suspend fun updatePlaylistMappingPosition(
        mappingId: Int,
        position: Int,
    )

    @Query("SELECT * FROM playlist WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findPlaylistByName(name: String): PlaylistEntity?

    @Query("SELECT * FROM playlist ORDER BY name")
    fun allPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_song_map WHERE playlistId = :playlistId")
    suspend fun nextPlaylistPosition(playlistId: String): Int

    @Query(
        "SELECT song.thumbnailUrl FROM playlist_song_map " +
            "JOIN song ON song.id = playlist_song_map.songId " +
            "WHERE playlist_song_map.playlistId = :playlistId AND song.thumbnailUrl IS NOT NULL AND song.thumbnailUrl != '' " +
            "GROUP BY song.thumbnailUrl ORDER BY MIN(playlist_song_map.position) LIMIT 4",
    )
    suspend fun playlistPreviewThumbnails(playlistId: String): List<String>

    @RawQuery
    suspend fun playlistPreviewThumbnails(query: RoomRawQuery): List<PlaylistPreviewThumbnail>

    suspend fun playlistPreviewThumbnails(playlistIds: List<String>): List<PlaylistPreviewThumbnail> =
        playlistIds.chunked(250).flatMap { batch ->
            // UNION keeps the per-playlist LIMIT on Android's older SQLite versions, without window functions.
            val sql =
                batch.joinToString(" UNION ALL ") {
                    "SELECT ? AS playlistId, thumbnailUrl FROM (" +
                        "SELECT song.thumbnailUrl FROM playlist_song_map JOIN song ON song.id = playlist_song_map.songId " +
                        "WHERE playlist_song_map.playlistId = ? AND song.thumbnailUrl IS NOT NULL AND song.thumbnailUrl != '' " +
                        "GROUP BY song.thumbnailUrl ORDER BY MIN(playlist_song_map.position) LIMIT 4)"
                }
            playlistPreviewThumbnails(
                RoomRawQuery(sql) { statement ->
                    batch.forEachIndexed { index, id ->
                        statement.bindText(index * 2 + 1, id)
                        statement.bindText(index * 2 + 2, id)
                    }
                },
            )
        }
}

data class WidgetPlaylist(
    val id: String,
    val browseId: String?,
    val name: String,
    val thumbnailUrl: String?,
    val customThumbnailUrl: String?,
    val isLocal: Boolean,
)
