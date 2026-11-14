package com.metrolist.android.data

import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteStatement
import com.metrolist.shared.db.HISTORY_SNAPSHOT_ARTIST_MATCH_SQL
import com.metrolist.shared.db.HistoryCursor
import com.metrolist.shared.db.HistoryStatGroup
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.RemoteHistoryDate
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.deleteHistoryEntries
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.historyAlbumStatistics
import com.metrolist.shared.db.historyArtistStatistics
import com.metrolist.shared.db.historySongStatistics
import com.metrolist.shared.db.historyStatisticsSnapshot
import com.metrolist.shared.db.historyStatisticsTotals
import com.metrolist.shared.db.historyTimeBuckets
import com.metrolist.shared.db.parseHistoryDate
import com.metrolist.shared.db.recentVisibleHistory
import com.metrolist.shared.db.statisticsSongs
import com.metrolist.shared.db.syncYouTubeHistory
import com.metrolist.shared.db.updateHistoryEntry
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.visibleHistoryIds
import com.metrolist.shared.db.visibleHistoryPage
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale
import kotlin.time.Instant

private const val HISTORY_LOG_TAG = "PlayHistory"

data class HistoryEntry(
    val id: Long,
    val songId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val duration: Long?,
    val playTimeMs: Long?,
    val thumbnailUrl: String?,
    val playedAt: Long,
    val source: String,
    val remoteFeedbackToken: String?,
    val remoteDateLabel: String? = null,
    val remoteOrder: Int = 0,
)

data class HistorySongStat(
    val songId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val thumbnailUrl: String?,
    val playCount: Int,
    val totalPlayTimeMs: Long,
    val lastPlayedAt: Long,
)

data class HistoryArtistStat(
    val name: String,
    val playCount: Int,
    val totalPlayTimeMs: Long,
    val thumbnailUrl: String?,
)

data class HistoryAlbumStat(
    val name: String,
    val artists: String,
    val thumbnailUrl: String?,
    val playCount: Int,
    val totalPlayTimeMs: Long,
)

data class AccountOverview(
    val totalSongs: Int,
    val totalPlaylists: Int,
    val totalPlayTimeMs: Long,
)

data class SongListeningStats(
    val totalListening: Int,
    val totalTimeMs: Long,
)

class PlayHistoryRepository(
    private val database: MusicDatabase,
    private val dao: DatabaseDao,
) {
    private data class SqlFilter(
        val sql: String,
        val args: List<Any?> = emptyList(),
    )

    private fun visibleHistoryFilter(songColumn: String = "e.songId"): SqlFilter {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        val songIds = BlockedContent.songIds().toList()
        if (songIds.isNotEmpty()) {
            clauses += "$songColumn NOT IN (${placeholders(songIds.size)})"
            args.addAll(songIds)
        }

        BlockedContent.artists().map { it.name.trim() }.filter(String::isNotBlank).forEach { name ->
            clauses +=
                "NOT EXISTS (SELECT 1 FROM history_snapshot blocked_snapshot WHERE blocked_snapshot.eventId = e.id AND $HISTORY_SNAPSHOT_ARTIST_MATCH_SQL)"
            args += Json.encodeToString(name)
            args += name
        }

        val artistIds = BlockedContent.artistIds().toList()
        if (artistIds.isNotEmpty()) {
            val artistPlaceholders = placeholders(artistIds.size)
            clauses +=
                "NOT EXISTS (SELECT 1 FROM song_artist_map blocked_sam " +
                "WHERE blocked_sam.songId = $songColumn AND blocked_sam.artistId IN ($artistPlaceholders) " +
                "AND NOT EXISTS (SELECT 1 FROM history_snapshot WHERE eventId = e.id))"
            args.addAll(artistIds)
        }

        return SqlFilter(
            sql = clauses.takeIf { it.isNotEmpty() }?.joinToString(prefix = " AND ", separator = " AND ").orEmpty(),
            args = args,
        )
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")

    suspend fun getRecentHistory(
        limit: Int = 500,
        before: HistoryCursor? = null,
    ): List<HistoryEntry> =
        withContext(Dispatchers.Default) {
            database.recentVisibleHistory(limit, before = before, catalogArtistIds = true).map {
                HistoryEntry(
                    it.id,
                    it.songId,
                    it.title,
                    it.artists,
                    it.album,
                    it.duration,
                    it.playTimeMs,
                    it.thumbnailUrl,
                    it.playedAt,
                    it.source,
                    it.remoteFeedbackToken,
                    remoteDateLabel = it.remoteDateLabel,
                    remoteOrder = it.remoteOrder,
                )
            }
        }

    suspend fun getHistoryPage(
        limit: Int,
        offset: Int,
        query: String,
        sortMode: HistorySortMode,
        direction: SortDirection,
    ): List<HistoryEntry> =
        database.visibleHistoryPage(limit, offset, query, sortMode, direction, catalogArtistIds = true).map {
            HistoryEntry(
                it.id,
                it.songId,
                it.title,
                it.artists,
                it.album,
                it.duration,
                it.playTimeMs,
                it.thumbnailUrl,
                it.playedAt,
                it.source,
                it.remoteFeedbackToken,
                remoteDateLabel = it.remoteDateLabel,
                remoteOrder = it.remoteOrder,
            )
        }

    suspend fun getHistoryIds(query: String): Set<Long> = database.visibleHistoryIds(query, catalogArtistIds = true)

    suspend fun getStatisticsSongs(groups: List<HistoryStatGroup>): List<SongItem> = database.statisticsSongs(groups)

    suspend fun getStatisticsSnapshot(
        sinceMs: Long,
        untilMs: Long,
    ) = database.historyStatisticsSnapshot(sinceMs, untilMs, catalogArtistIds = true)

    suspend fun getHistoryTotals(
        sinceMs: Long,
        untilMs: Long,
    ) = database.historyStatisticsTotals(sinceMs, untilMs, catalogArtistIds = true)

    suspend fun getMostPlayed(
        limit: Int = 20,
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
    ): List<HistorySongStat> =
        withContext(Dispatchers.IO) {
            database.historySongStatistics(sinceMs, untilMs, catalogArtistIds = true).take(limit).map {
                HistorySongStat(it.songId, it.title, it.artists, it.album, it.thumbnailUrl, it.playCount, it.playTimeMs, it.lastPlayedAt)
            }
        }

    suspend fun getArtistStats(
        limit: Int = 20,
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
    ): List<HistoryArtistStat> =
        withContext(Dispatchers.IO) {
            database.historyArtistStatistics(sinceMs, untilMs, catalogArtistIds = true).take(limit).map {
                HistoryArtistStat(it.name, it.playCount, it.playTimeMs, it.thumbnailUrl)
            }
        }

    suspend fun getAlbumStats(
        limit: Int = 20,
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
    ): List<HistoryAlbumStat> =
        withContext(Dispatchers.IO) {
            database.historyAlbumStatistics(sinceMs, untilMs, catalogArtistIds = true).take(limit).map {
                HistoryAlbumStat(it.name, it.artists, it.thumbnailUrl, it.playCount, it.playTimeMs)
            }
        }

    suspend fun getAccountOverview(): AccountOverview =
        withContext(Dispatchers.IO) {
            val filter = visibleHistoryFilter()
            AccountOverview(
                totalSongs = queryInt("SELECT COUNT(*) FROM song WHERE inLibrary IS NOT NULL"),
                totalPlaylists = queryInt("SELECT COUNT(*) FROM playlist WHERE bookmarkedAt IS NOT NULL OR isLocal = 1"),
                totalPlayTimeMs =
                    queryLong(
                        "SELECT COALESCE(SUM(CASE WHEN e.playTimeKnown = 1 THEN e.playTime ELSE 0 END), 0) FROM event e WHERE 1 = 1 ${filter.sql}",
                        filter.args.toTypedArray(),
                    )
                        ?: 0L,
            )
        }

    suspend fun getRecentHistorySince(
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
        limit: Int = 2000,
    ): List<HistoryEntry> =
        withContext(Dispatchers.IO) {
            val filter = visibleHistoryFilter()
            query(
                """SELECT e.id, e.songId, s.title, s.albumName,
               COALESCE(s.thumbnailUrl, '') as thumbnail_url,
               COALESCE(s.duration, -1) as duration,
               e.playTime, e.timestamp as played_at_ms,
               e.source, e.remoteFeedbackToken, e.playTimeKnown,
               h.eventId, h.title, h.artists, h.album, h.duration, h.thumbnailUrl, h.remoteFeedbackToken,
               (SELECT GROUP_CONCAT(name, ', ') FROM (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = e.songId ORDER BY m.position)) AS catalogArtists, e.remoteDateLabel, e.remoteOrder
               FROM event e LEFT JOIN song s ON e.songId = s.id LEFT JOIN history_snapshot h ON h.eventId = e.id
               WHERE e.timestamp >= ? AND e.timestamp <= ? ${filter.sql}
               ORDER BY e.timestamp DESC, e.remoteOrder DESC, e.id DESC LIMIT ?""",
                (listOf<Any?>(sinceMs, untilMs) + filter.args + limit).toTypedArray(),
            ) { c -> buildList { while (c.step()) add(cursorToEntry(c)) } }
        }

    suspend fun getScrobblesPerDay(
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
    ): Map<String, Int> =
        database.historyTimeBuckets(sinceMs, untilMs, hourly = false, localTime = true, catalogArtistIds = true).associate {
            it.bucket to
                it.playCount
        }

    suspend fun getScrobblesPerHour(
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
    ): Map<Int, Int> =
        database.historyTimeBuckets(sinceMs, untilMs, hourly = true, localTime = true, catalogArtistIds = true).associate {
            it.bucket.toInt() to
                it.playCount
        }

    suspend fun recordLocalPlay(
        song: SongItem,
        playTimeMs: Long,
    ) = withContext(Dispatchers.IO) {
        if (song.id.isBlank()) return@withContext
        database.withDatabaseTransaction {
            dao.upsertHistorySong(song)
            dao.incrementTotalPlayTime(song.id, playTimeMs)
            dao.insert(
                Event(
                    songId = song.id,
                    timestamp = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toLocalDateTime(TimeZone.UTC),
                    playTime = playTimeMs,
                    source = "LOCAL",
                ),
            )
        }
        Timber.tag(HISTORY_LOG_TAG).d(
            "local play recorded songId=%s playTimeMs=%d artists=%d",
            song.id,
            playTimeMs,
            song.artists.size,
        )
    }

    suspend fun getSongListeningStats(songId: String): SongListeningStats =
        withContext(Dispatchers.IO) {
            SongListeningStats(
                totalListening = queryInt("SELECT COUNT(*) FROM event WHERE songId = ?", arrayOf<Any?>(songId)),
                totalTimeMs =
                    queryLong(
                        "SELECT COALESCE(SUM(CASE WHEN playTimeKnown = 1 THEN playTime ELSE 0 END), 0) FROM event WHERE songId = ?",
                        arrayOf<Any?>(songId),
                    ) ?: 0L,
            )
        }

    suspend fun deleteHistoryEntry(
        id: Long,
        deleteRemote: Boolean = false,
    ) = deleteHistoryEntries(setOf(id), deleteRemote)

    suspend fun deleteHistoryEntries(
        ids: Set<Long>,
        deleteRemote: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        database.deleteHistoryEntries(
            ids,
            removeRemote =
                if (deleteRemote) {
                    { tokens -> GlobalYouTubeRepository.instance.removeHistoryItems(tokens) }
                } else {
                    null
                },
        )
    }

    suspend fun updateHistoryEntry(
        id: Long,
        title: String,
        artists: String,
        album: String?,
    ) = withContext(Dispatchers.IO) {
        database.updateHistoryEntry(id, title, artists, album)
    }

    suspend fun syncFromYouTubeHistory() = withContext(Dispatchers.IO) { database.syncYouTubeHistory(::parseRemoteDate) }

    private suspend fun <T> query(
        sql: String,
        args: Array<out Any?>,
        block: (SQLiteStatement) -> T,
    ): T =
        database.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                args.forEachIndexed { index, value ->
                    when (value) {
                        null -> statement.bindNull(index + 1)
                        is String -> statement.bindText(index + 1, value)
                        is Number -> statement.bindLong(index + 1, value.toLong())
                        else -> error("Unsupported history query argument")
                    }
                }
                block(statement)
            }
        }

    private suspend fun queryInt(sql: String): Int = query(sql, emptyArray<Any?>()) { c -> if (c.step()) c.getInt(0) else 0 }

    private suspend fun queryLong(sql: String): Long = query(sql, emptyArray<Any?>()) { c -> if (c.step()) c.getLong(0) else 0L }

    private fun cursorToEntry(c: SQLiteStatement): HistoryEntry {
        val songId = c.nullableText(1)
        val hasSnapshot = !c.isNull(11)
        return HistoryEntry(
            id = c.getLong(0),
            songId = requireNotNull(songId),
            title = if (hasSnapshot) c.getText(12) else c.nullableText(2) ?: "",
            artists = if (hasSnapshot) c.getText(13) else c.nullableText(18).orEmpty(),
            album = c.nullableText(if (hasSnapshot) 14 else 3),
            duration = if (hasSnapshot) c.getLong(15).takeUnless { c.isNull(15) } else c.getLong(5).takeIf { it >= 0 },
            thumbnailUrl = c.nullableText(if (hasSnapshot) 16 else 4),
            playTimeMs = c.getLong(6).takeIf { c.getInt(10) != 0 },
            playedAt = c.getLong(7),
            source = c.nullableText(8) ?: "LOCAL",
            remoteDateLabel = c.nullableText(19),
            remoteOrder = c.getInt(20),
            remoteFeedbackToken = if (hasSnapshot) c.nullableText(17) ?: c.nullableText(9) else c.nullableText(9),
        )
    }

    private fun parseRemoteDate(
        playedText: String,
        now: Long,
        contentLanguage: String,
    ): RemoteHistoryDate? = parseHistoryDate(playedText, now, Locale.forLanguageTag(contentLanguage))

    private suspend fun queryInt(
        sql: String,
        args: Array<out Any?>,
    ): Int = query(sql, args) { c -> if (c.step()) c.getInt(0) else 0 }

    private suspend fun queryLong(
        sql: String,
        args: Array<out Any?>,
    ): Long? = query(sql, args) { c -> if (c.step()) c.getLong(0) else null }
}

private fun SQLiteStatement.nullableText(index: Int): String? = if (isNull(index)) null else getText(index)
