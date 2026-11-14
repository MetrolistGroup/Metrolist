/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.playback

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.IncompleteAudioDownloadException
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.storage.DownloadedPlaylists
import com.metrolist.shared.storage.trackedDownloadPlaylistId
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class SongDownloadManager(
    private val context: Context,
    private val audioCacheManager: AudioCacheManager,
    private val database: MusicDatabase,
) {
    private val activeDownloads = ConcurrentHashMap<String, DownloadTask>()
    private val queueLock = Any()
    private var cancellationRevision = 0L
    private val json = Json { ignoreUnknownKeys = true }

    private val requestState = MutableStateFlow<List<DownloadRequest>>(emptyList())
    internal val requests = requestState.asStateFlow()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val persistence =
        DownloadQueuePersistence(workerScope) { requests ->
            Settings.shared.downloadQueueJson = if (requests.isEmpty()) "" else json.encodeToString(requests)
        }

    private val queueRestored = workerScope.async { requestState.value = restoreQueue() }

    internal suspend fun flushQueue() {
        queueRestored.await()
        persistence.flush()
    }

    private fun readQueue(): List<DownloadRequest> = requestState.value

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun pauseAll() = pause(null)

    fun pause(songId: String?) =
        workerScope.launch {
            queueRestored.await()
            synchronized(queueLock) {
                writeQueue(changeDownloadQueueState(readQueue(), songId, DownloadQueueState.PAUSED))
                activeDownloads.values.filter { songId == null || it.songId == songId }.forEach { it.control.cancel() }
            }
            showAggregateNotification(force = true)
        }

    internal fun onServiceUnavailable() {
        synchronized(queueLock) {
            writeQueue(changeDownloadQueueState(readQueue(), null, DownloadQueueState.PAUSED))
            activeDownloads.values.forEach { it.control.cancel() }
        }
    }

    fun resumeAll() = resume(null)

    fun resume(songId: String?) =
        workerScope.launch {
            queueRestored.await()
            synchronized(queueLock) {
                writeQueue(changeDownloadQueueState(readQueue(), songId, DownloadQueueState.QUEUED))
            }
            showAggregateNotification(force = true)
            persistence.flush()
            DownloadService.start(context)
        }

    fun cancel(song: SongItem) =
        workerScope.launch {
            queueRestored.await()
            synchronized(queueLock) {
                cancellationRevision++
                writeQueue(cancelDownloadQueue(readQueue(), song))
                activeDownloads[song.id]?.control?.cancel()
            }
            showAggregateNotification(force = true)
        }

    suspend fun removeDownload(song: SongItem): Boolean {
        cancel(song).join()
        persistence.flush()
        return audioCacheManager.removeDownload(song.id)
    }

    suspend fun clearDownloads(): Boolean {
        synchronized(queueLock) {
            readTrackedPlaylistIds().forEach(DownloadedPlaylists::stop)
        }
        cancelAll().join()
        persistence.flush()
        return audioCacheManager.clearDownloads()
    }

    suspend fun download(song: SongItem): Boolean =
        withContext(Dispatchers.IO) {
            if (song.id.isBlank() || audioCacheManager.isDownloaded(song.id)) return@withContext false
            val added = enqueue(listOf(song))
            persistence.flush()
            if (added == 0) return@withContext false
            !hasRunnableDownloads() || DownloadService.start(context)
        }

    suspend fun downloadAll(songs: List<SongItem>): Int =
        withContext(Dispatchers.IO) {
            val downloadedIds = audioCacheManager.downloadedIds(songs.map { it.id })
            val validSongs =
                songs
                    .filter { it.id.isNotBlank() }
                    .distinctBy { it.id }
                    .filterNot { it.id in downloadedIds }
            val added = enqueue(validSongs)
            persistence.flush()
            if (added > 0 && hasRunnableDownloads() && !DownloadService.start(context)) return@withContext 0
            added
        }

    suspend fun downloadMissing(
        songs: List<SongItem>,
        playlistId: String,
    ): Int = enqueueMissingTracked(songs, playlistId, synchronized(queueLock) { cancellationRevision })

    fun observeTrackedPlaylists(scope: CoroutineScope) {
        DownloadedPlaylists.observe(scope, database) { id, songs -> downloadMissing(songs, id) }
    }

    fun trackPlaylist(playlistId: String) = synchronized(queueLock) { DownloadedPlaylists.track(playlistId) }

    fun stopTrackingPlaylist(playlistId: String) = synchronized(queueLock) { DownloadedPlaylists.stop(playlistId) }

    suspend fun syncTrackedPlaylists(): Boolean =
        withContext(Dispatchers.IO) {
            val revision = synchronized(queueLock) { cancellationRevision }
            DownloadedPlaylists.sync(database) { id, songs -> enqueueMissingTracked(songs, id, revision) }
        }

    private suspend fun enqueueMissingTracked(
        songs: List<SongItem>,
        playlistId: String,
        revision: Long,
    ): Int =
        withContext(Dispatchers.IO) {
            val downloadedIds = audioCacheManager.downloadedIds(songs.map { it.id })
            val missing = pendingPlaylistDownloads(songs, downloadedIds)
            val added =
                enqueue(missing, trackedPlaylistId = trackedDownloadPlaylistId(playlistId) ?: return@withContext 0, syncRevision = revision)
            if (added > 0) {
                persistence.flush()
                if (hasRunnableDownloads() && !DownloadService.start(context)) return@withContext 0
            }
            added
        }

    internal fun trackedPlaylistIds(): Set<String> = synchronized(queueLock) { readTrackedPlaylistIds() }

    fun isTrackingPlaylist(playlistId: String): Boolean = DownloadedPlaylists.contains(playlistId)

    private fun readTrackedPlaylistIds(): Set<String> = DownloadedPlaylists.ids()

    internal suspend fun resetInterruptedDownloads() {
        queueRestored.await()
        synchronized(queueLock) {
            writeQueue(
                readQueue().map {
                    if (it.state == DownloadQueueState.RUNNING) {
                        it.copy(state = DownloadQueueState.QUEUED, updatedAt = System.currentTimeMillis())
                    } else {
                        it
                    }
                },
            )
        }
    }

    internal suspend fun hasRunnableDownloads(): Boolean {
        queueRestored.await()
        return synchronized(queueLock) { readQueue().any { it.state.isRunnable } }
    }

    internal fun queuedRequests(): List<DownloadRequest> = synchronized(queueLock) { readQueue() }

    internal suspend fun processNext(resolve: suspend (SongItem, Boolean) -> PlaybackResource? = ::resolvePlaybackResource): Boolean =
        withContext(Dispatchers.IO) {
            queueRestored.await()
            val (request, task) =
                synchronized(queueLock) {
                    val next =
                        readQueue().filter { it.state == DownloadQueueState.QUEUED }.minByOrNull { it.enqueuedAt }
                            ?: return@withContext false
                    val task = DownloadTask(next.song.id, AudioDownloadControl())
                    if (activeDownloads.putIfAbsent(next.song.id, task) != null) return@withContext false
                    try {
                        updateRequestLocked(next.song.id) {
                            it.copy(state = DownloadQueueState.RUNNING, updatedAt = System.currentTimeMillis(), lastError = null)
                        }
                    } catch (e: Throwable) {
                        activeDownloads.remove(next.song.id, task)
                        throw e
                    }
                    next to task
                }
            val song = request.song
            var transferStarted = false
            try {
                val currentState = queuedRequests().firstOrNull { it.song.id == song.id }?.state
                if (currentState == null || currentState == DownloadQueueState.CANCELLED || currentState == DownloadQueueState.PAUSED) {
                    return@withContext true
                }
                if (audioCacheManager.isDownloaded(song.id)) {
                    updateRequest(song.id) { it.copy(state = DownloadQueueState.COMPLETED, updatedAt = System.currentTimeMillis()) }
                    return@withContext true
                }
                showAggregateNotification(force = true)
                transferStarted = true
                downloadWithResourceRefresh(
                    resolve = { refresh -> resolve(song, refresh) },
                    isCancelled = { task.control.isCancelled },
                ) { resource ->
                    audioCacheManager.downloadSong(
                        song = song,
                        resource = resource,
                        control = task.control,
                    ) { downloadedBytes, totalBytes ->
                        task.downloadedBytes = downloadedBytes
                        task.totalBytes = totalBytes
                        publishProgress(task)
                        showAggregateNotification()
                    }
                }
                synchronized(queueLock) {
                    if (!task.control.isCancelled) {
                        updateRequestLocked(song.id) {
                            it.copy(
                                state = DownloadQueueState.COMPLETED,
                                downloadedBytes = task.downloadedBytes,
                                totalBytes = task.totalBytes,
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    showAggregateNotification(force = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                synchronized(queueLock) {
                    if (!task.control.isCancelled) {
                        updateRequestLocked(song.id) {
                            it.copy(
                                state = DownloadQueueState.FAILED,
                                updatedAt = System.currentTimeMillis(),
                                attemptCount = it.attemptCount + 1,
                                lastError = downloadFailureCode(e).name,
                            )
                        }
                    }
                    showAggregateNotification(force = true)
                    Timber.tag(TAG).w(e, "download failed songId=%s", song.id)
                }
            } finally {
                val removeCancelledDownload =
                    synchronized(queueLock) {
                        if (transferStarted && task.control.isCancelled) {
                            true
                        } else {
                            activeDownloads.remove(song.id, task)
                            false
                        }
                    }
                if (removeCancelledDownload) {
                    try {
                        withContext(NonCancellable) { audioCacheManager.removeDownload(song.id) }
                    } finally {
                        synchronized(queueLock) { activeDownloads.remove(song.id, task) }
                    }
                }
            }
            true
        }

    fun retryFailed() = retryFailed(null)

    fun retryFailed(songId: String?) =
        workerScope.launch {
            queueRestored.await()
            synchronized(queueLock) {
                writeQueue(retryDownloadQueue(readQueue(), songId))
            }
            persistence.flush()
            DownloadService.start(context)
        }

    fun dismissFailed() =
        workerScope.launch {
            queueRestored.await()
            synchronized(queueLock) { writeQueue(readQueue().filterNot { it.state == DownloadQueueState.FAILED }) }
        }

    fun cancelAll() =
        workerScope.launch {
            queueRestored.await()
            synchronized(queueLock) {
                cancellationRevision++
                // shortcut: Cancelled IDs stay durable; prune them if queue storage grows too large.
                writeQueue(cancelDownloadQueue(readQueue()))
                activeDownloads.values.forEach { it.control.cancel() }
            }
        }

    private suspend fun enqueue(
        songs: List<SongItem>,
        trackedPlaylistId: String? = null,
        syncRevision: Long? = null,
    ): Int {
        queueRestored.await()
        if (songs.isEmpty()) return 0
        val added =
            synchronized(queueLock) {
                if (trackedPlaylistId != null && (trackedPlaylistId !in readTrackedPlaylistIds() || syncRevision != cancellationRevision)) {
                    return 0
                }
                val queue = readQueue()
                val starts = queue.filterNot { it.state == DownloadQueueState.CANCELLED }.map { it.state }.isTerminalBatch()
                val retained = if (starts) queue.filter { it.state == DownloadQueueState.CANCELLED } else queue
                val merged =
                    if (trackedPlaylistId == null) {
                        mergeDownloadQueue(retained, songs, System.currentTimeMillis())
                    } else {
                        mergeTrackedPlaylistDownloads(retained, songs, System.currentTimeMillis())
                    }
                if (merged == retained) return 0
                writeQueue(merged)
                val previous = retained.associateBy { it.song.id }
                merged.count { it != previous[it.song.id] }
            }
        return added
    }

    private fun publishProgress(task: DownloadTask) {
        val now = System.currentTimeMillis()
        if (now - task.lastProgressAt < PROGRESS_UPDATE_THROTTLE_MS) return
        task.lastProgressAt = now
        synchronized(queueLock) {
            if (!task.control.isCancelled) {
                requestState.value =
                    updateDownloadQueue(readQueue(), task.songId) {
                        it.copy(downloadedBytes = task.downloadedBytes, totalBytes = task.totalBytes, updatedAt = now)
                    }
            }
        }
    }

    private fun updateRequest(
        songId: String,
        transform: (DownloadRequest) -> DownloadRequest,
    ) {
        synchronized(queueLock) { updateRequestLocked(songId, transform) }
    }

    private fun updateRequestLocked(
        songId: String,
        transform: (DownloadRequest) -> DownloadRequest,
    ) {
        val queue = readQueue()
        val updated = updateDownloadQueue(queue, songId, transform)
        if (updated !== queue) writeQueue(updated)
    }

    private fun restoreQueue(): List<DownloadRequest> =
        try {
            Settings.shared.downloadQueueJson
                .takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString<List<DownloadRequest>>(it) }
                .orEmpty()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "failed to read durable download queue")
            emptyList()
        }

    private fun writeQueue(requests: List<DownloadRequest>) {
        requestState.value = requests
        persistence.submit(requests)
        updateCompletionNotification(requests)
        if (requests.any { it.state == DownloadQueueState.PAUSED } && requests.none { it.state.isRunnable }) {
            notify(PAUSED_NOTIFICATION_ID, aggregateNotification())
        } else {
            NotificationManagerCompat.from(context).cancel(PAUSED_NOTIFICATION_ID)
        }
    }

    private suspend fun resolvePlaybackResource(
        song: SongItem,
        refresh: Boolean,
    ): PlaybackResource? {
        val resolver = GlobalYouTubeRepository.streamResolver ?: return null
        val hints = song.toContentHints().forAudioDownload(preferSabr = refresh)
        val quality = playbackAudioQuality()
        return resolver.resolveRemote(song.id, hints, quality, sourceSong = song, refresh = refresh)
    }

    internal fun aggregateNotification(): Notification {
        val requests = queuedRequests().filterNot { it.state == DownloadQueueState.CANCELLED }
        val completedCount = requests.count { it.state == DownloadQueueState.COMPLETED }
        val failedCount = requests.count { it.state == DownloadQueueState.FAILED }
        val running = requests.firstOrNull { it.state == DownloadQueueState.RUNNING }
        val activeTask = running?.let { activeDownloads[it.song.id] }
        val paused = requests.any { it.state == DownloadQueueState.PAUSED } && running == null
        val currentPercent =
            activeTask?.totalBytes?.takeIf { it > 0L }?.let {
                ((activeTask.downloadedBytes * 100) / it).coerceIn(0L, 100L).toInt()
            } ?: 0
        val processedProgress = (completedCount + failedCount) * 100 + currentPercent
        val maxProgress = (requests.size * 100).coerceAtLeast(100)
        val indeterminate = running != null && activeTask?.totalBytes == null
        val downloadingTitle = running?.song?.title?.takeIf { it.isNotBlank() }
        val action = if (paused) ACTION_RESUME_ALL else ACTION_PAUSE_ALL
        val actionText = if (paused) R.string.download_resume_all else R.string.download_pause_all
        val actionIcon = if (paused) R.drawable.ic_notification_play else R.drawable.ic_notification_pause

        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                downloadingTitle
                    ?: context.getString(
                        if (paused) R.string.download_notification_paused else R.string.download_notification_title,
                    ),
            ).setContentText(
                context.resources.getQuantityString(
                    R.plurals.download_notification_progress,
                    requests.size,
                    completedCount,
                    requests.size,
                    failedCount,
                ),
            ).setSubText(downloadingTitle?.let { context.getString(R.string.download_notification_title) })
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                maxProgress,
                processedProgress,
                indeterminate,
            ).addAction(actionIcon, context.getString(actionText), actionPendingIntent(action))
            .addAction(
                R.drawable.ic_notification_close,
                context.getString(R.string.download_cancel_all),
                actionPendingIntent(ACTION_CANCEL_ALL),
            ).build()
    }

    private fun showAggregateNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        if (queuedRequests().any { it.state.isRunnable }) notify(NOTIFICATION_ID, aggregateNotification())
    }

    private fun updateCompletionNotification(requests: List<DownloadRequest>) {
        val summary = downloadCompletionSummary(requests)
        if (summary == null) {
            NotificationManagerCompat.from(context).cancel(COMPLETION_NOTIFICATION_ID)
            return
        }
        val (completedCount, failedCount) = summary
        notify(
            COMPLETION_NOTIFICATION_ID,
            NotificationCompat
                .Builder(context, COMPLETION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    context.getString(
                        if (failedCount == 0) R.string.downloads_completed else R.string.downloads_finished,
                    ),
                ).setContentText(context.getString(R.string.download_completion_summary, completedCount, failedCount))
                .setContentIntent(openAppPendingIntent(completed = failedCount == 0))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notify(
        notificationId: Int,
        notification: Notification,
    ) {
        if (!hasNotificationPermission()) return
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "download notification permission denied")
        }
    }

    private fun openAppPendingIntent(completed: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                if (completed) {
                    action = MainActivity.ACTION_OPEN_WIDGET_TARGET
                    putExtra(MainActivity.EXTRA_WIDGET_TARGET_TYPE, "AUTO_PLAYLIST")
                    putExtra(MainActivity.EXTRA_WIDGET_TARGET_ID, "downloaded")
                } else {
                    action = MainActivity.ACTION_DOWNLOADS
                }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun actionPendingIntent(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, DownloadNotificationReceiver::class.java)
                .setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private data class DownloadTask(
        val songId: String,
        val control: AudioDownloadControl,
        var downloadedBytes: Long = 0L,
        var totalBytes: Long? = null,
        var lastProgressAt: Long = 0L,
    )

    companion object {
        const val CHANNEL_ID = "downloads"
        const val COMPLETION_CHANNEL_ID = "downloads_completed"
        const val ACTION_PAUSE_ALL = "com.metrolist.android.action.DOWNLOAD_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.metrolist.android.action.DOWNLOAD_RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.metrolist.android.action.DOWNLOAD_CANCEL_ALL"
        const val NOTIFICATION_ID = 19_999
        private const val COMPLETION_NOTIFICATION_ID = 20_000
        internal const val PAUSED_NOTIFICATION_ID = 20_001
        private const val TAG = "SongDownloadManager"
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val PROGRESS_UPDATE_THROTTLE_MS = 1_000L
        private var lastNotificationAt = 0L
    }
}

internal fun ContentHints.forAudioDownload(preferSabr: Boolean = false): ContentHints =
    copy(wantVideo = false, playbackClientOverrideId = null, sabrFirst = preferSabr)
        .withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true)

internal suspend fun <T : Any> downloadWithResourceRefresh(
    resolve: suspend (refresh: Boolean) -> T?,
    isCancelled: () -> Boolean = { false },
    download: suspend (T) -> Unit,
) {
    var lastFailure: Exception? = null
    repeat(DOWNLOAD_RESOURCE_ATTEMPTS) { attempt ->
        val resource =
            try {
                requireNotNull(resolve(attempt > 0)) { "No playback resource available" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCancelled()) throw e
                lastFailure = e
                return@repeat
            }
        try {
            download(resource)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCancelled() || e !is DownloadTransferException) throw e
            lastFailure = e
        }
    }
    throw checkNotNull(lastFailure)
}

internal fun trackablePlaylistId(playlistId: String): String? = trackedDownloadPlaylistId(playlistId)

private const val DOWNLOAD_RESOURCE_ATTEMPTS = 2

internal fun pendingPlaylistDownloads(
    songs: List<SongItem>,
    downloadedIds: Set<String>,
): List<SongItem> =
    songs
        .filter {
            !it.isUnavailable && !it.isLocal && it.id.isNotBlank() && it.id !in downloadedIds
        }.distinctBy(SongItem::id)

internal fun cancelDownloadQueue(
    queue: List<DownloadRequest>,
    song: SongItem? = null,
): List<DownloadRequest> {
    val updated =
        queue.map {
            if ((song == null && it.state != DownloadQueueState.COMPLETED) ||
                it.song.id == song?.id
            ) {
                it.copy(state = DownloadQueueState.CANCELLED)
            } else {
                it
            }
        }
    return if (song != null && queue.none { it.song.id == song.id }) {
        updated + DownloadRequest(song, state = DownloadQueueState.CANCELLED, enqueuedAt = System.currentTimeMillis())
    } else {
        updated
    }
}

internal fun updateDownloadQueue(
    queue: List<DownloadRequest>,
    songId: String,
    transform: (DownloadRequest) -> DownloadRequest,
): List<DownloadRequest> {
    val index = queue.indexOfFirst { it.song.id == songId }
    if (index < 0 || queue[index].state == DownloadQueueState.CANCELLED || queue[index].state == DownloadQueueState.PAUSED) return queue
    return queue.toMutableList().also { it[index] = transform(it[index]) }
}

internal fun changeDownloadQueueState(
    queue: List<DownloadRequest>,
    songId: String?,
    target: DownloadQueueState,
): List<DownloadRequest> =
    queue.map { request ->
        val eligible = if (target == DownloadQueueState.PAUSED) request.state.isRunnable else request.state == DownloadQueueState.PAUSED
        if ((songId == null || request.song.id == songId) && eligible) {
            request.copy(
                state = target,
                updatedAt = System.currentTimeMillis(),
                downloadedBytes = if (target == DownloadQueueState.QUEUED) 0L else request.downloadedBytes,
                totalBytes = if (target == DownloadQueueState.QUEUED) null else request.totalBytes,
            )
        } else {
            request
        }
    }

internal fun retryDownloadQueue(
    queue: List<DownloadRequest>,
    songId: String?,
): List<DownloadRequest> =
    queue.map { request ->
        if (request.state == DownloadQueueState.FAILED && (songId == null || request.song.id == songId)) {
            request.copy(
                state = DownloadQueueState.QUEUED,
                updatedAt = System.currentTimeMillis(),
                downloadedBytes = 0L,
                totalBytes = null,
                lastError = null,
            )
        } else {
            request
        }
    }

internal fun mergeTrackedPlaylistDownloads(
    queue: List<DownloadRequest>,
    songs: List<SongItem>,
    enqueuedAt: Long,
): List<DownloadRequest> {
    val blockedIds =
        queue
            .filter { it.state == DownloadQueueState.PAUSED || it.state == DownloadQueueState.CANCELLED }
            .mapTo(mutableSetOf()) { it.song.id }
    val eligible = songs.filterNot { it.id in blockedIds }
    val merged = mergeDownloadQueue(queue, eligible, enqueuedAt)
    if (queue.none { it.state == DownloadQueueState.PAUSED }) return merged
    val newIds = eligible.mapTo(mutableSetOf()) { it.id }
    return merged.map {
        if (it.song.id in newIds &&
            it.state == DownloadQueueState.QUEUED
        ) {
            it.copy(state = DownloadQueueState.PAUSED)
        } else {
            it
        }
    }
}

internal fun mergeDownloadQueue(
    queue: List<DownloadRequest>,
    songs: List<SongItem>,
    enqueuedAt: Long,
): List<DownloadRequest> {
    val requests = queue.toMutableList()
    val indexes = mutableMapOf<String, Int>()
    requests.forEachIndexed { index, request -> indexes.putIfAbsent(request.song.id, index) }
    songs.filterNot { it.isUnavailable }.forEach { song ->
        val index = indexes[song.id]
        val request = DownloadRequest(song = song, enqueuedAt = enqueuedAt)
        if (index == null) {
            indexes[song.id] = requests.size
            requests += request
        } else if (!requests[index].state.isRunnable) {
            requests[index] = request
        }
    }
    return requests
}

@Serializable
internal data class DownloadRequest(
    val song: SongItem,
    val state: DownloadQueueState = DownloadQueueState.QUEUED,
    val enqueuedAt: Long,
    val updatedAt: Long = enqueuedAt,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Serializable
internal enum class DownloadQueueState {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED,
    CANCELLED,
}

internal val DownloadQueueState.isRunnable: Boolean
    get() = this == DownloadQueueState.QUEUED || this == DownloadQueueState.RUNNING

internal val DownloadQueueState.isTerminal: Boolean
    get() = this == DownloadQueueState.FAILED || this == DownloadQueueState.COMPLETED

internal fun Iterable<DownloadQueueState>.isTerminalBatch(): Boolean = any() && all { it.isTerminal }

internal fun downloadCompletionSummary(queue: List<DownloadRequest>): Pair<Int, Int>? {
    val requests = queue.filterNot { it.state == DownloadQueueState.CANCELLED }
    if (!requests.map { it.state }.isTerminalBatch()) return null
    val completed = requests.count { it.state == DownloadQueueState.COMPLETED }
    return completed to (requests.size - completed)
}

internal enum class DownloadFailureCode { STORAGE_FULL, STORAGE_ACCESS, NETWORK, INCOMPLETE, OTHER }

internal fun downloadFailureCode(error: Throwable): DownloadFailureCode {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    return when {
        causes.any {
            it is InsufficientAudioStorageException || (it is ErrnoException && it.errno == OsConstants.ENOSPC)
        } -> DownloadFailureCode.STORAGE_FULL

        causes.any { it is SecurityException } -> DownloadFailureCode.STORAGE_ACCESS

        causes.any { it is SocketException || it is SocketTimeoutException || it is UnknownHostException } -> DownloadFailureCode.NETWORK

        causes.any {
            it is IncompleteAudioDownloadException || it is SabrProtocolException ||
                (it is StreamResolveException && it.reason == StreamResolveException.Reason.NO_PLAYABLE_STREAM)
        } -> DownloadFailureCode.INCOMPLETE

        else -> DownloadFailureCode.OTHER
    }
}
