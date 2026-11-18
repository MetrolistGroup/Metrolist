package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.RadioRecommendationHistory
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.buildRadioQueueRecommendations
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DesktopQueueRadio(
    private val scope: CoroutineScope,
    private val queue: MusicQueue,
    private val onQueueChanged: () -> Unit,
    private val radio: suspend (String) -> RadioQueuePage = { GlobalYouTubeRepository.instance.startRadioQueue(it) },
    private val isBlocked: (SongItem) -> Boolean = BlockedContent::isBlocked,
    private val continueRadio: suspend (String) -> RadioQueuePage = { GlobalYouTubeRepository.instance.continueRadioQueue(it) },
    private val autoLoadMore: () -> Boolean = { Settings.shared.autoLoadMore },
    private val recommendationHistory: RadioRecommendationHistory = RadioRecommendationHistory(),
) {
    private val mutex = Mutex()
    private var prefetchJob: Job? = null
    private val queuedIds = linkedSetOf<String>()
    private var continuation: String? = null

    // Classic mode keeps YouTube's order: songs not yet enqueued from the last
    // fetched page are served before fetching the next one.
    private var pendingClassicSongs: List<SongItem> = emptyList()

    fun reset() {
        cancelPrefetch()
        continuation = null
        pendingClassicSongs = emptyList()
    }

    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    fun syncQueue() {
        queuedIds.clear()
        queuedIds.addAll(
            queue.items.value
                .map(SongItem::id)
                .filter(String::isNotBlank),
        )
    }

    fun prefetch(
        seed: SongItem,
        shouldCommit: () -> Boolean,
    ) {
        if (prefetchJob?.isActive == true) return
        var job: Job? = null
        job =
            scope.launch {
                try {
                    load(6, seed, playImmediately = false, shouldCommit)
                } finally {
                    if (prefetchJob === job) prefetchJob = null
                }
            }
        prefetchJob = job
    }

    suspend fun load(
        limit: Int,
        seed: SongItem,
        playImmediately: Boolean,
        shouldCommit: () -> Boolean,
    ): SongItem? {
        fun canLoad() = autoLoadMore() && shouldCommit()
        if (!canLoad()) return null
        return mutex.withLock {
            if (!canLoad()) return@withLock null
            if (playImmediately && queue.currentIndex.value + 1 < queue.items.value.size) {
                return@withLock queue.getNext()?.also { onQueueChanged() }
            }
            if (seed.id.isBlank()) return@withLock null
            try {
                val classic = Settings.shared.classicRecommendations
                val buffered = if (classic) pendingClassicSongs else emptyList()
                val page =
                    if (buffered.size >= limit) {
                        null
                    } else {
                        val token = continuation
                        val firstPage = if (token == null) radio(seed.id) else continueRadio(token)
                        recommendationHistory.select(
                            firstPage = firstPage,
                            classic = classic,
                            filter = { songs ->
                                buildRadioQueueRecommendations(
                                    songs,
                                    queue.items.value.mapTo(mutableSetOf(), SongItem::id) + queuedIds + seed.id,
                                    QUEUE_SOURCE_AUTO_MIX,
                                    isBlocked,
                                    songsOnly = queue.recommendationsSongsOnly,
                                )
                            },
                            isCurrent = ::canLoad,
                            loadMore = continueRadio,
                        ) ?: return@withLock null
                    }
                if (page != null) continuation = page.continuation
                val available = buffered + (page?.songs ?: emptyList())
                val candidates = available.take(limit)
                pendingClassicSongs = if (classic) available.drop(limit) else emptyList()
                var changed = false
                for (song in candidates) {
                    if (song.id in queuedIds || queue.items.value.any { it.id == song.id }) continue
                    queue.addToQueue(song.copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX))
                    queuedIds.add(song.id)
                    changed = true
                }
                recommendationHistory.remember(candidates)
                val next = if (playImmediately) queue.getNext() else null
                if (changed || next != null) onQueueChanged()
                next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.w("PlayerState", "Failed to load recommendations (${error::class.simpleName})")
                null
            }
        }
    }
}
