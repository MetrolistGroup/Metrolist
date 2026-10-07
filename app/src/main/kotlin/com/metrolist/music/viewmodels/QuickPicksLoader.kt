/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import com.metrolist.music.utils.reportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the Quick Picks list shown on Home so a refresh never blanks it.
 *
 * - The last good list stays published while a new one is computed, and an empty result never
 *   replaces a non-empty list.
 * - Local results are published before the network enrichment only when nothing is shown yet, so
 *   a refresh replaces the visible list once instead of reshuffling it twice.
 * - Only the newest load may publish; starting a load cancels the one it supersedes.
 */
internal class QuickPicksLoader<T>(
    private val limit: Int = 20,
    private val shuffle: (List<T>) -> List<T> = { it.shuffled() },
    private val idOf: (T) -> Any,
) {
    private val _items = MutableStateFlow<List<T>?>(null)
    val items: StateFlow<List<T>?> = _items.asStateFlow()

    private val lock = Any()
    private var latestRequest = 0L
    private var activeJob: Job? = null

    suspend fun load(
        local: suspend () -> List<T>,
        similar: suspend () -> List<T>,
    ) = coroutineScope {
        val request = begin(currentCoroutineContext()[Job])

        val localDeferred = async { local().distinctBy(idOf) }
        val similarDeferred = async {
            try {
                similar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                emptyList()
            }
        }

        val localItems = localDeferred.await()
        currentCoroutineContext().ensureActive()

        val publishedLocal =
            _items.value.isNullOrEmpty() &&
                localItems.isNotEmpty() &&
                publish(request, shuffle(localItems).take(limit))

        val similarItems = similarDeferred.await()
        currentCoroutineContext().ensureActive()

        val localIds = localItems.mapTo(HashSet(), idOf)
        if (publishedLocal && similarItems.all { idOf(it) in localIds }) return@coroutineScope

        publish(request, shuffle((localItems + similarItems).distinctBy(idOf)).take(limit))
    }

    private fun begin(job: Job?): Long =
        synchronized(lock) {
            activeJob?.takeIf { it != job }?.cancel()
            activeJob = job
            ++latestRequest
        }

    private fun publish(request: Long, items: List<T>): Boolean =
        synchronized(lock) {
            if (request != latestRequest) return false
            if (items.isEmpty() && !_items.value.isNullOrEmpty()) return false
            _items.value = items
            true
        }
}
