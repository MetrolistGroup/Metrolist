package com.metrolist.desktop.storage

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal class DownloadLyricsEnricher(
    private val scope: CoroutineScope,
    private val cachedIds: suspend (List<String>) -> Set<String>,
    private val fetch: suspend (SongItem) -> Unit,
    private val isDownloaded: (String) -> Boolean,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    private val semaphore = Semaphore(1)

    suspend fun restore(songs: List<SongItem>) {
        songs.chunked(500).forEach { batch ->
            val completed = cachedIds(batch.map { it.id })
            batch.filter { it.id !in completed }.forEach(::enqueue)
        }
    }

    fun enqueue(song: SongItem) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                var retryDelay = 60_000L
                try {
                    while (isDownloaded(song.id)) {
                        val completed =
                            try {
                                semaphore.withPermit {
                                    if (song.id in cachedIds(listOf(song.id))) {
                                        true
                                    } else {
                                        fetch(song)
                                        song.id in cachedIds(listOf(song.id))
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.error("DownloadManager", "Lyrics remain pending for ${song.id}", e)
                                false
                            }
                        if (completed) break
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(3_600_000L)
                    }
                } finally {
                    jobs.remove(song.id, coroutineContext[Job])
                }
            }
        if (jobs.putIfAbsent(song.id, job) == null) job.start() else job.cancel()
    }

    fun cancel(ids: Set<String>) {
        cancelDownloadJobs(ids, jobs)
    }
}
