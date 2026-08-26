/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubePlaylistQueue(
    private val playlistId: String,
    private val playlistTitle: String? = null,
    private val initialSongs: List<SongItem> = emptyList(),
    private val initialContinuation: String? = null,
    private val startIndex: Int = 0,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var continuation: String? = initialContinuation
    private var retryCount = 0
    private val maxRetries = 3

    override suspend fun getInitialStatus(): Queue.Status {
        return withContext(IO) {
            if (initialSongs.isNotEmpty()) {
                Queue.Status(
                    title = playlistTitle,
                    items = initialSongs.map { it.toMediaItem() },
                    mediaItemIndex = startIndex,
                )
            } else {
                val playlistPage = YouTube.playlist(playlistId).getOrThrow()
                continuation = playlistPage.songsContinuation
                Queue.Status(
                    title = playlistPage.playlist.title,
                    items = playlistPage.songs.map { it.toMediaItem() },
                    mediaItemIndex = startIndex,
                )
            }
        }
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaItem> {
        return withContext(IO) {
            val currentContinuation = continuation ?: return@withContext emptyList()
            var lastException: Throwable? = null
            
            for (attempt in 0..maxRetries) {
                try {
                    val continuationPage = YouTube.playlistContinuation(currentContinuation).getOrThrow()
                    continuation = continuationPage.continuation
                    retryCount = 0
                    return@withContext continuationPage.songs.map { it.toMediaItem() }
                } catch (e: Exception) {
                    lastException = e
                    retryCount++
                    if (retryCount >= maxRetries) {
                        continuation = null
                    }
                }
            }
            throw lastException ?: Exception("Failed to get next page")
        }
    }
}
