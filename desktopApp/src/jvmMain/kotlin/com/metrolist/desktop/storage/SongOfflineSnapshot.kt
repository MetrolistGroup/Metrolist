package com.metrolist.desktop.storage

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.OfflineSongAvailability
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction

internal data class SongOfflineSnapshot(
    val downloadedIds: Set<String> = emptySet(),
    val cachedIds: Set<String> = emptySet(),
)

internal fun desktopSongOfflineSnapshot(
    downloads: List<DownloadedFile>,
    cachedIds: Set<String>,
    downloadPath: (String) -> String? = DownloadManager::getLocalAudioPath,
    cachePath: (String) -> String? = SongCacheManager::getCachedFilePath,
): SongOfflineSnapshot =
    SongOfflineSnapshot(
        downloadedIds = downloads.mapNotNullTo(mutableSetOf()) { it.songId.takeIf { id -> downloadPath(id) != null } },
        cachedIds = cachedIds.filterTo(mutableSetOf()) { cachePath(it) != null },
    )

internal fun desktopSongOfflineAvailability(
    database: MusicDatabase,
    downloads: () -> List<DownloadedFile> = { DownloadManager.downloadedFiles.value },
    cachedIds: () -> Set<String> = { SongCacheManager.cachedSongIds.value },
    downloadPath: (String) -> String? = DownloadManager::getLocalAudioPath,
    cachePath: (String) -> String? = SongCacheManager::getCachedFilePath,
): OfflineSongAvailability =
    OfflineSongAvailability {
        val indexedDownloads = downloads()
        val snapshot = desktopSongOfflineSnapshot(indexedDownloads, cachedIds(), downloadPath, cachePath)
        restoreDownloadedSongs(database, indexedDownloads.filter { it.songId in snapshot.downloadedIds })
        snapshot.downloadedIds + snapshot.cachedIds
    }

internal suspend fun restoreDownloadedSongs(
    database: MusicDatabase,
    downloads: List<DownloadedFile>,
) {
    database.withDatabaseTransaction {
        val dao = database.dao()
        downloads.forEach { download ->
            if (dao.getSongById(download.songId) == null) {
                dao.upsertHistorySong(download.toSongItem())
                dao.updateDownloadedInfo(download.songId, true, null)
            }
        }
    }
}
