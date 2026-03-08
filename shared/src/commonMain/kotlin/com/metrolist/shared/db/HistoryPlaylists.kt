package com.metrolist.shared.db

import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.history_playlist_monthly
import com.metrolist.shared.generated.resources.history_playlist_weekly
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Instant

private const val DAY_MS = 86_400_000L

private const val HISTORY_PLAYLIST_TRACK_LIMIT = 30

internal enum class HistoryPlaylistPeriod(
    val playlistId: String,
    val title: StringResource,
    val windowMs: Long,
) {
    WEEKLY(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID, Res.string.history_playlist_weekly, 7 * DAY_MS),
    MONTHLY(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID, Res.string.history_playlist_monthly, 30 * DAY_MS),
    ;

    fun periodKey(epochMs: Long): Long {
        val date = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC).date
        return when (this) {
            WEEKLY -> date.toEpochDays() / 7
            MONTHLY -> date.year.toLong() * 12 + date.month.ordinal
        }
    }
}

fun isHistoryPlaylistId(id: String): Boolean = HistoryPlaylistPeriod.entries.any { it.playlistId == id }

suspend fun emptyHistoryPlaylist(id: String): PlaylistItem? {
    val builtin = BuiltinPlaylist.fromId(id)?.takeIf { it.collectionType == null } ?: return null
    return builtin.playlist(getString(builtin.title)).copy(thumbnail = null, songCount = "0")
}

suspend fun MusicDatabase.refreshHistoryPlaylist(id: String) {
    if (isHistoryPlaylistId(id)) withContext(Dispatchers.Default) { refreshHistoryPlaylists() }
}

internal suspend fun MusicDatabase.refreshHistoryPlaylists(nowMs: Long = Clock.System.now().toEpochMilliseconds()) {
    if (!Settings.shared.historyPlaylistsEnabled) {
        deleteHistoryPlaylists()
        return
    }
    HistoryPlaylistPeriod.entries.forEach { period ->
        val existing = dao().findPlaylistEntity(period.playlistId)
        val name = getString(period.title)
        val existingKey =
            existing
                ?.lastUpdateTime
                ?.toInstant(TimeZone.UTC)
                ?.toEpochMilliseconds()
                ?.let(period::periodKey)
        if (existing != null && existingKey == period.periodKey(nowMs)) {
            val updated = dao().withThumbnail(existing.copy(name = name))
            if (updated != existing) dao().upsert(updated)
            return@forEach
        }

        val songIds = topHistorySongIds(period, nowMs)
        if (songIds.isEmpty()) {
            existing?.let { dao().deletePlaylistById(period.playlistId) }
            return@forEach
        }
        writeHistoryPlaylist(period, name, songIds, existing, nowMs)
    }
}

private suspend fun MusicDatabase.deleteHistoryPlaylists() {
    val dao = dao()
    val existing = HistoryPlaylistPeriod.entries.filter { dao.findPlaylistEntity(it.playlistId) != null }
    if (existing.isEmpty()) return
    withDatabaseTransaction {
        existing.forEach { dao.deletePlaylistById(it.playlistId) }
    }
}

private suspend fun MusicDatabase.topHistorySongIds(
    period: HistoryPlaylistPeriod,
    nowMs: Long,
): List<String> {
    val since = nowMs - period.windowMs
    return historySongStatistics(since, nowMs)
        .sortedWith(
            compareByDescending<HistoryStatGroup> { it.playCount }
                .thenByDescending { it.playTimeMs }
                .thenByDescending { it.lastPlayedAt },
        ).take(HISTORY_PLAYLIST_TRACK_LIMIT)
        .map { it.songId }
}

private suspend fun MusicDatabase.writeHistoryPlaylist(
    period: HistoryPlaylistPeriod,
    name: String,
    songIds: List<String>,
    existing: PlaylistEntity?,
    nowMs: Long,
) {
    val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.UTC)
    withDatabaseTransaction {
        val dao = dao()
        val entity =
            PlaylistEntity(
                id = period.playlistId,
                name = name,
                browseId = null,
                createdAt = existing?.createdAt ?: now,
                lastUpdateTime = now,
                isEditable = false,
                bookmarkedAt = existing?.bookmarkedAt ?: now,
                isLocal = true,
                customThumbnailUrl = existing?.customThumbnailUrl,
            )
        dao.upsert(entity)
        dao.clearPlaylist(period.playlistId)
        songIds.forEachIndexed { index, songId ->
            dao.insert(PlaylistSongMap(playlistId = period.playlistId, songId = songId, position = index))
        }
        val withThumbnail = dao.withThumbnail(entity)
        if (withThumbnail != entity) dao.upsert(withThumbnail)
    }
}

private suspend fun DatabaseDao.withThumbnail(playlist: PlaylistEntity): PlaylistEntity {
    if (!playlist.thumbnailUrl.isNullOrBlank()) return playlist
    val thumbnail = playlistPreviewThumbnails(playlist.id).firstOrNull() ?: return playlist
    return playlist.copy(thumbnailUrl = thumbnail)
}
