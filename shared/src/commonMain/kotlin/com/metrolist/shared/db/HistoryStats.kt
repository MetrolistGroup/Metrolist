package com.metrolist.shared.db

import androidx.room3.RoomRawQuery
import androidx.room3.useWriterConnection
import com.metrolist.shared.db.entities.HistoryStatExclusion
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.filterAndSortHistory
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class HistoryStatGroup(
    val songId: String,
    val title: String,
    val artists: String,
    val artistNamesJson: String?,
    val album: String?,
    val albumId: String?,
    val thumbnailUrl: String?,
    val hasSnapshot: Boolean,
    val playCount: Int,
    val playTimeMs: Long,
    val lastPlayedAt: Long,
    val lastEventId: Long,
    val duration: Long?,
)

data class HistoryArtistCredit(
    val songId: String,
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
)

data class HistoryBucket(
    val bucket: String,
    val playCount: Int,
)

data class HistoryArtistStatistic(
    val name: String,
    val playCount: Int,
    val playTimeMs: Long,
    val thumbnailUrl: String?,
    val id: String? = null,
)

data class HistoryAlbumStatistic(
    val name: String,
    val artists: String,
    val thumbnailUrl: String?,
    val playCount: Int,
    val playTimeMs: Long,
    val id: String? = null,
)

private fun historyStatsQuery(
    select: String,
    sinceMs: Long,
    untilMs: Long,
    catalogArtistIds: Boolean,
    suffix: String = "",
    songIds: List<String>? = null,
    suffixArgs: List<String> = emptyList(),
): RoomRawQuery {
    val args = mutableListOf<Any>(sinceMs, untilMs)
    val filters = mutableListOf("e.timestamp BETWEEN ? AND ?")
    filters += "NOT EXISTS (SELECT 1 FROM history_stat_exclusion b WHERE b.kind = 'song' AND b.value = e.songId)"
    filters +=
        "NOT EXISTS (SELECT 1 FROM history_stat_exclusion b WHERE b.kind = 'snapshot-name:' || e.songId AND b.value = COALESCE(h.artistNamesJson, h.artists))"
    if (catalogArtistIds) {
        filters +=
            "NOT EXISTS (SELECT 1 FROM history_stat_exclusion b WHERE b.kind = 'snapshot-id:' || e.songId AND b.value = COALESCE(h.artistNamesJson, h.artists))"
        filters += """NOT (h.eventId IS NULL AND EXISTS (
            SELECT 1 FROM song_artist_map m JOIN history_stat_exclusion b ON b.kind = 'artist' AND b.value = m.artistId
            WHERE m.songId = e.songId))"""
    } else {
        filters +=
            "NOT (h.eventId IS NULL AND EXISTS (SELECT 1 FROM history_stat_exclusion b WHERE b.kind = 'catalog-name' AND b.value = e.songId))"
    }
    if (songIds != null) {
        filters += "e.songId IN (${songIds.joinToString { "?" }})"
        args.addAll(songIds)
    }
    args.addAll(suffixArgs)
    val where = filters.joinToString(" AND ")
    val sql =
        "$select FROM event e JOIN song s ON s.id = e.songId " +
            "LEFT JOIN history_snapshot h ON h.eventId = e.id WHERE $where $suffix"
    return RoomRawQuery(sql) { statement ->
        args.forEachIndexed { index, value ->
            when (value) {
                is Long -> statement.bindLong(index + 1, value)
                is String -> statement.bindText(index + 1, value)
            }
        }
    }
}

@Serializable
private data class HistoryExclusionScope(
    val sinceMs: Long,
    val untilMs: Long,
    val blocklist: String,
)

private suspend fun MusicDatabase.installHistoryExclusionInvalidation() {
    useWriterConnection { connection ->
        val installed =
            connection.usePrepared(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name GLOB 'history_exclusion_*'",
            ) {
                it.step()
                it.getLong(0) == 15L
            }
        if (installed) return@useWriterConnection
        for (table in listOf("event", "history_snapshot", "song", "song_artist_map", "artist")) {
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                connection.usePrepared(
                    "CREATE TRIGGER IF NOT EXISTS history_exclusion_${table}_$operation AFTER $operation ON $table " +
                        "BEGIN DELETE FROM history_stat_exclusion WHERE kind = 'prepared'; END",
                ) { it.step() }
            }
        }
    }
}

private suspend fun <T> MusicDatabase.withHistoryStatExclusions(
    sinceMs: Long = Long.MIN_VALUE,
    untilMs: Long = Long.MAX_VALUE,
    block: suspend () -> T,
): T =
    withDatabaseTransaction {
        installHistoryExclusionInvalidation()
        val artists = BlockedContent.artists()
        val songIds = BlockedContent.songIds()
        val blocklist = Json.encodeToString(listOf(Json.encodeToString(artists), Json.encodeToString(songIds.sorted())))
        val prepared = dao().historyExclusionScope()?.let { Json.decodeFromString<HistoryExclusionScope>(it) }
        if (prepared != null && prepared.blocklist == blocklist && prepared.sinceMs <= sinceMs && prepared.untilMs >= untilMs) {
            return@withDatabaseTransaction block()
        }
        val names = artists.map { it.name.trim() }.filter(String::isNotBlank)
        val entries =
            (
                songIds.map { HistoryStatExclusion("song", it) } +
                    artists.map { HistoryStatExclusion("artist", it.id) }
            ).toMutableList()
        if (artists.isNotEmpty()) {
            val blockedCredits =
                artists
                    .map { it.id }
                    .chunked(500)
                    .flatMap { dao().historyBlockedArtistCredits(it, sinceMs, untilMs) }
                    .groupBy({ it.songId }, { it.name })
            // Resolve distinct credits with Kotlin case folding; SQLite lower() only folds ASCII.
            dao().historySnapshotCredits(sinceMs, untilMs).forEach { credit ->
                val credits = credit.artistNamesJson?.let { Json.decodeFromString<List<String>>(it) } ?: listOf(credit.artists)
                val key = credit.artistNamesJson ?: credit.artists
                if (credits.any { candidate -> names.any { it.equals(candidate.trim(), ignoreCase = true) } }) {
                    entries += HistoryStatExclusion("snapshot-name:${credit.songId}", key)
                }
                if (credits.any { it in blockedCredits[credit.songId].orEmpty() }) {
                    entries += HistoryStatExclusion("snapshot-id:${credit.songId}", key)
                }
            }
            dao().historyCatalogNames(sinceMs, untilMs).forEach { credit ->
                if (names.any { it.equals(credit.artists.trim(), ignoreCase = true) }) {
                    entries += HistoryStatExclusion("catalog-name", credit.songId)
                }
            }
        }
        entries += HistoryStatExclusion("prepared", "scope", Json.encodeToString(HistoryExclusionScope(sinceMs, untilMs, blocklist)))
        dao().clearHistoryStatExclusions()
        entries.chunked(250).forEach { dao().insertHistoryStatExclusions(it) }
        block()
    }

suspend fun MusicDatabase.historyStatisticsTotals(
    sinceMs: Long,
    untilMs: Long,
    catalogArtistIds: Boolean = false,
): HistoryTotals =
    withHistoryStatExclusions(sinceMs, untilMs) {
        dao().historyStatsTotals(
            historyStatsQuery(
                "SELECT COUNT(*) AS playCount, COALESCE(SUM(CASE WHEN e.playTimeKnown THEN e.playTime ELSE 0 END), 0) AS playTimeMs",
                sinceMs,
                untilMs,
                catalogArtistIds,
            ),
        )
    }

private suspend fun MusicDatabase.statGroups(
    sinceMs: Long,
    untilMs: Long,
    catalogArtistIds: Boolean,
    songsOnly: Boolean = false,
): List<HistoryStatGroup> =
    run {
        dao().historyStatGroups(
            historyStatsQuery(
                """SELECT e.songId, COALESCE(h.title, s.title) AS title,
                COALESCE(h.artists, (SELECT GROUP_CONCAT(name, ', ') FROM (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = e.songId ORDER BY m.position)), '') AS artists,
                h.artistNamesJson, h.eventId IS NOT NULL AS hasSnapshot,
                CASE WHEN h.eventId IS NOT NULL THEN h.album ELSE s.albumName END AS album,
                CASE WHEN h.eventId IS NOT NULL THEN h.albumId ELSE s.albumId END AS albumId,
                CASE WHEN h.eventId IS NOT NULL THEN h.thumbnailUrl ELSE s.thumbnailUrl END AS thumbnailUrl,
                CASE WHEN h.eventId IS NOT NULL THEN h.duration ELSE s.duration END AS duration,
                COUNT(*) AS playCount, SUM(CASE WHEN e.playTimeKnown THEN e.playTime ELSE 0 END) AS playTimeMs, MAX(e.id) AS lastEventId, MAX(e.timestamp) AS lastPlayedAt""",
                sinceMs,
                untilMs,
                catalogArtistIds,
                if (songsOnly) {
                    "GROUP BY e.songId ORDER BY playCount DESC, lastPlayedAt DESC"
                } else {
                    "GROUP BY e.songId, h.title, h.artists, h.artistNamesJson, h.album, h.albumId, h.thumbnailUrl, h.duration, (h.eventId IS NOT NULL) ORDER BY lastPlayedAt DESC"
                },
            ),
        )
    }

suspend fun MusicDatabase.historySongStatistics(
    sinceMs: Long,
    untilMs: Long,
    catalogArtistIds: Boolean = false,
): List<HistoryStatGroup> = withHistoryStatExclusions(sinceMs, untilMs) { statGroups(sinceMs, untilMs, catalogArtistIds, songsOnly = true) }

private suspend fun MusicDatabase.statCredits(
    groups: List<HistoryStatGroup>,
    catalogArtistIds: Boolean,
): Map<String, List<String>> =
    if (catalogArtistIds) {
        groups
            .filterNot { it.hasSnapshot }
            .map { it.songId }
            .distinct()
            .chunked(500)
            .flatMap { dao().historyArtistCredits(it) }
            .groupBy({ it.songId }, { it.name })
    } else {
        emptyMap()
    }

private fun HistoryStatGroup.names(credits: Map<String, List<String>>): List<String> =
    (artistNamesJson?.let { Json.decodeFromString<List<String>>(it) } ?: credits[songId]?.takeUnless { hasSnapshot } ?: listOf(artists))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

suspend fun MusicDatabase.historyArtistStatistics(
    sinceMs: Long,
    untilMs: Long,
    catalogArtistIds: Boolean = false,
): List<HistoryArtistStatistic> =
    withHistoryStatExclusions(sinceMs, untilMs) {
        val groups = statGroups(sinceMs, untilMs, catalogArtistIds)
        artistStatistics(groups, artistMetadata(groups))
    }

private suspend fun MusicDatabase.artistMetadata(groups: List<HistoryStatGroup>): List<HistoryArtistCredit> =
    groups
        .map { it.songId }
        .distinct()
        .chunked(500)
        .flatMap { dao().historyArtistCredits(it) }

private fun HistoryStatGroup.credits(metadata: Map<String, List<HistoryArtistCredit>>): List<HistoryArtistCredit> {
    val catalog =
        metadata[songId].orEmpty().map { credit ->
            if (credit.id.startsWith("LH:") || credit.id.startsWith("LA") || credit.id == "unknown") {
                credit.copy(id = "history-artist:${credit.name}")
            } else {
                credit
            }
        }
    if (!hasSnapshot) return catalog
    return names(emptyMap()).flatMap { name ->
        catalog.filter { it.name == name }.ifEmpty { listOf(HistoryArtistCredit(songId, "history-artist:$name", name, null)) }
    }
}

private fun artistStatistics(
    groups: List<HistoryStatGroup>,
    artistMetadata: List<HistoryArtistCredit>,
): List<HistoryArtistStatistic> {
    val metadata = artistMetadata.groupBy { it.songId }
    return groups
        .flatMap { group -> group.credits(metadata).distinctBy { it.id }.map { it to group } }
        .groupBy { it.first.id }
        .map { (id, plays) ->
            HistoryArtistStatistic(
                plays.first().first.name,
                plays.sumOf { it.second.playCount },
                plays.sumOf { it.second.playTimeMs },
                plays.firstNotNullOfOrNull { it.first.thumbnailUrl } ?: plays.first().second.thumbnailUrl,
                id.takeUnless { it.startsWith("history-artist:") || it.startsWith("LA") },
            )
        }.sortedByDescending { it.playCount }
}

suspend fun MusicDatabase.historyAlbumStatistics(
    sinceMs: Long,
    untilMs: Long,
    catalogArtistIds: Boolean = false,
): List<HistoryAlbumStatistic> =
    withHistoryStatExclusions(sinceMs, untilMs) {
        val groups = statGroups(sinceMs, untilMs, catalogArtistIds)
        albumStatistics(groups, statCredits(groups, catalogArtistIds), catalogArtistIds)
    }

private fun albumStatistics(
    groups: List<HistoryStatGroup>,
    credits: Map<String, List<String>>,
    catalogArtistIds: Boolean,
): List<HistoryAlbumStatistic> {
    return groups
        .filter { !it.album.isNullOrBlank() }
        .groupBy { it.albumId to it.album.takeIf { _ -> it.albumId == null } }
        .values
        .flatMap { albumGroups ->
            if (albumGroups.first().albumId != null) return@flatMap listOf(albumGroups)
            val leads = albumGroups.map { it.names(credits).firstOrNull() ?: it.artists }.toSet()
            val aliases =
                leads.associateWith { lead ->
                    var end = lead.indexOf(", ")
                    while (end >= 0 && lead.substring(0, end) !in leads) end = lead.indexOf(", ", end + 2)
                    if (end >= 0) lead.substring(0, end) else lead
                }
            albumGroups.groupBy { aliases.getValue(it.names(credits).firstOrNull() ?: it.artists) }.values
        }.map { plays ->
            val latest = plays.first()
            HistoryAlbumStatistic(
                requireNotNull(latest.album),
                if (catalogArtistIds) latest.names(credits).joinToString(", ") else latest.artists,
                latest.thumbnailUrl,
                plays.sumOf { it.playCount },
                plays.sumOf { it.playTimeMs },
            )
        }.sortedByDescending { it.playCount }
}

suspend fun MusicDatabase.historyTimeBuckets(
    sinceMs: Long,
    untilMs: Long,
    hourly: Boolean,
    localTime: Boolean,
    catalogArtistIds: Boolean = false,
): List<HistoryBucket> = withHistoryStatExclusions(sinceMs, untilMs) { timeBuckets(sinceMs, untilMs, hourly, localTime, catalogArtistIds) }

private suspend fun MusicDatabase.timeBuckets(
    sinceMs: Long,
    untilMs: Long,
    hourly: Boolean,
    localTime: Boolean,
    catalogArtistIds: Boolean,
): List<HistoryBucket> {
    val format = if (hourly) "%H" else "%Y-%m-%d"
    val zone = if (localTime) ", 'localtime'" else ""
    // Date buckets and legacy imports without measured playback do not establish an hour.
    val precisionFilter =
        if (hourly) "AND e.remoteDateLabel IS NULL AND (e.source != 'YOUTUBE' OR e.playTimeKnown = 1)" else ""
    return run {
        dao().historyStatBuckets(
            historyStatsQuery(
                "SELECT strftime('$format', e.timestamp / 1000, 'unixepoch'$zone) AS bucket, COUNT(*) AS playCount",
                sinceMs,
                untilMs,
                catalogArtistIds,
                "$precisionFilter GROUP BY bucket ORDER BY bucket",
            ),
        )
    }
}

data class HistoryStatisticsSnapshot(
    val songs: List<HistoryStatGroup>,
    val artists: List<HistoryArtistStatistic>,
    val albums: List<HistoryAlbumStatistic>,
    val totals: HistoryTotals,
    val daily: List<HistoryBucket>,
    val hourly: List<HistoryBucket>,
    val songItems: List<SongItem> = emptyList(),
)

suspend fun MusicDatabase.historyStatisticsSnapshot(
    sinceMs: Long,
    untilMs: Long,
    catalogArtistIds: Boolean = false,
    localTime: Boolean = true,
): HistoryStatisticsSnapshot =
    withContext(Dispatchers.Default) {
        withHistoryStatExclusions(sinceMs, untilMs) {
            val groups = statGroups(sinceMs, untilMs, catalogArtistIds)
            val metadata = artistMetadata(groups)
            val songs =
                groups
                    .groupBy { it.songId }
                    .values
                    .map { plays ->
                        plays.first().copy(playCount = plays.sumOf { it.playCount }, playTimeMs = plays.sumOf { it.playTimeMs })
                    }.sortedWith(compareByDescending<HistoryStatGroup> { it.playCount }.thenByDescending { it.lastPlayedAt })
            HistoryStatisticsSnapshot(
                songs,
                artistStatistics(groups, metadata),
                albumStatistics(groups, metadata.groupBy({ it.songId }, { it.name }), catalogArtistIds),
                HistoryTotals(groups.sumOf { it.playCount }, groups.sumOf { it.playTimeMs }),
                timeBuckets(sinceMs, untilMs, false, localTime, catalogArtistIds),
                timeBuckets(sinceMs, untilMs, true, localTime, catalogArtistIds),
                songItems = statisticsSongs(songs.take(500)),
            )
        }
    }

data class HistorySongCount(
    val songId: String,
    val playCount: Int,
)

fun MusicDatabase.historyChanges(includeSongMetadata: Boolean = true): Flow<Unit> =
    combine(
        invalidationTracker.createFlow(
            *(listOf("event", "history_snapshot", "song_artist_map", "artist") + if (includeSongMetadata) listOf("song") else emptyList())
                .toTypedArray(),
        ),
        BlockedContent.version,
    ) { _, _ -> }

fun MusicDatabase.collectionPlayCounts(songIds: List<String>? = null): Flow<Map<String, Int>> =
    historyChanges(includeSongMetadata = false)
        .map {
            val ids = songIds
            withHistoryStatExclusions {
                val batches = ids?.distinct()?.chunked(500) ?: listOf(null)
                batches
                    .flatMap { batch ->
                        dao().historySongCounts(
                            historyStatsQuery(
                                "SELECT e.songId, COUNT(*) AS playCount",
                                Long.MIN_VALUE,
                                Long.MAX_VALUE,
                                true,
                                "GROUP BY e.songId",
                                batch,
                            ),
                        )
                    }.associate { it.songId to it.playCount }
            }
        }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)

suspend fun MusicDatabase.mobileHistoryStatisticsSnapshot(
    sinceMs: Long,
    untilMs: Long,
): HistoryStatisticsSnapshot =
    withContext(Dispatchers.Default) {
        withHistoryStatExclusions(sinceMs, untilMs) {
            val groups = statGroups(sinceMs, untilMs, true).sortedByDescending { it.lastEventId }
            val metadata = artistMetadata(groups).groupBy { it.songId }

            val songs =
                groups
                    .groupBy { it.songId }
                    .values
                    .map { plays ->
                        plays.first().copy(playCount = plays.sumOf { it.playCount }, playTimeMs = plays.sumOf { it.playTimeMs })
                    }.sortedByDescending { it.playTimeMs }
            val artists = artistStatistics(groups, metadata.values.flatten()).sortedByDescending { it.playTimeMs }
            val albums =
                groups
                    .filter { it.albumId != null || !it.album.isNullOrBlank() }
                    .groupBy { group ->
                        Triple(
                            group.albumId,
                            group.album.takeIf { group.albumId == null },
                            group.credits(metadata).firstOrNull()?.id.takeIf {
                                group.albumId ==
                                    null
                            },
                        )
                    }.mapNotNull { (_, plays) ->
                        val latest = plays.first()
                        latest.album?.let { name ->
                            HistoryAlbumStatistic(
                                name,
                                plays.flatMap { it.credits(metadata) }.distinctBy { it.id }.joinToString(", ") { it.name },
                                latest.thumbnailUrl,
                                plays.sumOf { it.playCount },
                                plays.sumOf { it.playTimeMs },
                                latest.albumId,
                            )
                        }
                    }.sortedByDescending { it.playTimeMs }
            HistoryStatisticsSnapshot(
                songs,
                artists,
                albums,
                HistoryTotals(groups.sumOf { it.playCount }, groups.sumOf { it.playTimeMs }),
                timeBuckets(sinceMs, untilMs, false, true, true),
                timeBuckets(sinceMs, untilMs, true, true, true),
                statisticsSongs(songs.take(100)),
            )
        }
    }

suspend fun MusicDatabase.statisticsSongs(groups: List<HistoryStatGroup>): List<SongItem> =
    withContext(Dispatchers.Default) {
        groups.chunked(500).flatMap { batch ->
            val rows = dao().historyEventsById(batch.map { it.lastEventId }).associateBy { it.event.id }
            batch.mapNotNull { rows[it.lastEventId]?.withHistoricalMetadata()?.song?.toSongItem() }
        }
    }

suspend fun MusicDatabase.historySong(eventId: Long): SongItem? =
    dao()
        .historyEventsById(listOf(eventId))
        .firstOrNull()
        ?.withHistoricalMetadata()
        ?.song
        ?.toSongItem()

fun MusicDatabase.mostPlayedHistorySongs(
    limit: Int = 100,
    sinceMs: Long = Long.MIN_VALUE,
): Flow<List<SongItem>> =
    historyChanges()
        .map {
            withHistoryStatExclusions {
                val songs =
                    statGroups(sinceMs, Long.MAX_VALUE, true)
                        .sortedByDescending { it.lastEventId }
                        .groupBy { it.songId }
                        .values
                        .map { plays ->
                            plays.first().copy(playCount = plays.sumOf { it.playCount }, playTimeMs = plays.sumOf { it.playTimeMs })
                        }.sortedByDescending { it.playTimeMs }
                        .take(limit)
                statisticsSongs(songs)
            }
        }.flowOn(Dispatchers.Default)

private fun visibleHistoryAccountKey(): String =
    GlobalYouTubeRepository.instance.let { repository ->
        if (repository.isHistorySyncEnabled()) repository.playbackReportingAccountKey().orEmpty() else ""
    }

internal fun latestHistorySongFilter(
    sinceMs: Long?,
    untilMs: Long?,
): String =
    "AND (e.source != 'YOUTUBE' OR e.remoteAccountKey = ?) " +
        "AND e.id = (SELECT latest.id FROM event latest WHERE latest.songId = e.songId " +
        "AND (latest.source != 'YOUTUBE' OR latest.remoteAccountKey = ?) " +
        "AND latest.timestamp BETWEEN ${sinceMs ?: Long.MIN_VALUE} AND ${untilMs ?: Long.MAX_VALUE} " +
        "ORDER BY latest.timestamp DESC, latest.remoteOrder DESC, latest.id DESC LIMIT 1)"

internal suspend fun MusicDatabase.visibleHistoryRows(
    limit: Int,
    sinceMs: Long?,
    untilMs: Long?,
    before: HistoryCursor?,
    catalogArtistIds: Boolean,
    query: String = "",
    sortMode: HistorySortMode = HistorySortMode.NEWEST,
    direction: SortDirection = SortDirection.DESCENDING,
    offset: Int = 0,
    preserveUnicodeOrder: Boolean = false,
): List<StoredHistoryRow> {
    require(limit >= 0 && offset >= 0)
    if (limit == 0) return emptyList()
    return withContext(Dispatchers.Default) {
        withHistoryStatExclusions(sinceMs ?: Long.MIN_VALUE, untilMs ?: Long.MAX_VALUE) {
            // Values here are typed integers; keep the range explicit so SQLite seeks to the cursor.
            val cursor =
                before
                    ?.let {
                        "AND e.timestamp <= ${it.playedAt} AND (e.timestamp < ${it.playedAt} OR e.remoteOrder < ${it.remoteOrder} OR (e.remoteOrder = ${it.remoteOrder} AND e.id < ${it.id}))"
                    }.orEmpty()
            val accountKey = visibleHistoryAccountKey()
            val distinctSongs = latestHistorySongFilter(sinceMs, untilMs)
            val context = currentCoroutineContext()
            val pageIds =
                if (preserveUnicodeOrder &&
                    (query.isNotBlank() || sortMode != HistorySortMode.NEWEST || direction != SortDirection.DESCENDING)
                ) {
                    dao()
                        .historyOrderRows(
                            historyStatsQuery(
                                "SELECT e.id, $HISTORY_TITLE AS title, COALESCE($HISTORY_ARTISTS, '') AS artists, " +
                                    "$HISTORY_ALBUM AS album, " +
                                    "e.timestamp AS playedAt, CASE WHEN e.playTimeKnown THEN e.playTime ELSE NULL END AS playTimeMs, e.remoteOrder",
                                sinceMs ?: Long.MIN_VALUE,
                                untilMs ?: Long.MAX_VALUE,
                                catalogArtistIds,
                                "$cursor $distinctSongs ORDER BY e.timestamp DESC, e.remoteOrder DESC, e.id DESC",
                                suffixArgs = listOf(accountKey, accountKey),
                            ),
                        ).filterAndSortHistory(
                            query,
                            sortMode,
                            HistoryOrderRow::title,
                            HistoryOrderRow::artists,
                            HistoryOrderRow::album,
                            HistoryOrderRow::playedAt,
                            HistoryOrderRow::playTimeMs,
                            checkCancelled = { context.ensureActive() },
                            direction = direction,
                            extractRemoteOrder = HistoryOrderRow::remoteOrder,
                        ).drop(offset)
                        .take(limit)
                        .map { it.id }
                } else {
                    null
                }
            if (pageIds != null && pageIds.isEmpty()) return@withHistoryStatExclusions emptyList()
            val search = if (pageIds == null) historySearch(query) else "AND e.id IN (${pageIds.joinToString()})" to emptyList()
            val order = historyOrder(sortMode, direction)
            dao()
                .historyRowsPage(
                    historyStatsQuery(
                        "SELECT e.*, s.title AS title, s.albumName AS album, s.duration AS duration, s.thumbnailUrl AS thumbnailUrl, " +
                            "(SELECT GROUP_CONCAT(name, ', ') FROM (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = " +
                            "m.artistId WHERE m.songId = e.songId ORDER BY m.position)) AS artists," +
                            "h.eventId AS snapshotEventId, h.title AS snapshotTitle, h.artists AS snapshotArtists, h.album AS " +
                            "snapshotAlbum," +
                            "h.duration AS snapshotDuration, h.thumbnailUrl AS snapshotThumbnailUrl, h.remoteFeedbackToken AS " +
                            "snapshotToken," +
                            "h.artistNamesJson AS snapshotArtistNamesJson, h.albumId AS snapshotAlbumId, s.albumId AS catalogAlbumId",
                        sinceMs ?: Long.MIN_VALUE,
                        untilMs ?: Long.MAX_VALUE,
                        catalogArtistIds,
                        "$cursor $distinctSongs ${search.first} ORDER BY $order LIMIT $limit OFFSET ${if (pageIds == null) offset else 0}",
                        suffixArgs = listOf(accountKey, accountKey) + search.second,
                    ),
                ).map { it.toStoredHistoryRow() }
                .let { rows ->
                    if (pageIds == null) rows else rows.sortedBy { pageIds.indexOf(it.id) }
                }
        }
    }
}

private const val HISTORY_TITLE = "CASE WHEN h.eventId IS NOT NULL THEN h.title ELSE s.title END"
private const val HISTORY_ARTISTS =
    "CASE WHEN h.eventId IS NOT NULL THEN h.artists ELSE " +
        "(SELECT GROUP_CONCAT(name, ', ') FROM (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId " +
        "WHERE m.songId = e.songId ORDER BY m.position)) END"
private const val HISTORY_ALBUM = "CASE WHEN h.eventId IS NOT NULL THEN h.album ELSE s.albumName END"

// SQLite's lower()/NOCASE only fold ASCII. GLOB character classes preserve Unicode case-insensitive search.
private fun historySearch(query: String): Pair<String, List<String>> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return "" to emptyList()
    val pattern =
        buildString {
            append('*')
            normalized.forEach { char ->
                when (char) {
                    '*' -> {
                        append("[*]")
                    }

                    '?' -> {
                        append("[?]")
                    }

                    '[' -> {
                        append("[[]")
                    }

                    else -> {
                        val lower = char.lowercaseChar()
                        val upper = char.uppercaseChar()
                        if (lower != upper) append("[$lower$upper]") else append(char)
                    }
                }
            }
            append('*')
        }
    return "AND ($HISTORY_TITLE GLOB ? OR $HISTORY_ARTISTS GLOB ? OR $HISTORY_ALBUM GLOB ?)" to List(3) { pattern }
}

private fun historyOrder(
    sortMode: HistorySortMode,
    direction: SortDirection,
): String {
    val column =
        when (sortMode) {
            HistorySortMode.NEWEST, HistorySortMode.OLDEST -> "e.timestamp"
            HistorySortMode.TITLE -> "$HISTORY_TITLE COLLATE NOCASE"
            HistorySortMode.ARTIST -> "$HISTORY_ARTISTS COLLATE NOCASE"
            HistorySortMode.PLAY_TIME -> "CASE WHEN e.playTimeKnown = 1 THEN e.playTime ELSE 0 END"
        }
    val order = if (direction == SortDirection.ASCENDING) "ASC" else "DESC"
    val dateOrder = if (sortMode == HistorySortMode.OLDEST || sortMode == HistorySortMode.NEWEST) order else "DESC"
    return "$column $order, e.timestamp DESC, e.remoteOrder $dateOrder, e.id DESC"
}

suspend fun MusicDatabase.visibleHistoryPage(
    limit: Int,
    offset: Int,
    query: String,
    sortMode: HistorySortMode,
    direction: SortDirection,
    catalogArtistIds: Boolean = false,
    preserveUnicodeOrder: Boolean = false,
): List<StoredHistoryRow> =
    visibleHistoryRows(limit, null, null, null, catalogArtistIds, query, sortMode, direction, offset, preserveUnicodeOrder)

suspend fun MusicDatabase.visibleHistoryIds(
    query: String,
    catalogArtistIds: Boolean = false,
    preserveUnicodeOrder: Boolean = false,
): Set<Long> =
    withContext(Dispatchers.Default) {
        withHistoryStatExclusions {
            val accountKey = visibleHistoryAccountKey()
            val context = currentCoroutineContext()
            if (preserveUnicodeOrder && query.isNotBlank()) {
                return@withHistoryStatExclusions dao()
                    .historyOrderRows(
                        historyStatsQuery(
                            "SELECT e.id, $HISTORY_TITLE AS title, COALESCE($HISTORY_ARTISTS, '') AS artists, " +
                                "$HISTORY_ALBUM AS album, " +
                                "e.timestamp AS playedAt, CASE WHEN e.playTimeKnown THEN e.playTime ELSE NULL END AS playTimeMs, e.remoteOrder",
                            Long.MIN_VALUE,
                            Long.MAX_VALUE,
                            catalogArtistIds,
                            latestHistorySongFilter(null, null),
                            suffixArgs = listOf(accountKey, accountKey),
                        ),
                    ).filterAndSortHistory(
                        query,
                        HistorySortMode.NEWEST,
                        HistoryOrderRow::title,
                        HistoryOrderRow::artists,
                        HistoryOrderRow::album,
                        HistoryOrderRow::playedAt,
                        HistoryOrderRow::playTimeMs,
                        checkCancelled = { context.ensureActive() },
                    ).mapTo(mutableSetOf()) { it.id }
            }
            val search = historySearch(query)
            dao()
                .historyIds(
                    historyStatsQuery(
                        "SELECT e.id",
                        Long.MIN_VALUE,
                        Long.MAX_VALUE,
                        catalogArtistIds,
                        "${latestHistorySongFilter(null, null)} ${search.first}",
                        suffixArgs = listOf(accountKey, accountKey) + search.second,
                    ),
                ).toSet()
        }
    }

suspend fun MusicDatabase.historyHasRemoteTokens(ids: Set<Long>): Boolean =
    ids.chunked(500).any { dao().historyTokens(it).any(String::isNotBlank) }

data class HistorySnapshotCredit(
    val songId: String,
    val artists: String,
    val artistNamesJson: String?,
)

data class HistoryCatalogNames(
    val songId: String,
    val artists: String,
)

data class HistoryOrderRow(
    val id: Long,
    val title: String,
    val artists: String,
    val album: String?,
    val playedAt: Long,
    val playTimeMs: Long?,
    val remoteOrder: Int,
)
