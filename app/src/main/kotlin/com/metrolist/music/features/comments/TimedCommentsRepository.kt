package com.metrolist.music.features.comments

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.TimedComment
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, track-scoped comments cache. It loads one window at a time so the UI can start
 * quickly and asks for the next page only when the currently visible window is nearly consumed.
 */
object TimedCommentsRepository {
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val COMMENTS_PER_WINDOW = 10
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val fetchMutex = Mutex()

    suspend fun loadNextWindow(
        videoId: String,
        reset: Boolean = false,
    ): WindowResult = fetchMutex.withLock {
        val now = System.currentTimeMillis()
        var current = if (reset) {
            CacheEntry()
        } else {
            cache[videoId]?.takeIf { it.expiresAt > now } ?: CacheEntry()
        }

        if (!current.exhausted) {
            val targetCount = current.comments.size + COMMENTS_PER_WINDOW
            val seenContinuations = mutableSetOf<String>()
            while (current.comments.size < targetCount && !current.exhausted) {
                current.continuation?.let { token ->
                    if (!seenContinuations.add(token)) {
                        current = current.copy(exhausted = true)
                        break
                    }
                }
                if (current.pending.isNotEmpty()) {
                    val needed = targetCount - current.comments.size
                    val nextComments = current.pending.take(needed)
                    current = current.copy(
                        comments = current.comments + nextComments,
                        pending = current.pending.drop(nextComments.size),
                        exhausted = current.continuation == null && current.pending.size <= nextComments.size,
                        expiresAt = now + CACHE_TTL_MS,
                    )
                    continue
                }

                val page = withContext(Dispatchers.IO) {
                    YouTube.featuredCommentsPage(
                        videoId = videoId,
                        continuation = current.continuation,
                        client = current.client,
                    )
                }
                if (page.isFailure) break

                val response = page.getOrThrow()
                val merged =
                    (current.comments + current.pending + response.comments)
                        .distinctBy { it.id }
                val needed = targetCount - current.comments.size
                val nextComments = merged.drop(current.comments.size).take(needed)
                val nextPending = merged.drop(current.comments.size + nextComments.size)
                current = current.copy(
                    comments = current.comments + nextComments,
                    pending = nextPending,
                    continuation = response.continuation,
                    client = response.client,
                    exhausted = response.continuation == null && nextPending.isEmpty(),
                    expiresAt = now + CACHE_TTL_MS,
                )
                if (response.comments.isEmpty() && response.continuation == null) break
            }
        }

        if (current.comments.isEmpty() && !current.exhausted) {
            // Keep the entry retryable when the initial request fails.
            cache.remove(videoId)
        } else {
            cache[videoId] = current
        }
        current.toResult()
    }

    fun clearAll() {
        cache.clear()
    }

    private data class CacheEntry(
        val comments: List<TimedComment> = emptyList(),
        val pending: List<TimedComment> = emptyList(),
        val continuation: String? = null,
        val client: YouTubeClient? = null,
        val exhausted: Boolean = false,
        val expiresAt: Long = Long.MAX_VALUE,
    ) {
        fun toResult() =
            WindowResult(
                comments = comments,
                hasMore = !exhausted,
            )
    }

    data class WindowResult(
        val comments: List<TimedComment>,
        val hasMore: Boolean,
    )
}
