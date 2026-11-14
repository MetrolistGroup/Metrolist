package com.metrolist.shared.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class RadioRecommendationHistory {
    private val recentIds = MutableStateFlow<List<String>>(emptyList())

    fun remember(songs: List<SongItem>) {
        val ids = songs.map(SongItem::id).filter(String::isNotBlank).distinct()
        recentIds.update { previous -> (previous.filterNot { it in ids } + ids).takeLast(500) }
    }

    suspend fun select(
        firstPage: RadioQueuePage,
        filter: (List<SongItem>) -> List<SongItem>,
        isCurrent: () -> Boolean,
        loadMore: suspend (String) -> RadioQueuePage,
    ): RadioQueuePage? {
        val previous = recentIds.value.withIndex().associate { it.value to it.index }
        val seenContinuations = mutableSetOf<String>()
        var page = firstPage
        var fallback = emptyList<SongItem>()
        var continuation: String? = null
        var repeatedPages = 0
        for (pageIndex in 0 until 10) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return null
            val eligible = filter(page.songs).filter { it.id.isNotBlank() }.distinctBy(SongItem::id)
            val fresh = eligible.filterNot { it.id in previous }
            continuation = page.continuation?.takeIf(seenContinuations::add)
            if (fresh.isNotEmpty()) {
                return firstPage.copy(songs = varyRadioRecommendations(fresh), continuation = continuation)
            }
            if (fallback.isEmpty()) fallback = eligible
            if (fallback.isNotEmpty()) repeatedPages++
            val token = continuation ?: break
            if (repeatedPages == 3 || pageIndex == 9) break
            page =
                try {
                    loadMore(token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    break
                }
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        return firstPage.copy(
            songs = varyRadioRecommendations(filter(fallback)).sortedBy { previous[it.id] ?: -1 },
            continuation = continuation,
        )
    }
}
