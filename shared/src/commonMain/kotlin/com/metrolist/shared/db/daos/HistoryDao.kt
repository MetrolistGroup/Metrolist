package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import com.metrolist.shared.db.HistoryArtistCredit
import com.metrolist.shared.db.HistoryBucket
import com.metrolist.shared.db.HistoryCatalogNames
import com.metrolist.shared.db.HistoryOrderRow
import com.metrolist.shared.db.HistorySnapshotCredit
import com.metrolist.shared.db.HistorySongCount
import com.metrolist.shared.db.HistoryStatGroup
import com.metrolist.shared.db.HistorySyncRow
import com.metrolist.shared.db.HistoryTotals
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.EventWithSong
import com.metrolist.shared.db.entities.HistoryProvenance
import com.metrolist.shared.db.entities.HistorySnapshot
import com.metrolist.shared.db.entities.HistoryStatExclusion
import com.metrolist.shared.db.entities.RecognitionHistory
import com.metrolist.shared.db.entities.SearchHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

@Dao
interface HistoryDao {
    @Query("SELECT encodedValue FROM history_stat_exclusion WHERE kind = 'prepared' AND value = 'scope'")
    suspend fun historyExclusionScope(): String?

    @Query("DELETE FROM history_stat_exclusion")
    suspend fun clearHistoryStatExclusions()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryStatExclusions(entries: List<HistoryStatExclusion>)

    @RawQuery
    suspend fun historySyncRows(query: RoomRawQuery): List<HistorySyncRow>

    @RawQuery
    suspend fun historySongCounts(query: RoomRawQuery): List<HistorySongCount>

    @RawQuery
    suspend fun historyStatGroups(query: RoomRawQuery): List<HistoryStatGroup>

    @Query(
        "SELECT COUNT(*) AS playCount, COALESCE(SUM(CASE WHEN playTimeKnown THEN playTime ELSE 0 END), 0) AS playTimeMs FROM event WHERE songId = :songId",
    )
    fun songListeningTotals(songId: String): Flow<HistoryTotals>

    @RawQuery
    suspend fun historyStatsTotals(query: RoomRawQuery): HistoryTotals

    @RawQuery
    suspend fun historyStatBuckets(query: RoomRawQuery): List<HistoryBucket>

    @Query(
        "SELECT m.songId, a.id, a.name, a.thumbnailUrl FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId IN (:songIds) ORDER BY m.songId, m.position",
    )
    suspend fun historyArtistCredits(songIds: List<String>): List<HistoryArtistCredit>

    @Query(
        "SELECT DISTINCT e.songId, h.artists, h.artistNamesJson FROM history_snapshot h JOIN event e ON e.id = h.eventId WHERE e.timestamp BETWEEN :sinceMs AND :untilMs",
    )
    suspend fun historySnapshotCredits(
        sinceMs: Long,
        untilMs: Long,
    ): List<HistorySnapshotCredit>

    @Query(
        "SELECT m.songId, a.id, a.name, a.thumbnailUrl FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.artistId IN (:ids) AND EXISTS (SELECT 1 FROM event e WHERE e.songId = m.songId AND e.timestamp BETWEEN :sinceMs AND :untilMs)",
    )
    suspend fun historyBlockedArtistCredits(
        ids: List<String>,
        sinceMs: Long,
        untilMs: Long,
    ): List<HistoryArtistCredit>

    @Query(
        """
        SELECT DISTINCT e.songId, a.name AS artists FROM event e
        JOIN song_artist_map m ON m.songId = e.songId JOIN artist a ON a.id = m.artistId
        WHERE e.timestamp BETWEEN :sinceMs AND :untilMs
        AND NOT EXISTS (SELECT 1 FROM history_snapshot h WHERE h.eventId = e.id)
        """,
    )
    suspend fun historyCatalogNames(
        sinceMs: Long,
        untilMs: Long,
    ): List<HistoryCatalogNames>

    @Transaction
    @Query("SELECT * FROM event ORDER BY rowId DESC")
    fun events(): Flow<List<EventWithSong>>

    @Transaction
    @Query("SELECT * FROM event WHERE id IN (:ids)")
    suspend fun historyEventsById(ids: List<Long>): List<EventWithSong>

    @Query("SELECT COUNT(*) FROM event")
    fun eventCount(): Flow<Int>

    @Transaction
    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    @Transaction
    @Query("SELECT * FROM recognition_history ORDER BY recognizedAt DESC")
    fun recognitionHistory(): Flow<List<RecognitionHistory>>

    @Transaction
    @Query(
        "SELECT * FROM recognition_history WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY recognizedAt DESC",
    )
    fun searchRecognitionHistory(query: String): Flow<List<RecognitionHistory>>

    @Transaction
    @Query("DELETE FROM recognition_history")
    suspend fun clearRecognitionHistory()

    @Transaction
    @Query("DELETE FROM recognition_history WHERE id = :id")
    suspend fun deleteRecognitionHistoryById(id: Long)

    @Transaction
    @Query(
        "UPDATE recognition_history SET liked = :liked WHERE trackId = :trackId OR (youtubeVideoId IS NOT NULL AND youtubeVideoId = :trackId)",
    )
    suspend fun updateRecognitionHistoryLikedByTrack(
        trackId: String,
        liked: Boolean,
    )

    @Transaction
    @Query(
        "UPDATE recognition_history SET youtubeVideoId = :youtubeVideoId WHERE trackId = :trackId AND (youtubeVideoId IS NULL OR youtubeVideoId = '')",
    )
    suspend fun updateRecognitionHistoryVideoId(
        trackId: String,
        youtubeVideoId: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recognitionHistory: RecognitionHistory): Long

    @Delete
    suspend fun delete(recognitionHistory: RecognitionHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: Event): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: HistorySnapshot)

    @Insert
    suspend fun insert(provenance: HistoryProvenance)

    @Query("SELECT * FROM history_provenance WHERE source = :source AND legacyId = :id")
    suspend fun historyProvenance(
        source: String,
        id: Long,
    ): HistoryProvenance?

    @Query(
        "SELECT * FROM history_provenance WHERE source != :source AND legacyId = :id AND songId = :songId AND remoteFeedbackToken IS :token AND (playedAt = :playedAt OR (:token IS NOT NULL AND :token != '')) LIMIT 1",
    )
    suspend fun copiedHistoryProvenance(
        source: String,
        id: Long,
        songId: String,
        playedAt: Long,
        token: String?,
    ): HistoryProvenance?

    @Query(
        "SELECT COALESCE(h.remoteFeedbackToken, e.remoteFeedbackToken) FROM event e LEFT JOIN history_snapshot h ON h.eventId = e.id " +
            "WHERE e.id IN (:ids) AND COALESCE(h.remoteFeedbackToken, e.remoteFeedbackToken) IS NOT NULL",
    )
    suspend fun historyTokens(ids: List<Long>): List<String>

    @Query("SELECT id FROM event WHERE remoteFeedbackToken = :token LIMIT 1")
    suspend fun eventIdForToken(token: String): Long?

    @Query("SELECT eventId FROM history_snapshot WHERE remoteFeedbackToken = :token LIMIT 1")
    suspend fun snapshotEventIdForToken(token: String): Long?

    @Query("SELECT enabled FROM history_import_policy WHERE id = 1")
    suspend fun historyImportEnabled(): Boolean?

    @Query("INSERT OR REPLACE INTO history_import_policy (id, enabled) VALUES (1, 0)")
    suspend fun finishLegacyHistoryImport()

    @Query(
        "SELECT e.*, s.title AS title, s.albumName AS album, s.duration AS duration, s.thumbnailUrl AS thumbnailUrl, (SELECT GROUP_CONCAT(a.name, ', ') FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = e.songId ORDER BY m.position) AS artists, h.eventId AS snapshotEventId, h.title AS snapshotTitle, h.artists AS snapshotArtists, h.album AS snapshotAlbum, h.duration AS snapshotDuration, h.thumbnailUrl AS snapshotThumbnailUrl, h.remoteFeedbackToken AS snapshotToken, h.artistNamesJson AS snapshotArtistNamesJson, h.albumId AS snapshotAlbumId, s.albumId AS catalogAlbumId FROM event e JOIN song s ON s.id = e.songId LEFT JOIN history_snapshot h ON h.eventId = e.id ORDER BY e.timestamp DESC, e.remoteOrder DESC, e.id DESC",
    )
    suspend fun historyRows(): List<HistoryRow>

    @RawQuery
    suspend fun historyRowsPage(query: RoomRawQuery): List<HistoryRow>

    @RawQuery
    suspend fun historyOrderRows(query: RoomRawQuery): List<HistoryOrderRow>

    @RawQuery
    suspend fun historyIds(query: RoomRawQuery): List<Long>

    @Query("DELETE FROM event WHERE id IN (:ids)")
    suspend fun deleteHistoryIds(ids: List<Long>)

    @Query("SELECT songId FROM event WHERE id = :id")
    suspend fun historySongId(id: Long): String?

    @Query(
        "UPDATE history_snapshot SET title = :title, artists = :artists, artistNamesJson = :artistNamesJson, albumId = CASE WHEN album IS :album THEN albumId ELSE NULL END, album = :album WHERE eventId = :id",
    )
    suspend fun updateHistorySnapshot(
        id: Long,
        title: String,
        artists: String,
        album: String?,
        artistNamesJson: String,
    ): Int

    @Query("UPDATE event SET timestamp = :timestamp, source = :source, remoteSynced = :remoteSynced WHERE id = :id")
    suspend fun updateCopiedHistoryEvent(
        id: Long,
        timestamp: LocalDateTime,
        source: String,
        remoteSynced: Boolean,
    )

    @Query(
        "UPDATE event SET timestamp = :timestamp, remoteDateLabel = :label, remoteOrder = :remoteOrder, remoteSynced = 1, remoteAccountKey = :accountKey WHERE id = :id",
    )
    suspend fun updateHistoryTimestamp(
        id: Long,
        timestamp: LocalDateTime,
        label: String,
        remoteOrder: Int,
        accountKey: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(searchHistory: SearchHistory)

    @Delete
    suspend fun delete(searchHistory: SearchHistory)

    @Delete
    suspend fun delete(event: Event)
}
