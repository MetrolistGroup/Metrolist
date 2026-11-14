package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.FormatEntity
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SetVideoIdEntity
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class CachedAudioPath(
    val songId: String,
    val filePath: String,
)

@Dao
interface CacheDao {
    @Transaction
    @Query("SELECT * FROM format WHERE id = :id")
    fun format(id: String?): Flow<FormatEntity?>

    @Transaction
    @Query("SELECT * FROM lyrics WHERE id = :id")
    fun lyrics(id: String?): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics WHERE id = :id")
    suspend fun cachedLyrics(id: String): LyricsEntity?

    @Query("SELECT id FROM lyrics WHERE id IN (:ids)")
    suspend fun cachedLyricsIds(ids: List<String>): List<String>

    @Transaction
    suspend fun cacheLyricsIfUnchanged(
        lyrics: LyricsEntity,
        expected: LyricsEntity?,
    ): LyricsEntity? {
        val current = cachedLyrics(lyrics.id)
        // An offset-only row may appear while the first lyrics fetch is in flight.
        val previous = expected ?: LyricsEntity(id = lyrics.id, lyrics = "")
        if (current != expected &&
            (
                current?.lyrics != previous.lyrics || current.provider != previous.provider ||
                    current.isUserSelected != previous.isUserSelected ||
                    current.missLookupKey != previous.missLookupKey || current.missExpiresAt != previous.missExpiresAt
            )
        ) {
            return current
        }
        val updated =
            lyrics.copy(
                offsetMs = current?.offsetMs ?: lyrics.offsetMs,
                userLyrics = current?.userLyrics,
                userLyricsDraft = current?.userLyricsDraft,
                stashedUserLyrics = current?.stashedUserLyrics,
                stashedUserLyricsDraft = current?.stashedUserLyricsDraft,
            )
        upsert(updated)
        return updated
    }

    @Query("SELECT offsetMs FROM lyrics WHERE id = :id")
    suspend fun lyricsOffset(id: String): Int?

    @Query("SELECT lyricsOffset FROM song WHERE id = :id")
    suspend fun legacyLyricsOffset(id: String): Int?

    @Query("UPDATE lyrics SET offsetMs = :offsetMs WHERE id = :id")
    suspend fun updateLyricsOffset(
        id: String,
        offsetMs: Int,
    ): Int

    @Transaction
    suspend fun saveLyrics(lyrics: LyricsEntity) {
        val offsetMs = lyricsOffset(lyrics.id) ?: legacyLyricsOffset(lyrics.id) ?: lyrics.offsetMs
        val current = cachedLyrics(lyrics.id)
        upsert(
            lyrics.copy(
                offsetMs = offsetMs,
                userLyrics = current?.userLyrics,
                userLyricsDraft = current?.userLyricsDraft,
                stashedUserLyrics = current?.stashedUserLyrics,
                stashedUserLyricsDraft = current?.stashedUserLyricsDraft,
            ),
        )
    }

    /** Sets the active User fork aside so an explicit provider choice can show, keeping it restorable. */
    @Transaction
    suspend fun stashUserLyrics(id: String) {
        val current = cachedLyrics(id) ?: return
        val lyrics = current.userLyrics ?: return
        upsert(
            current.copy(
                userLyrics = null,
                userLyricsDraft = null,
                stashedUserLyrics = lyrics,
                stashedUserLyricsDraft = current.userLyricsDraft,
            ),
        )
    }

    @Transaction
    suspend fun restoreStashedUserLyrics(id: String) {
        val current = cachedLyrics(id) ?: return
        val lyrics = current.stashedUserLyrics ?: return
        upsert(
            current.copy(
                userLyrics = lyrics,
                userLyricsDraft = current.stashedUserLyricsDraft,
                stashedUserLyrics = null,
                stashedUserLyricsDraft = null,
            ),
        )
    }

    @Transaction
    suspend fun setLyricsOffset(
        id: String,
        offsetMs: Int,
    ) {
        if (updateLyricsOffset(id, offsetMs) == 0) {
            upsert(LyricsEntity(id = id, lyrics = "", offsetMs = offsetMs))
        }
    }

    @Transaction
    suspend fun saveUserLyrics(
        id: String,
        lyrics: String?,
        draft: String?,
    ) {
        val current = cachedLyrics(id) ?: LyricsEntity(id = id, lyrics = "", offsetMs = legacyLyricsOffset(id) ?: 0)
        upsert(current.copy(userLyrics = lyrics, userLyricsDraft = draft))
    }

    @Query(
        """
        SELECT 'playlist:' || COALESCE(p.browseId, p.id) FROM playlist p
        JOIN playlist_song_map m ON m.playlistId = p.id
        JOIN song s ON s.id = m.songId
        LEFT JOIN cached_audio c ON c.songId = m.songId AND c.kind = 'DOWNLOAD' AND c.fileSize > 0
        GROUP BY p.id
        HAVING COUNT(*) = SUM(s.isUnavailable OR c.songId IS NOT NULL) AND SUM(NOT s.isUnavailable) > 0
          AND (p.isLocal = 1 OR p.browseId IS NULL OR (p.remoteSongCount > 0 AND COUNT(*) >= p.remoteSongCount))
        UNION
        SELECT 'album:' || a.id FROM album a
        JOIN (
            SELECT albumId, songId FROM song_album_map
            UNION
            SELECT s.albumId, s.id AS songId FROM song s
            JOIN album a ON a.id = s.albumId
            WHERE (SELECT COUNT(*) FROM song_album_map WHERE albumId = a.id) < a.songCount
        ) tracks ON tracks.albumId = a.id
        JOIN song s ON s.id = tracks.songId
        LEFT JOIN cached_audio c ON c.songId = s.id AND c.kind = 'DOWNLOAD' AND c.fileSize > 0
        GROUP BY a.id
        HAVING a.songCount > 0 AND COUNT(*) >= a.songCount
            AND COUNT(*) = SUM(s.isUnavailable OR c.songId IS NOT NULL) AND SUM(NOT s.isUnavailable) > 0
        """,
    )
    fun downloadedCollectionKeys(): Flow<List<String>>

    @RawQuery(
        observedEntities = [PlaylistEntity::class, PlaylistSongMap::class, AlbumEntity::class, SongAlbumMap::class, SongEntity::class],
    )
    fun downloadedCollectionKeys(query: RoomRawQuery): Flow<List<String>>

    fun downloadedCollectionKeys(songIds: List<String>): Flow<List<String>> {
        if (songIds.isEmpty()) return flowOf(emptyList())
        // Hex literals keep external IDs out of SQL syntax without SQLite's bound-parameter limit.
        val available = songIds.joinToString(",") { "CAST(X'${it.encodeToByteArray().toHexString()}' AS TEXT)" }
        return downloadedCollectionKeys(
            RoomRawQuery(
                """
                SELECT 'playlist:' || COALESCE(p.browseId, p.id) FROM playlist p
                JOIN playlist_song_map m ON m.playlistId = p.id
                JOIN song s ON s.id = m.songId
                GROUP BY p.id
                HAVING COUNT(*) = SUM(s.isUnavailable OR m.songId IN ($available)) AND SUM(NOT s.isUnavailable) > 0
                  AND (p.isLocal = 1 OR p.browseId IS NULL OR (p.remoteSongCount > 0 AND COUNT(*) >= p.remoteSongCount))
                UNION
                SELECT 'album:' || a.id FROM album a
                JOIN (
                    SELECT albumId, songId FROM song_album_map
                    UNION
                    SELECT s.albumId, s.id AS songId FROM song s
                    JOIN album a ON a.id = s.albumId
                    WHERE (SELECT COUNT(*) FROM song_album_map WHERE albumId = a.id) < a.songCount
                ) tracks ON tracks.albumId = a.id
                JOIN song s ON s.id = tracks.songId
                GROUP BY a.id
                HAVING a.songCount > 0 AND COUNT(*) >= a.songCount AND COUNT(*) = SUM(s.isUnavailable OR s.id IN ($available)) AND SUM(NOT s.isUnavailable) > 0
                """.trimIndent(),
            ),
        )
    }

    @Query(
        """
        SELECT EXISTS(
            SELECT p.id FROM playlist p JOIN playlist_song_map m ON m.playlistId = p.id
            JOIN song s ON s.id = m.songId
            LEFT JOIN cached_audio c ON c.songId = m.songId AND c.kind = 'DOWNLOAD' AND c.fileSize > 0
            WHERE p.id = :id OR p.browseId = :id OR p.browseId = 'VL' || :id
            GROUP BY p.id
            HAVING COUNT(*) = SUM(s.isUnavailable OR c.songId IS NOT NULL) AND SUM(NOT s.isUnavailable) > 0
            AND (p.isLocal = 1 OR p.browseId IS NULL OR (p.remoteSongCount > 0 AND COUNT(*) >= p.remoteSongCount))
        )
        """,
    )
    fun isPlaylistDownloaded(id: String): Flow<Boolean>

    @Query(
        """
        SELECT EXISTS(
            SELECT a.id FROM album a
            JOIN (
                SELECT albumId, songId FROM song_album_map
                UNION
                SELECT s.albumId, s.id AS songId FROM song s
                JOIN album a ON a.id = s.albumId
                WHERE (SELECT COUNT(*) FROM song_album_map WHERE albumId = a.id) < a.songCount
            ) tracks ON tracks.albumId = a.id
            JOIN song s ON s.id = tracks.songId
            LEFT JOIN cached_audio c ON c.songId = s.id AND c.kind = 'DOWNLOAD' AND c.fileSize > 0
            WHERE a.id = :id GROUP BY a.id
            HAVING a.songCount > 0 AND COUNT(*) >= a.songCount
            AND COUNT(*) = SUM(s.isUnavailable OR c.songId IS NOT NULL) AND SUM(NOT s.isUnavailable) > 0
        )
        """,
    )
    fun isAlbumDownloaded(id: String): Flow<Boolean>

    @Query(
        """
        SELECT DISTINCT c.songId FROM playlist p JOIN playlist_song_map m ON m.playlistId = p.id
        JOIN cached_audio c ON c.songId = m.songId AND c.kind = 'DOWNLOAD' AND c.fileSize > 0
        WHERE p.id = :id OR p.browseId = :id OR p.browseId = 'VL' || :id
        """,
    )
    suspend fun playlistDownloadIds(id: String): List<String>

    @Query(
        """
        SELECT c.songId FROM song s
        JOIN cached_audio c ON c.songId = s.id AND c.kind = 'DOWNLOAD' AND c.fileSize > 0
        WHERE s.id IN (
            SELECT songId FROM song_album_map WHERE albumId = :id
            UNION
            SELECT id FROM song WHERE albumId = :id
        )
        """,
    )
    suspend fun albumDownloadIds(id: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM cached_audio WHERE songId = :songId AND kind = 'DOWNLOAD' AND fileSize > 0)")
    fun isSongDownloaded(songId: String): Flow<Boolean>

    @Transaction
    @Query("SELECT * FROM cached_audio WHERE songId = :songId")
    suspend fun cachedAudio(songId: String): CachedAudioEntity?

    @Transaction
    @Query("SELECT * FROM cached_audio WHERE kind = :kind ORDER BY cachedAt DESC")
    fun cachedAudioByKind(kind: String): Flow<List<CachedAudioEntity>>

    @Query("SELECT songId, filePath FROM cached_audio WHERE kind = :kind AND songId IN (:songIds)")
    suspend fun cachedAudioCandidates(
        songIds: List<String>,
        kind: String,
    ): List<CachedAudioPath>

    @Query("UPDATE cached_audio SET lastAccessedAt = :timestamp WHERE songId = :songId AND lastAccessedAt < :before")
    suspend fun touchCachedAudio(
        songId: String,
        timestamp: Long,
        before: Long,
    )

    @Query("SELECT songId FROM cached_audio WHERE kind = :kind ORDER BY songId")
    fun cachedAudioIdsByKind(kind: String): Flow<List<String>>

    @Query("SELECT songId FROM cached_audio")
    fun cachedAudioIds(): Flow<List<String>>

    @Transaction
    @Query("SELECT * FROM cached_audio ORDER BY cachedAt DESC")
    fun cachedAudio(): Flow<List<CachedAudioEntity>>

    @Transaction
    @Query("SELECT * FROM cached_audio WHERE kind = :kind ORDER BY lastAccessedAt ASC")
    suspend fun cachedAudioByLastAccessed(kind: String): List<CachedAudioEntity>

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM cached_audio WHERE kind = :kind")
    suspend fun cachedAudioSize(kind: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setVideoIdEntity: SetVideoIdEntity)

    @Delete
    suspend fun delete(lyrics: LyricsEntity)

    @Upsert
    suspend fun upsert(lyrics: LyricsEntity)

    @Upsert
    suspend fun upsert(format: FormatEntity)

    @Upsert
    suspend fun upsert(cachedAudio: CachedAudioEntity)

    @Query("UPDATE cached_audio SET lastAccessedAt = :timestamp WHERE songId = :songId")
    suspend fun updateCachedAudioLastAccessed(
        songId: String,
        timestamp: Long,
    )

    @Query("DELETE FROM cached_audio WHERE songId = :songId")
    suspend fun deleteCachedAudio(songId: String)

    @Query("DELETE FROM format WHERE id = :id")
    suspend fun deleteFormat(id: String)

    @Query("DELETE FROM cached_audio WHERE kind = :kind")
    suspend fun clearCachedAudio(kind: String)
}
