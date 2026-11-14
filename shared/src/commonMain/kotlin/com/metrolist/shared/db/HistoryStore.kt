package com.metrolist.shared.db

import androidx.room3.RoomRawQuery
import com.metrolist.shared.api.YouTubeHistoryEntry
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.daos.HistoryRow
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.HistoryProvenance
import com.metrolist.shared.db.entities.HistorySnapshot
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

data class RemoteHistoryDate(
    val startMs: Long,
    val label: String,
)

data class StoredHistoryPlay(
    val legacyId: Long,
    val songId: String,
    val title: String,
    val artists: String,
    val album: String?,
    val duration: Long?,
    val thumbnailUrl: String?,
    val playedAt: Long,
    val source: String,
    val remoteFeedbackToken: String?,
    val remoteSynced: Boolean,
)

data class StoredHistoryRow(
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
    val artistNames: List<String>? = null,
    val albumId: String? = null,
    val remoteDateLabel: String? = null,
    val remoteOrder: Int = 0,
)

data class HistoryTotals(
    val playCount: Int,
    val playTimeMs: Long,
)

private fun Long.historyTimestamp() = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC)

private val historySyncMutex = Mutex()
private var lastHistorySyncMs = 0L
private var lastHistorySyncSession: YouTubeRepository.PlaybackTrackingSession? = null
private var lastHistorySyncDatabase: MusicDatabase? = null

private fun Long.historyDay(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()).date

const val HISTORY_SNAPSHOT_ARTIST_MATCH_SQL =
    "((blocked_snapshot.artistNamesJson IS NOT NULL AND instr(lower(blocked_snapshot.artistNamesJson), lower(?)) > 0) " +
        "OR (blocked_snapshot.artistNamesJson IS NULL AND lower(trim(blocked_snapshot.artists)) = lower(trim(?))))"

data class HistorySyncRow(
    val id: Long,
    val songId: String,
    val playedAt: Long,
    val source: String,
    val remoteFeedbackToken: String?,
    val playTimeMs: Long?,
    val remoteSynced: Boolean,
    val remoteAccountKey: String? = null,
    val remoteDateLabel: String? = null,
    val remoteOrder: Int = 0,
)

internal class HistorySyncIndex(
    rows: List<HistorySyncRow>,
) {
    private val tokens = mutableMapOf<String, HistorySyncRow>()
    private val days = mutableMapOf<Pair<String, LocalDate>, MutableMap<Long, HistorySyncRow>>()

    init {
        rows.forEach(::add)
    }

    fun find(token: String?): HistorySyncRow? = token?.takeIf(String::isNotBlank)?.let(tokens::get)

    fun findDay(
        songId: String,
        playedAt: Long,
    ): HistorySyncRow? = days[songId to playedAt.historyDay()]?.values?.firstOrNull()

    fun add(row: HistorySyncRow) {
        row.remoteFeedbackToken?.takeIf(String::isNotBlank)?.let { token ->
            val previous = tokens[token]
            if (previous == null || (previous.playTimeMs != null && row.playTimeMs == null) || previous.id == row.id) tokens[token] = row
        }
        if (row.source == "YOUTUBE") {
            val key = row.songId to row.playedAt.historyDay()
            days.getOrPut(key) { mutableMapOf() }[row.id] = row
        }
    }

    fun update(
        row: HistorySyncRow,
        date: RemoteHistoryDate,
        remoteOrder: Int,
        accountKey: String?,
    ) {
        if (row.source == "YOUTUBE") {
            val key = row.songId to row.playedAt.historyDay()
            val matches = days.getValue(key)
            matches.remove(row.id)
            if (matches.isEmpty()) days.remove(key)
        }
        add(
            row.copy(
                playedAt = date.startMs,
                source = "YOUTUBE",
                remoteSynced = true,
                remoteAccountKey = accountKey,
                remoteDateLabel = date.label,
                remoteOrder = remoteOrder,
            ),
        )
    }
}

internal suspend fun MusicDatabase.historySyncIndex(
    entries: List<Pair<YouTubeHistoryEntry, RemoteHistoryDate?>>,
    accountKey: String? = null,
): HistorySyncIndex {
    val rows = mutableMapOf<Long, HistorySyncRow>()

    suspend fun read(
        where: String,
        args: List<Any>,
    ) {
        val accountFilter =
            if (accountKey != null) {
                "AND (e.source != 'YOUTUBE' OR e.remoteAccountKey IS NULL OR e.remoteAccountKey = ?)"
            } else {
                ""
            }
        val queryArgs = args + listOfNotNull(accountKey)
        val query =
            RoomRawQuery(
                "SELECT e.id, e.songId, e.timestamp AS playedAt, e.source, e.remoteSynced, " +
                    "e.remoteAccountKey, e.remoteDateLabel, e.remoteOrder, " +
                    "COALESCE(h.remoteFeedbackToken, e.remoteFeedbackToken) AS remoteFeedbackToken, " +
                    "CASE WHEN e.playTimeKnown THEN e.playTime ELSE NULL END AS playTimeMs " +
                    "FROM event e LEFT JOIN history_snapshot h ON h.eventId = e.id WHERE $where $accountFilter ORDER BY e.timestamp DESC, e.id DESC",
            ) { statement ->
                queryArgs.forEachIndexed { index, value ->
                    when (value) {
                        is String -> statement.bindText(index + 1, value)
                        is Long -> statement.bindLong(index + 1, value)
                    }
                }
            }
        dao().historySyncRows(query).forEach { rows[it.id] = it }
    }
    entries.mapNotNull { it.first.feedbackToken }.distinct().chunked(400).forEach { tokens ->
        val placeholders = tokens.joinToString { "?" }
        read(
            "e.id IN (SELECT id FROM event WHERE remoteFeedbackToken IN ($placeholders) UNION SELECT eventId FROM history_snapshot WHERE remoteFeedbackToken IN ($placeholders))",
            tokens + tokens,
        )
    }
    entries
        .filter { it.second != null && it.second!!.startMs > 0 }
        .groupBy { it.second!!.startMs.historyDay() }
        .forEach { (day, matches) ->
            matches.map { it.first.song.id }.distinct().chunked(500).forEach { ids ->
                read(
                    "e.source = 'YOUTUBE' AND e.timestamp >= ? AND e.timestamp < ? AND e.songId IN (${ids.joinToString { "?" }})",
                    listOf(
                        day.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
                        day.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
                    ) + ids,
                )
            }
        }
    return HistorySyncIndex(rows.values.sortedWith(compareByDescending<HistorySyncRow> { it.playedAt }.thenByDescending { it.id }))
}

internal expect fun parseYouTubeHistoryDate(
    text: String,
    now: Long,
    language: String,
): RemoteHistoryDate?

suspend fun MusicDatabase.syncYouTubeHistory(
    parseDate: (String, Long, String) -> RemoteHistoryDate? = ::parseYouTubeHistoryDate,
): Boolean = syncYouTubeHistory(GlobalYouTubeRepository.instance, parseDate)

internal suspend fun MusicDatabase.syncYouTubeHistory(
    repository: YouTubeRepository,
    parseDate: (String, Long, String) -> RemoteHistoryDate? = ::parseYouTubeHistoryDate,
): Boolean =
    historySyncMutex.withLock {
        if (!repository.isHistorySyncEnabled()) return@withLock false
        val session = repository.playbackTrackingSession()
        val accountKey = repository.playbackReportingAccountKey(session) ?: return@withLock false
        val now = Clock.System.now().toEpochMilliseconds()
        if (lastHistorySyncDatabase === this && lastHistorySyncSession?.snapshot == session.snapshot &&
            now - lastHistorySyncMs < 5 * 60_000L
        ) {
            return@withLock false
        }

        fun requireCurrentAccount() {
            if (!repository.isHistorySyncEnabled() || !repository.isPlaybackTrackingSessionCurrent(session)) {
                throw CancellationException("History account or privacy settings changed")
            }
        }
        val result = repository.getHistoryResult()
        requireCurrentAccount()
        val dates = mutableMapOf<String, RemoteHistoryDate?>()
        val entries =
            result.entries.map { entry ->
                entry to dates.getOrPut(entry.playedText) { parseDate(entry.playedText, now, session.snapshot.locale.hl) }
            }
        val changed =
            withDatabaseTransaction {
                val known = historySyncIndex(entries, accountKey)
                var changed = false
                entries.forEachIndexed { index, (entry, date) ->
                    requireCurrentAccount()
                    if (applyYouTubeHistoryEntry(entry, date, known, -index, accountKey)) changed = true
                }
                requireCurrentAccount()
                changed
            }
        if (result.complete) {
            lastHistorySyncMs = Clock.System.now().toEpochMilliseconds()
            lastHistorySyncSession = session
            lastHistorySyncDatabase = this
        }
        changed
    }

internal suspend fun MusicDatabase.applyYouTubeHistoryEntry(
    entry: YouTubeHistoryEntry,
    date: RemoteHistoryDate?,
    known: HistorySyncIndex? = null,
    remoteOrder: Int = 0,
    accountKey: String? = null,
): Boolean {
    if (entry.song.id.isBlank() || date == null || date.startMs <= 0L) return false
    val rows = known ?: historySyncIndex(listOf(entry to date), accountKey)
    val existing =
        rows.find(entry.feedbackToken)
            ?: if (entry.feedbackToken.isNullOrBlank()) rows.findDay(entry.song.id, date.startMs) else null
    if (existing != null) {
        if (existing.source != "YOUTUBE") return false
        if (existing.playedAt == date.startMs && existing.remoteDateLabel == date.label &&
            existing.remoteOrder == remoteOrder && existing.remoteSynced && existing.remoteAccountKey == accountKey
        ) {
            return false
        }
        dao().updateHistoryTimestamp(existing.id, date.startMs.historyTimestamp(), date.label, remoteOrder, accountKey)
        rows.update(existing, date, remoteOrder, accountKey)
        return true
    }
    val id =
        recordHistoryPlay(
            entry.song,
            date.startMs,
            "YOUTUBE",
            entry.feedbackToken?.takeIf(String::isNotBlank),
            remoteSynced = true,
            remoteDateLabel = date.label,
            remoteOrder = remoteOrder,
            remoteAccountKey = accountKey,
        ) ?: return false
    rows.add(
        HistorySyncRow(id, entry.song.id, date.startMs, "YOUTUBE", entry.feedbackToken, null, true, accountKey, date.label, remoteOrder),
    )
    return true
}

suspend fun MusicDatabase.importHistorySources(
    sources: List<Pair<String, List<StoredHistoryPlay>>>,
    afterInsert: (suspend (Int) -> Unit)? = null,
) {
    withDatabaseTransaction {
        val dao = dao()
        check(dao.historyImportEnabled() != false) { "Legacy history import is disabled for this restored backup" }
        var index = 0
        sources.forEach { (source, plays) ->
            plays.forEach { play ->
                if (dao.historyProvenance(source, play.legacyId) != null) return@forEach
                val copied = dao.copiedHistoryProvenance(source, play.legacyId, play.songId, play.playedAt, play.remoteFeedbackToken)
                val eventId =
                    if (copied != null) {
                        copied.eventId?.let {
                            dao.updateCopiedHistoryEvent(it, play.playedAt.historyTimestamp(), play.source, play.remoteSynced)
                            dao.insert(
                                HistorySnapshot(
                                    it,
                                    play.title,
                                    play.artists,
                                    play.album,
                                    play.duration,
                                    play.thumbnailUrl,
                                    play.remoteFeedbackToken,
                                    Json.encodeToString(listOf(play.artists.trim()).filter(String::isNotBlank)),
                                ),
                            )
                        }
                        copied.eventId
                    } else {
                        if (dao.getSongById(play.songId) == null) {
                            dao.upsertHistorySong(
                                SongItem(
                                    id = play.songId,
                                    title = play.title,
                                    thumbnail = play.thumbnailUrl,
                                    artists =
                                        play.artists
                                            .takeIf(String::isNotBlank)
                                            ?.let { listOf(ArtistTiny(null, it)) }
                                            .orEmpty(),
                                    album = play.album?.let { AlbumTiny(null, it) },
                                    duration = play.duration,
                                ),
                            )
                        }
                        val token = play.remoteFeedbackToken?.takeIf { dao.eventIdForToken(it) == null }
                        val inserted =
                            dao.insert(
                                Event(
                                    songId = play.songId,
                                    timestamp = play.playedAt.historyTimestamp(),
                                    playTime = 0,
                                    playTimeKnown = false,
                                    source = play.source,
                                    remoteFeedbackToken = token,
                                    remoteSynced = play.remoteSynced,
                                ),
                            )
                        check(inserted > 0) { "Failed to import history event" }
                        dao.insert(
                            HistorySnapshot(
                                inserted,
                                play.title,
                                play.artists,
                                play.album,
                                play.duration,
                                play.thumbnailUrl,
                                play.remoteFeedbackToken,
                                Json.encodeToString(listOf(play.artists.trim()).filter(String::isNotBlank)),
                            ),
                        )
                        inserted
                    }
                dao.insert(HistoryProvenance(source, play.legacyId, play.songId, play.playedAt, play.remoteFeedbackToken, eventId))
                afterInsert?.invoke(index++)
            }
        }
    }
}

suspend fun MusicDatabase.recordHistoryPlay(
    song: SongItem,
    playedAt: Long,
    source: String = "LOCAL",
    token: String? = null,
    remoteSynced: Boolean = false,
    playTimeMs: Long? = null,
    remoteDateLabel: String? = null,
    remoteOrder: Int = 0,
    remoteAccountKey: String? = null,
): Long? {
    if (song.id.isBlank()) return null
    return withDatabaseTransaction {
        val dao = dao()
        if (token != null && (dao.eventIdForToken(token) != null || dao.snapshotEventIdForToken(token) != null)) {
            return@withDatabaseTransaction null
        }
        dao.upsertHistorySong(song)
        val id =
            dao.insert(
                Event(
                    songId = song.id,
                    timestamp = playedAt.historyTimestamp(),
                    playTime = playTimeMs ?: 0,
                    playTimeKnown =
                        playTimeMs != null,
                    source = source,
                    remoteFeedbackToken = token,
                    remoteSynced = remoteSynced,
                    remoteDateLabel = remoteDateLabel,
                    remoteAccountKey = remoteAccountKey,
                    remoteOrder = remoteOrder,
                ),
            )
        if (id > 0) {
            if (source == "LOCAL" && playTimeMs != null && playTimeMs > 0) {
                dao.incrementTotalPlayTime(song.id, playTimeMs)
            }
            dao.insert(
                HistorySnapshot(
                    id,
                    song.title,
                    song.artists.joinToString(", ") {
                        it.name
                    },
                    song.album?.name,
                    song.duration,
                    song.thumbnail,
                    token,
                    Json.encodeToString(song.artists.map { it.name.trim() }),
                    song.album?.id?.takeIf(String::isNotBlank),
                ),
            )
        }
        id.takeIf { it > 0 }
    }
}

suspend fun MusicDatabase.history(): List<StoredHistoryRow> = dao().historyRows().map { it.toStoredHistoryRow() }

internal fun HistoryRow.toStoredHistoryRow(): StoredHistoryRow {
    val row = this
    val event = row.event
    val hasSnapshot = row.snapshotEventId != null
    return StoredHistoryRow(
        id = event.id,
        songId = event.songId,
        title = row.snapshotTitle ?: row.title,
        artists = row.snapshotArtists ?: row.artists.orEmpty(),
        album = if (hasSnapshot) row.snapshotAlbum else row.album,
        duration = if (hasSnapshot) row.snapshotDuration else row.duration?.takeIf { it > 0 },
        thumbnailUrl = if (hasSnapshot) row.snapshotThumbnailUrl else row.thumbnailUrl,
        playedAt = event.timestamp.toInstant(TimeZone.UTC).toEpochMilliseconds(),
        source = event.source,
        remoteDateLabel = event.remoteDateLabel,
        remoteOrder = event.remoteOrder,
        remoteFeedbackToken = row.snapshotToken ?: event.remoteFeedbackToken,
        playTimeMs = event.playTime.takeIf { event.playTimeKnown },
        artistNames =
            if (hasSnapshot) {
                row.snapshotArtistNamesJson?.let { Json.decodeFromString<List<String>>(it) }
                    ?: listOfNotNull(row.snapshotArtists?.takeIf(String::isNotBlank))
            } else {
                null
            },
        albumId = if (hasSnapshot) row.snapshotAlbumId else row.catalogAlbumId,
    )
}

fun List<StoredHistoryRow>.visibleHistory(): List<StoredHistoryRow> {
    val blockedSongIds = BlockedContent.songIds()
    val names = BlockedContent.artists().map { it.name.trim() }.filter(String::isNotBlank)
    return filter { row ->
        row.songId !in blockedSongIds &&
            names.none { name ->
                (row.artistNames ?: listOf(row.artists)).any { it.trim().equals(name, ignoreCase = true) }
            }
    }
}

data class HistoryCursor(
    val playedAt: Long,
    val id: Long,
    val remoteOrder: Int = 0,
)

suspend fun MusicDatabase.recentVisibleHistory(
    limit: Int,
    sinceMs: Long? = null,
    untilMs: Long? = null,
    before: HistoryCursor? = null,
    catalogArtistIds: Boolean = false,
): List<StoredHistoryRow> = visibleHistoryRows(limit, sinceMs, untilMs, before, catalogArtistIds)

suspend fun MusicDatabase.deleteHistoryEntries(
    ids: Set<Long>,
    removeRemote: (suspend (List<String>) -> Unit)? = null,
) {
    if (ids.isEmpty()) return
    val chunks = ids.toList().chunked(500)
    if (removeRemote != null) {
        val tokens =
            chunks
                .flatMap { dao().historyTokens(it) }
                .filter(String::isNotBlank)
                .distinct()
        removeRemote(tokens)
    }
    withDatabaseTransaction {
        chunks.forEach { dao().deleteHistoryIds(it) }
    }
}
