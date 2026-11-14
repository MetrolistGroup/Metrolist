package com.metrolist.shared.playback

import androidx.compose.runtime.snapshotFlow
import com.metrolist.innertubex.extraction.PlaybackTrackingData
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.YouTubeRepository.PlaybackTrackingSession
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.TimeSource

class YouTubePlaybackReporter internal constructor(
    private val scope: CoroutineScope,
    private val repository: YouTubeRepository,
    private val monotonicTimeMs: () -> Long = { monotonicTimeOrigin.elapsedNow().inWholeMilliseconds },
    private val epochTimeMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val replayDelay: suspend (Long) -> Unit = { delay(it) },
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val onPingResult: (PlaybackReporterPingResult) -> Unit = {},
) {
    internal sealed interface Ping {
        data class Start(
            val currentMediaTimeMs: Long,
        ) : Ping

        data class Watchtime(
            val segments: WatchTimeSegments.Drained,
            val currentMediaTimeMs: Long,
            val realTimeMs: Long,
            val final: Boolean,
        ) : Ping
    }

    private inner class Session(
        var playbackInstanceId: Long,
        val reportingGeneration: Long,
        val videoId: String,
        val clientName: String,
        val clientPlaybackNonce: String,
        val initialTracking: PlaybackTrackingData?,
        var format: Int?,
    ) {
        val pendingGeneration = PendingYouTubePlaybackReportStore.generation
        val playbackSession = repository.playbackTrackingSession()
        val accountKey = repository.playbackReportingAccountKey(playbackSession)
        val startedAtMs = monotonicTimeMs()
        val segments = WatchTimeSegments()
        val pings = Channel<Ping>(Channel.UNLIMITED)
        var schedule = initialTracking?.toSchedule() ?: WatchTimeSchedule(null, null)
        var deferred = false
        var lastAcknowledgedOffsetMs = 0L
        var lastCheckpointMs = 0L
        var nextScheduledFlush = 0
        var accumulating = false
        var wasRendering = false
        var finished = false
        var lastRenderingPositionMs: Long? = null
        var lastRenderingSampleAtMs: Long? = null

        fun realTimeMs(): Long = (monotonicTimeMs() - startedAtMs).coerceAtLeast(0L)
    }

    private val lock = ReentrantLock()
    private var session: Session? = null
    private var reportingEnabled = true
    private var reportingGeneration = 0L
    private val consumerJobs = mutableSetOf<Job>()

    init {
        startPendingDrain()
    }

    fun setReportingEnabled(enabled: Boolean) {
        lock.withLock {
            if (reportingEnabled == enabled) return
            reportingEnabled = enabled
            reportingGeneration++
            Logger.i(REPORTING_TAG, "reporting enabled=$enabled generation=$reportingGeneration")
            if (!enabled) session?.let { finishLocked(it, endPositionMs = null) }
        }
    }

    fun onPlaybackSample(
        playbackInstanceId: Long,
        resource: PlaybackResource?,
        positionMs: Long,
        isRendering: Boolean,
        wantsPlayback: Boolean,
        playbackSpeed: Float,
    ) {
        lock.withLock {
            if (!reportingEnabled) return
            if (!repository.isPlaybackReportingEnabled()) {
                session?.let {
                    reportingGeneration++
                    finishLocked(it, endPositionMs = null)
                }
                return
            }
            val current = session
            if (current != null &&
                (current.playbackInstanceId != playbackInstanceId || current.accountKey != repository.playbackReportingAccountKey())
            ) {
                finishLocked(current, endPositionMs = null)
            }

            var active = session
            if (active == null) {
                val resource = resource ?: return
                val tracking = resource.playbackTracking
                if (tracking == null && !resource.allowPlaybackTrackingFallback) return
                if (!isRendering || playbackInstanceId <= 0L) return
                val newSession =
                    Session(
                        playbackInstanceId = playbackInstanceId,
                        reportingGeneration = reportingGeneration,
                        videoId = resource.videoId,
                        clientName = resource.clientName,
                        clientPlaybackNonce = tracking?.clientPlaybackNonce ?: generateClientPlaybackNonce(),
                        initialTracking = tracking,
                        format = resource.itag,
                    )
                newSession.accumulating = true
                newSession.wasRendering = true
                newSession.segments.onPlay(positionMs)
                newSession.lastRenderingPositionMs = positionMs
                newSession.lastRenderingSampleAtMs = monotonicTimeMs()
                session = newSession
                newSession.pendingId()?.let { pendingDrainLock.withLock { activePendingIds += it } }
                newSession.pings.trySend(Ping.Start(positionMs.coerceAtLeast(0L)))
                val consumer = scope.launch { consume(newSession) }
                consumerJobs += consumer
                consumer.invokeOnCompletion {
                    lock.withLock { consumerJobs -= consumer }
                }
                active = newSession
            }

            if (!wantsPlayback || !isRendering) {
                interruptLocked(active, positionMs)
                return
            }
            if (!active.wasRendering) skipOverdueFlushes(active)
            if (!active.accumulating) {
                active.segments.onPlay(positionMs)
                active.accumulating = true
            } else if (hasPositionDiscontinuity(active, positionMs, playbackSpeed)) {
                active.segments.onSeek(active.segments.lastKnownPositionMs(), positionMs, continuePlaying = true)
                enqueueWatchtimeLocked(active, positionMs, final = false)
            } else {
                active.segments.onProgress(positionMs)
            }
            active.wasRendering = true
            active.lastRenderingPositionMs = positionMs
            active.lastRenderingSampleAtMs = monotonicTimeMs()
            flushScheduledWatchtime(active, positionMs)
        }
    }

    fun onSeek(
        playbackInstanceId: Long,
        fromPositionMs: Long,
        toPositionMs: Long,
        continuePlaying: Boolean,
    ) {
        lock.withLock {
            val active = session?.takeIf { it.playbackInstanceId == playbackInstanceId } ?: return
            val keepAccumulating = continuePlaying && active.wasRendering
            active.segments.onSeek(fromPositionMs, toPositionMs, keepAccumulating)
            active.accumulating = keepAccumulating
            active.lastRenderingPositionMs = null
            active.lastRenderingSampleAtMs = null
            if (abs(toPositionMs - fromPositionMs) >= REAL_SEEK_MIN_MS) {
                enqueueWatchtimeLocked(active, toPositionMs, final = false)
            }
        }
    }

    fun onPlaybackInterrupted(
        playbackInstanceId: Long,
        positionMs: Long,
    ) {
        lock.withLock {
            val active = session?.takeIf { it.playbackInstanceId == playbackInstanceId } ?: return
            interruptLocked(active, positionMs)
        }
    }

    fun onPlaybackRecovered(
        previousPlaybackInstanceId: Long,
        playbackInstanceId: Long,
        positionMs: Long,
        format: Int?,
    ) {
        lock.withLock {
            val active = session?.takeIf { it.playbackInstanceId == previousPlaybackInstanceId } ?: return
            interruptLocked(active, positionMs)
            active.playbackInstanceId = playbackInstanceId
            active.format = format
        }
    }

    fun onPlaybackEnded(
        playbackInstanceId: Long,
        positionMs: Long,
    ) {
        lock.withLock {
            val active = session?.takeIf { it.playbackInstanceId == playbackInstanceId } ?: return
            finishLocked(active, positionMs)
        }
    }

    fun onPlaybackStopped(
        playbackInstanceId: Long,
        positionMs: Long,
    ) {
        lock.withLock {
            val active = session?.takeIf { it.playbackInstanceId == playbackInstanceId } ?: return
            finishLocked(active, positionMs)
        }
    }

    suspend fun awaitPendingPings() {
        lock.withLock { consumerJobs.toList() }.joinAll()
        pendingDrainLock.withLock { pendingDrainAttempt }?.await()
    }

    private fun flushScheduledWatchtime(
        active: Session,
        positionMs: Long,
    ) {
        if (active.deferred) {
            if (active.realTimeMs() - active.lastCheckpointMs >= OFFLINE_CHECKPOINT_MS) {
                active.lastCheckpointMs = active.realTimeMs()
                enqueueWatchtimeLocked(active, positionMs, final = false)
            }
            return
        }
        val dueAt = active.schedule.flushOffsetMs(active.nextScheduledFlush) ?: return
        if (active.realTimeMs() < dueAt) return
        active.nextScheduledFlush++
        enqueueWatchtimeLocked(active, positionMs, final = false)
    }

    private fun skipOverdueFlushes(active: Session) {
        while (true) {
            val dueAt = active.schedule.flushOffsetMs(active.nextScheduledFlush) ?: return
            if (dueAt > active.realTimeMs()) return
            active.nextScheduledFlush++
        }
    }

    private fun interruptLocked(
        active: Session,
        positionMs: Long,
    ) {
        if (active.accumulating) {
            active.segments.onPause(positionMs)
            active.accumulating = false
            enqueueWatchtimeLocked(active, positionMs, final = false)
        }
        active.wasRendering = false
        active.lastRenderingPositionMs = null
        active.lastRenderingSampleAtMs = null
    }

    private fun hasPositionDiscontinuity(
        active: Session,
        positionMs: Long,
        playbackSpeed: Float,
    ): Boolean {
        val previousPositionMs = active.lastRenderingPositionMs ?: return false
        val previousSampleMs = active.lastRenderingSampleAtMs ?: return false
        return isPlaybackPositionDiscontinuity(
            previousPositionMs = previousPositionMs,
            positionMs = positionMs,
            elapsedRealtimeMs = (monotonicTimeMs() - previousSampleMs).coerceAtLeast(0L),
            playbackSpeed = playbackSpeed,
        )
    }

    private fun enqueueWatchtimeLocked(
        active: Session,
        currentMediaTimeMs: Long,
        final: Boolean,
    ) {
        val drained =
            active.segments.drain(
                currentPositionMs = currentMediaTimeMs,
                continuePlaying = active.accumulating && !final,
            ) ?: return
        active.pings.trySend(
            Ping.Watchtime(
                segments = drained,
                currentMediaTimeMs = currentMediaTimeMs.coerceAtLeast(0L),
                realTimeMs = active.realTimeMs(),
                final = final,
            ),
        )
    }

    private fun finishLocked(
        active: Session,
        endPositionMs: Long?,
    ) {
        if (active.finished) return
        active.finished = true
        if (session === active) session = null
        val lastKnownPositionMs = active.segments.lastKnownPositionMs()
        val end = endPositionMs?.takeIf { it >= 0L }?.coerceAtLeast(lastKnownPositionMs) ?: lastKnownPositionMs
        active.segments.onPause(end)
        active.accumulating = false
        val drained = active.segments.drain(end, continuePlaying = false)
        active.pings.trySend(
            Ping.Watchtime(
                segments =
                    drained
                        ?: WatchTimeSegments.Drained(
                            startSeconds = WatchTimeSegments.formatSeconds(end),
                            endSeconds = WatchTimeSegments.formatSeconds(end),
                            segments = emptyList(),
                        ),
                currentMediaTimeMs = end,
                realTimeMs = active.realTimeMs(),
                final = true,
            ),
        )
        active.pings.close()
    }

    private suspend fun persist(
        active: Session,
        ping: Ping,
    ) {
        if (!Settings.shared.queueYouTubeHistoryWhenOffline || !isReportingEnabled(active)) return
        val accountKey = active.accountKey ?: return
        val pending = ping.toPending()
        val appended =
            PendingYouTubePlaybackReportStore.append(
                PendingYouTubePlaybackReport(
                    id = accountKey + ":" + active.clientPlaybackNonce,
                    accountKey = accountKey,
                    videoId = active.videoId,
                    clientPlaybackNonce = active.clientPlaybackNonce,
                    format = lock.withLock { active.format },
                    pings = emptyList(),
                    lastAcknowledgedOffsetMs = active.lastAcknowledgedOffsetMs,
                ),
                pending,
                expectedGeneration = active.pendingGeneration,
            )
        if (!appended) {
            Logger.i(
                REPORTING_TAG,
                "pending report rejected: queue invalidated or capacity reached",
            )
        }
    }

    private suspend fun consume(active: Session) {
        try {
            val initial = active.initialTracking?.takeIf { active.clientName == YouTubeClient.WEB_REMIX.clientName && isFresh(it) }
            val tracking =
                if (!isReportingEnabled(active)) {
                    Logger.i(REPORTING_TAG, "suppressed phase=tracking generation=${active.reportingGeneration}")
                    null
                } else {
                    initial
                        ?: try {
                            repository.getPlaybackTracking(
                                videoId = active.videoId,
                                clientPlaybackNonce = active.clientPlaybackNonce,
                                playbackSession = active.playbackSession,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                }
            if (tracking != null) {
                lock.withLock {
                    if (!active.finished) active.schedule = tracking.toSchedule()
                }
            }

            var opened = false
            lock.withLock { active.deferred = tracking?.playbackUrl == null }
            for (ping in active.pings) {
                if (!isReportingEnabled(active)) {
                    if (active.accountKey == repository.playbackReportingAccountKey()) {
                        active.pendingId()?.let { PendingYouTubePlaybackReportStore.remove(it) }
                    }
                    onPingResult(ping.result(active.clientPlaybackNonce, success = false))
                    continue
                }
                if (lock.withLock { active.deferred }) {
                    persist(active, ping)
                    onPingResult(ping.result(active.clientPlaybackNonce, success = false))
                    continue
                }
                val success =
                    try {
                        when (ping) {
                            is Ping.Start -> {
                                repository
                                    .registerPlayback(
                                        playbackTrackingUrl = tracking?.playbackUrl ?: continue,
                                        clientPlaybackNonce = active.clientPlaybackNonce,
                                        currentMediaTime = WatchTimeSegments.formatSeconds(ping.currentMediaTimeMs),
                                        format = lock.withLock { active.format },
                                        playbackSession = active.playbackSession,
                                    ).also { opened = it }
                            }

                            is Ping.Watchtime -> {
                                val watchtimeUrl = tracking?.watchtimeUrl
                                opened && watchtimeUrl != null &&
                                    repository.registerWatchtime(
                                        watchtimeTrackingUrl = watchtimeUrl,
                                        clientPlaybackNonce = active.clientPlaybackNonce,
                                        startTimes = ping.segments.startSeconds,
                                        endTimes = ping.segments.endSeconds,
                                        currentMediaTime = WatchTimeSegments.formatSeconds(ping.currentMediaTimeMs),
                                        realTime = WatchTimeSegments.formatSeconds(ping.realTimeMs),
                                        final = ping.final,
                                        format = lock.withLock { active.format },
                                        playbackSession = active.playbackSession,
                                    )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                Logger.i(
                    REPORTING_TAG,
                    "${ping.logType()} outcome=${if (success) "success" else "failure"} generation=${active.reportingGeneration}",
                )
                onPingResult(ping.result(active.clientPlaybackNonce, success))
                if (success) {
                    active.lastAcknowledgedOffsetMs = ping.toPending().offsetMs
                } else {
                    lock.withLock { active.deferred = true }
                    persist(active, ping)
                }
            }
        } finally {
            active.pings.close()
            active.pendingId()?.let { pendingDrainLock.withLock { activePendingIds -= it } }
            if (scope.coroutineContext[Job]?.isActive == true) startPendingDrain()
        }
    }

    private fun isReportingEnabled(active: Session): Boolean =
        lock.withLock { reportingEnabled && active.reportingGeneration == reportingGeneration } &&
            repository.isPlaybackReportingEnabled() &&
            active.pendingGeneration == PendingYouTubePlaybackReportStore.generation &&
            if (active.accountKey != null) {
                active.accountKey == repository.playbackReportingAccountKey()
            } else {
                repository.isPlaybackTrackingSessionCurrent(active.playbackSession)
            }

    private fun isFresh(tracking: PlaybackTrackingData): Boolean {
        val ageMs = epochTimeMs() - tracking.resolvedAtEpochMs
        return ageMs in 0L..TRACKING_MAX_AGE_MS && tracking.playbackUrl != null
    }

    private fun PlaybackTrackingData.toSchedule() =
        WatchTimeSchedule(
            scheduledSeconds = scheduledFlushWalltimeSeconds,
            defaultIntervalSeconds = defaultFlushIntervalSeconds,
        )

    private fun Session.pendingId(): String? = accountKey?.let { it + ":" + clientPlaybackNonce }

    private fun Ping.toPending(): PendingYouTubePlaybackPing =
        when (this) {
            is Ping.Start -> {
                PendingYouTubePlaybackPing.Start(0L, currentMediaTimeMs)
            }

            is Ping.Watchtime -> {
                PendingYouTubePlaybackPing.Watchtime(
                    offsetMs = realTimeMs,
                    startSeconds = segments.startSeconds,
                    endSeconds = segments.endSeconds,
                    currentMediaTimeMs = currentMediaTimeMs,
                    realTimeMs = realTimeMs,
                    final = final,
                    segments = segments.segments,
                )
            }
        }

    private fun startPendingDrain() {
        pendingDrainLock.withLock {
            if (pendingDrainJob?.isActive == true) return
            val settings = Settings.shared
            val firstAttempt = CompletableDeferred<Unit>()
            pendingDrainAttempt = firstAttempt
            pendingDrainJob =
                scope.launch {
                    val changes = Channel<Unit>(Channel.CONFLATED)
                    val observer =
                        launch {
                            combine(
                                PendingYouTubePlaybackReportStore.changes,
                                PlaybackNetworkStateStore.state,
                                snapshotFlow {
                                    with(settings) {
                                        listOf(
                                            queueYouTubeHistoryWhenOffline,
                                            pauseListenHistory,
                                            ytmSync,
                                            innerTubeCookie,
                                            dataSyncId,
                                            innerTubeAuthUser,
                                        )
                                    }
                                },
                            ) { _, _, _ -> Unit }.collect { changes.trySend(Unit) }
                        }
                    var attempt = firstAttempt
                    try {
                        while (true) {
                            val nextDelay = drainPending()
                            attempt.complete(Unit)
                            if (nextDelay == null) {
                                changes.receive()
                            } else {
                                coroutineScope {
                                    val deadline = launch { retryDelay(nextDelay) }
                                    try {
                                        select<Unit> {
                                            changes.onReceive { }
                                            deadline.onJoin { }
                                        }
                                    } finally {
                                        deadline.cancel()
                                    }
                                }
                            }
                            attempt = CompletableDeferred()
                            pendingDrainLock.withLock { pendingDrainAttempt = attempt }
                        }
                    } finally {
                        observer.cancel()
                        changes.close()
                        attempt.complete(Unit)
                    }
                }
        }
    }

    private suspend fun drainPending(): Long? {
        if (!PlaybackNetworkStateStore.current.connected) return null
        val accountKey = repository.playbackReportingAccountKey() ?: return null
        if (!Settings.shared.queueYouTubeHistoryWhenOffline || !repository.isPlaybackReportingEnabled()) return null
        for (report in PendingYouTubePlaybackReportStore.all()) {
            if (report.accountKey != accountKey) continue
            if (report.pings.isEmpty()) {
                if (pendingDrainLock.withLock { report.id !in activePendingIds }) {
                    PendingYouTubePlaybackReportStore.removeIfEmpty(report.id)
                }
                continue
            }
            if (report.nextAttemptAtMs > epochTimeMs()) continue
            val playbackSession = repository.playbackTrackingSession()
            if (!isReplayCurrent(accountKey, playbackSession)) return null
            val tracking =
                try {
                    repository.getPlaybackTracking(
                        videoId = report.videoId,
                        clientPlaybackNonce = report.clientPlaybackNonce,
                        playbackSession = playbackSession,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (!isReplayCurrent(accountKey, playbackSession)) return null
            if (tracking?.playbackUrl == null) {
                PendingYouTubePlaybackReportStore.markRetry(report.id, epochTimeMs())
                break
            }
            var opened = report.pings.none { it is PendingYouTubePlaybackPing.Start }
            var previousOffset = report.lastAcknowledgedOffsetMs
            for (ping in report.pings) {
                val waitMs = (ping.offsetMs - previousOffset).coerceAtLeast(0L)
                if (waitMs > 0L) replayDelay(waitMs)
                previousOffset = ping.offsetMs
                if (!isReplayCurrent(accountKey, playbackSession)) return null
                val stillQueued = PendingYouTubePlaybackReportStore.all().any { it.id == report.id && ping in it.pings }
                if (!stillQueued) break
                val success =
                    try {
                        submitPendingPing(report, ping, tracking, opened, playbackSession)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                if (!isReplayCurrent(accountKey, playbackSession)) return null
                if (!success) {
                    PendingYouTubePlaybackReportStore.markRetry(report.id, epochTimeMs())
                    break
                }
                if (ping is PendingYouTubePlaybackPing.Start) opened = true
                PendingYouTubePlaybackReportStore.removePing(
                    report.id,
                    ping,
                    retainEmpty = pendingDrainLock.withLock { report.id in activePendingIds },
                )
                Logger.i(
                    REPORTING_TAG,
                    "replay outcome=success phase=${if (ping is PendingYouTubePlaybackPing.Start) "start" else "watchtime"}",
                )
            }
        }
        return PendingYouTubePlaybackReportStore
            .all()
            .filter { it.accountKey == accountKey && it.pings.isNotEmpty() }
            .minOfOrNull { (it.nextAttemptAtMs - epochTimeMs()).takeIf { remaining -> remaining > 0L } ?: BASE_RETRY_MS }
    }

    private fun isReplayCurrent(
        accountKey: String,
        playbackSession: PlaybackTrackingSession,
    ): Boolean =
        Settings.shared.queueYouTubeHistoryWhenOffline &&
            repository.isPlaybackReportingEnabled() &&
            repository.playbackReportingAccountKey() == accountKey &&
            repository.isPlaybackTrackingSessionCurrent(playbackSession)

    private suspend fun submitPendingPing(
        report: PendingYouTubePlaybackReport,
        ping: PendingYouTubePlaybackPing,
        tracking: PlaybackTrackingData,
        opened: Boolean,
        playbackSession: PlaybackTrackingSession,
    ): Boolean =
        when (ping) {
            is PendingYouTubePlaybackPing.Start -> {
                repository.registerPlayback(
                    playbackTrackingUrl = tracking.playbackUrl ?: return false,
                    clientPlaybackNonce = tracking.clientPlaybackNonce,
                    currentMediaTime = WatchTimeSegments.formatSeconds(ping.currentMediaTimeMs),
                    format = report.format,
                    playbackSession = playbackSession,
                )
            }

            is PendingYouTubePlaybackPing.Watchtime -> {
                val watchtimeUrl = tracking.watchtimeUrl
                opened && watchtimeUrl != null &&
                    repository.registerWatchtime(
                        watchtimeTrackingUrl = watchtimeUrl,
                        clientPlaybackNonce = tracking.clientPlaybackNonce,
                        startTimes = ping.startSeconds,
                        endTimes = ping.endSeconds,
                        currentMediaTime = WatchTimeSegments.formatSeconds(ping.currentMediaTimeMs),
                        realTime = WatchTimeSegments.formatSeconds(ping.realTimeMs),
                        final = ping.final,
                        format = report.format,
                        playbackSession = playbackSession,
                    )
            }
        }

    companion object {
        fun create(
            scope: CoroutineScope,
            repository: YouTubeRepository,
        ) = YouTubePlaybackReporter(CoroutineScope(scope.coroutineContext + Dispatchers.IO), repository)

        private val monotonicTimeOrigin = TimeSource.Monotonic.markNow()
        private const val REPORTING_TAG = "YouTubeReporter"
        private const val REAL_SEEK_MIN_MS = 1_000L
        private const val TRACKING_MAX_AGE_MS = 60L * 60L * 1000L
        private const val BASE_RETRY_MS = 30_000L
        private const val OFFLINE_CHECKPOINT_MS = 30_000L
        private val pendingDrainLock = ReentrantLock()
        private var pendingDrainJob: Job? = null
        private var pendingDrainAttempt: CompletableDeferred<Unit>? = null
        private val activePendingIds = mutableSetOf<String>()
    }
}

internal sealed interface PlaybackReporterPingResult {
    val clientPlaybackNonce: String
    val success: Boolean

    data class Start(
        override val clientPlaybackNonce: String,
        override val success: Boolean,
    ) : PlaybackReporterPingResult

    data class Watchtime(
        override val clientPlaybackNonce: String,
        override val success: Boolean,
        val segments: List<Pair<Long, Long>>,
        val final: Boolean,
    ) : PlaybackReporterPingResult
}

private fun YouTubePlaybackReporter.Ping.logType(): String =
    when (this) {
        is YouTubePlaybackReporter.Ping.Start -> "start"
        is YouTubePlaybackReporter.Ping.Watchtime -> "watchtime"
    }

private fun YouTubePlaybackReporter.Ping.result(
    clientPlaybackNonce: String,
    success: Boolean,
): PlaybackReporterPingResult =
    when (this) {
        is YouTubePlaybackReporter.Ping.Start -> {
            PlaybackReporterPingResult.Start(clientPlaybackNonce, success)
        }

        is YouTubePlaybackReporter.Ping.Watchtime -> {
            PlaybackReporterPingResult.Watchtime(clientPlaybackNonce, success, segments.segments, final)
        }
    }

internal fun isPlaybackPositionDiscontinuity(
    previousPositionMs: Long,
    positionMs: Long,
    elapsedRealtimeMs: Long,
    playbackSpeed: Float,
): Boolean {
    val positionDeltaMs = positionMs - previousPositionMs
    val expectedAdvanceMs = (elapsedRealtimeMs.coerceAtLeast(0L) * playbackSpeed.coerceIn(0.1f, 4f)).toLong()
    return positionDeltaMs < -POSITION_DISCONTINUITY_TOLERANCE_MS ||
        positionDeltaMs > expectedAdvanceMs + POSITION_DISCONTINUITY_TOLERANCE_MS
}

private const val POSITION_DISCONTINUITY_TOLERANCE_MS = 1_500L
