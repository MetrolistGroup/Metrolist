/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService

/**
 * Builds download requests for [items], skipping any song whose download has already
 * completed.
 *
 * Re-adding a completed download is not a no-op: media3 merges the request into the
 * existing entry and moves it back to [Download.STATE_QUEUED], which re-runs the
 * download and overwrites the song's `dateDownload` timestamp.
 */
fun <T> buildPendingDownloadRequests(
    downloads: Map<String, Download>,
    items: List<T>,
    id: (T) -> String,
    title: (T) -> String,
): List<DownloadRequest> =
    items
        .filter { downloads[id(it)]?.state != Download.STATE_COMPLETED }
        .map { item ->
            DownloadRequest
                .Builder(id(item), id(item).toUri())
                .setCustomCacheKey(id(item))
                .setData(title(item).toByteArray())
                .build()
        }

/**
 * Enqueues [items] for offline playback, skipping songs that are already downloaded.
 */
fun <T> Context.enqueuePendingDownloads(
    downloads: Map<String, Download>,
    items: List<T>,
    id: (T) -> String,
    title: (T) -> String,
) {
    buildPendingDownloadRequests(downloads, items, id, title).forEach { request ->
        DownloadService.sendAddDownload(
            this,
            ExoDownloadService::class.java,
            request,
            false,
        )
    }
}
