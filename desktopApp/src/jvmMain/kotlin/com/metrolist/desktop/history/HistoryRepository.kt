package com.metrolist.desktop.history

import com.metrolist.shared.db.HistoryCursor
import com.metrolist.shared.db.HistoryStatGroup
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.StoredHistoryPlay
import com.metrolist.shared.db.StoredHistoryRow
import com.metrolist.shared.db.deleteHistoryEntries
import com.metrolist.shared.db.historyAlbumStatistics
import com.metrolist.shared.db.historyArtistStatistics
import com.metrolist.shared.db.historyHasRemoteTokens
import com.metrolist.shared.db.historySongStatistics
import com.metrolist.shared.db.historyStatisticsSnapshot
import com.metrolist.shared.db.historyStatisticsTotals
import com.metrolist.shared.db.historyTimeBuckets
import com.metrolist.shared.db.importHistorySources
import com.metrolist.shared.db.parseHistoryDate
import com.metrolist.shared.db.recentVisibleHistory
import com.metrolist.shared.db.recordHistoryPlay
import com.metrolist.shared.db.statisticsSongs
import com.metrolist.shared.db.syncYouTubeHistory
import com.metrolist.shared.db.updateHistoryEntry
import com.metrolist.shared.db.visibleHistoryIds
import com.metrolist.shared.db.visibleHistoryPage
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.Locale
import java.util.logging.Logger

internal fun purgeLegacyScrobbleQueue(file: File) {
    if (!file.exists()) return
    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
        db.createStatement().use { it.execute("DROP TABLE IF EXISTS scrobble_queue") }
    }
}

internal fun purgeLegacyScrobbleQueues(
    activeFiles: List<File>,
    oldFile: File,
    xdgFile: File,
) {
    activeFiles.forEach(::purgeLegacyScrobbleQueue)
    val activePaths = activeFiles.map { it.canonicalPath }.toSet()
    listOf(oldFile, xdgFile).distinctBy { it.canonicalPath }.filterNot { it.canonicalPath in activePaths }.forEach { file ->
        try {
            purgeLegacyScrobbleQueue(file)
        } catch (e: Exception) {
            Logger.getLogger("HistoryRepository").warning("Failed to purge inactive history queue ${file.path}: ${e.message}")
        }
    }
}

internal fun activeLegacyHistoryFiles(
    oldFile: File,
    xdgFile: File,
): List<File> = listOf(xdgFile.takeIf(File::exists) ?: oldFile.takeIf(File::exists)).filterNotNull()

internal suspend fun MusicDatabase.initializeDesktopHistory(
    oldFile: File,
    xdgFile: File,
) {
    if (dao().historyImportEnabled() != false) {
        val sources = activeLegacyHistoryFiles(oldFile, xdgFile).map { it.canonicalPath to readLegacyHistory(it) }
        withDatabaseTransaction {
            if (dao().historyImportEnabled() != false) {
                importHistorySources(sources)
                dao().finishLegacyHistoryImport()
            }
        }
    }
    purgeLegacyScrobbleQueues(emptyList(), oldFile, xdgFile)
}

internal fun readLegacyHistory(file: File): List<StoredHistoryPlay> {
    check(file.isFile) { "Legacy history source is not a regular file: $file" }
    val snapshotDir = Files.createTempDirectory("metrolist-history-import").toFile()
    val snapshot = File(snapshotDir, "snapshot.db")
    try {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { stmt ->
                stmt.execute("PRAGMA busy_timeout = 15000")
                stmt.execute("VACUUM INTO '${snapshot.absolutePath.replace("'", "''")}'")
            }
        }
        return DriverManager.getConnection("jdbc:sqlite:${snapshot.absolutePath}").use { db ->
            db.createStatement().use { stmt ->
                check(stmt.executeQuery("PRAGMA integrity_check").use { it.next() && it.getString(1) == "ok" })
                val columns =
                    stmt.executeQuery("PRAGMA table_info(play_history)").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString("name")) }
                    }
                check(
                    columns.containsAll(setOf("id", "song_id", "title", "artists", "played_at", "thumbnail_url")),
                ) { "Unsupported legacy history schema" }

                fun column(
                    name: String,
                    default: String = "NULL",
                ) = if (name in columns) name else "$default AS $name"
                stmt
                    .executeQuery(
                        "SELECT id, song_id, title, artists, ${column(
                            "album",
                        )}, ${column(
                            "duration",
                        )}, thumbnail_url, played_at, ${column(
                            "source",
                            "'LOCAL'",
                        )}, ${column("remote_feedback_token")}, ${column("remote_synced", "0")} FROM play_history ORDER BY id",
                    ).use { rs ->
                        buildList {
                            while (rs.next()) {
                                val songId = rs.getString("song_id")
                                check(!songId.isNullOrBlank()) { "Invalid legacy history song ID" }
                                add(
                                    StoredHistoryPlay(
                                        rs.getLong("id"),
                                        songId,
                                        rs.getString("title"),
                                        rs.getString("artists"),
                                        rs.getString("album"),
                                        (rs.getObject("duration") as? Number)?.toLong(),
                                        rs.getString("thumbnail_url"),
                                        rs.getLong("played_at"),
                                        rs.getString("source"),
                                        rs.getString("remote_feedback_token"),
                                        rs.getInt("remote_synced") != 0,
                                    ),
                                )
                            }
                        }
                    }
            }
        }
    } finally {
        snapshotDir.deleteRecursively()
    }
}

data class HistoryEntry(
    val id: Long,
    val songId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val duration: Long?,
    val thumbnailUrl: String?,
    val playedAt: Long,
    val source: String,
    val remoteFeedbackToken: String?,
    val playTimeMs: Long?,
    val remoteDateLabel: String? = null,
    val remoteOrder: Int = 0,
)

internal fun StoredHistoryRow.toHistoryEntry() =
    HistoryEntry(
        id,
        songId,
        title,
        artists,
        album,
        duration,
        thumbnailUrl,
        playedAt,
        source,
        remoteFeedbackToken,
        playTimeMs,
        remoteDateLabel,
        remoteOrder,
    )

data class HistoryStat(
    val songId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val thumbnailUrl: String?,
    val playCount: Int,
    val lastPlayedAt: Long,
)

data class ArtistStat(
    val name: String,
    val playCount: Int,
)

data class AlbumStat(
    val name: String,
    val artists: String,
    val thumbnailUrl: String?,
    val playCount: Int,
)

object HistoryRepository {
    private val oldFile get() = File(System.getProperty("user.home"), ".metrolist/history.db")
    private val xdgFile get() = File(XdgUtils.getXdgDataHome(), "metrolist/history.db")
    private val database get() = GlobalContext.get().get<MusicDatabase>()
    private var initialized = false

    @Synchronized fun initialize() {
        if (initialized) return
        runBlocking { database.initializeDesktopHistory(oldFile, xdgFile) }
        initialized = true
    }

    suspend fun recordPlay(
        song: SongItem,
        playedAt: Long = System.currentTimeMillis(),
        source: String = "LOCAL",
        remoteFeedbackToken: String? = null,
        remoteSynced: Boolean = false,
        playTimeMs: Long? = null,
    ) {
        initialize()
        database.recordHistoryPlay(song, playedAt, source, remoteFeedbackToken, remoteSynced, playTimeMs)
    }

    suspend fun getRecentHistory(
        limit: Int = 50,
        before: HistoryCursor? = null,
    ): List<HistoryEntry> {
        initialize()
        return database.recentVisibleHistory(limit, before = before).map(StoredHistoryRow::toHistoryEntry)
    }

    suspend fun getHistoryPage(
        limit: Int,
        offset: Int,
        query: String,
        sortMode: HistorySortMode,
        direction: SortDirection,
    ): List<HistoryEntry> {
        initialize()
        return database
            .visibleHistoryPage(
                limit,
                offset,
                query,
                sortMode,
                direction,
                preserveUnicodeOrder = true,
            ).map(StoredHistoryRow::toHistoryEntry)
    }

    suspend fun getVisibleHistoryIds(query: String): Set<Long> {
        initialize()
        return database.visibleHistoryIds(query, preserveUnicodeOrder = true)
    }

    suspend fun hasHistoryRemoteTokens(ids: Set<Long>): Boolean {
        initialize()
        return database.historyHasRemoteTokens(ids)
    }

    suspend fun getRecentHistorySince(
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
        limit: Int = 2000,
    ): List<HistoryEntry> {
        initialize()
        return database.recentVisibleHistory(limit, sinceMs, untilMs).map(StoredHistoryRow::toHistoryEntry)
    }

    suspend fun getStatisticsSongs(groups: List<HistoryStatGroup>): List<SongItem> = database.statisticsSongs(groups)

    suspend fun getStatisticsSnapshot(
        sinceMs: Long,
        untilMs: Long,
    ) = run {
        initialize()
        database.historyStatisticsSnapshot(sinceMs, untilMs)
    }

    suspend fun getHistoryTotals(
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
    ) = run {
        initialize()
        database.historyStatisticsTotals(sinceMs, untilMs)
    }

    suspend fun getMostPlayed(
        limit: Int = 20,
        sinceMs: Long = 0,
        untilMs: Long = Long.MAX_VALUE,
    ): List<HistoryStat> =
        run {
            initialize()
            database.historySongStatistics(sinceMs, untilMs).take(limit).map {
                HistoryStat(it.songId, it.title, it.artists, it.album, it.thumbnailUrl, it.playCount, it.lastPlayedAt)
            }
        }

    suspend fun getArtistStats(
        limit: Int = 20,
        sinceMs: Long = 0,
        untilMs: Long = Long.MAX_VALUE,
    ): List<ArtistStat> =
        run {
            initialize()
            database.historyArtistStatistics(sinceMs, untilMs).take(limit).map { ArtistStat(it.name, it.playCount) }
        }

    suspend fun getAlbumStats(
        limit: Int = 20,
        sinceMs: Long = 0,
        untilMs: Long = Long.MAX_VALUE,
    ): List<AlbumStat> =
        run {
            initialize()
            database
                .historyAlbumStatistics(
                    sinceMs,
                    untilMs,
                ).take(limit)
                .map { AlbumStat(it.name, it.artists, it.thumbnailUrl, it.playCount) }
        }

    suspend fun getScrobblesPerDay(
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
    ): Map<String, Int> =
        run {
            initialize()
            database.historyTimeBuckets(sinceMs, untilMs, hourly = false, localTime = true).associate { it.bucket to it.playCount }
        }

    suspend fun getScrobblesPerHour(
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
    ): Map<Int, Int> =
        run {
            initialize()
            database.historyTimeBuckets(sinceMs, untilMs, hourly = true, localTime = true).associate { it.bucket.toInt() to it.playCount }
        }

    suspend fun updateHistoryEntry(
        id: Long,
        title: String,
        artists: String,
        album: String?,
    ) {
        initialize()
        database.updateHistoryEntry(id, title, artists, album)
    }

    suspend fun deleteHistoryEntry(
        id: Long,
        deleteRemote: Boolean,
    ) = deleteHistoryEntries(setOf(id), deleteRemote)

    suspend fun deleteHistoryEntries(
        ids: Set<Long>,
        deleteRemote: Boolean,
    ) {
        if (ids.isEmpty()) return
        initialize()
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

    suspend fun syncFromYouTubeHistory(): Boolean {
        initialize()
        return database.syncYouTubeHistory { text, now, language ->
            parseHistoryDate(text, now, Locale.forLanguageTag(language))
        }
    }

    @Synchronized fun clearQueuedScrobbles() {
        initialize()
        purgeLegacyScrobbleQueues(emptyList(), oldFile, xdgFile)
    }
}
