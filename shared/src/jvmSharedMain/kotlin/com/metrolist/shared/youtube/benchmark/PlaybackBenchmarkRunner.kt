package com.metrolist.shared.youtube.benchmark

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackClientOption
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.playback.PlaybackReporterPingResult
import com.metrolist.shared.playback.WatchTimeSegments
import com.metrolist.shared.playback.YouTubePlaybackReporter
import com.metrolist.shared.youtube.logging.YtLogger
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

data class PlaybackBenchmarkEnvironment(
    val httpClient: HttpClient,
    val resolverFor: () -> StreamResolver,
    val playerFactory: () -> PlaybackBenchmarkPlayer,
    val youtubeRepository: YouTubeRepository? = null,
)

class PlaybackBenchmarkRunner(
    private val environmentFor: (anonymousSession: Boolean) -> PlaybackBenchmarkEnvironment,
    private val onCaseCompleted: (PlaybackBenchmarkCaseResult) -> Unit = {},
) {
    private val _progress = MutableStateFlow(PlaybackBenchmarkProgress())
    val progress: StateFlow<PlaybackBenchmarkProgress> = _progress.asStateFlow()

    private val runMutex = Mutex()
    private val runJob = AtomicReference<Job?>()

    fun cancel() {
        runJob.get()?.cancel()
    }

    suspend fun run(config: PlaybackBenchmarkConfig): PlaybackBenchmarkReport =
        coroutineScope {
            val job = currentCoroutineContext().job
            runJob.getAndSet(job)?.cancel()
            try {
                runMutex.withLock { runBenchmark(config) }
            } finally {
                runJob.compareAndSet(job, null)
            }
        }

    private suspend fun runBenchmark(config: PlaybackBenchmarkConfig): PlaybackBenchmarkReport =
        withContext(Dispatchers.Default) {
            val results = mutableListOf<PlaybackBenchmarkCaseResult>()
            val profiles =
                buildList {
                    if (config.includeAutomaticSelection) add(null)
                    addAll(config.selectedProfiles)
                }
            val totalCases = profiles.size * config.samples.size
            val windowPerCase = config.playbackWindowPerCaseMs
            _progress.value = PlaybackBenchmarkProgress(totalCases = totalCases, running = true)
            try {
                val environment = environmentFor(config.anonymousSession)
                for (profile in profiles) {
                    for (sample in config.samples) {
                        coroutineContext.ensureActive()
                        val requestedProfileId = profile?.id ?: AUTOMATIC_PROFILE_ID
                        _progress.value = _progress.value.copy(activeProfileId = requestedProfileId, activeCase = sample.case)
                        val result = runCase(profile, sample, windowPerCase, config, environment)
                        results += result
                        onCaseCompleted(result)
                        YtLogger.d(
                            TAG,
                            "result profile=${result.profileId} case=${result.case.name} status=${result.status.name} " +
                                "selected=${result.selectedClientName}/${result.selectedProfileId} transport=${result.transport} " +
                                "poToken=${result.usedPoToken} attempts=${result.playerResponseAttempts} " +
                                "hops=${result.fallbackHops} resolve=${result.resolutionMs}ms " +
                                "prepare=${result.preparationMs}ms firstMedia=${result.firstMediaResponseMs}ms " +
                                "playbackStart=${result.timeToPlaybackStartMs}ms " +
                                "failureCategory=${result.failureCategory ?: "none"} " +
                                "failureType=${result.failureType ?: "none"} mediaStatuses=${result.mediaStatuses} " +
                                "assertions=${result.assertions}",
                        )
                        _progress.value = _progress.value.copy(completedCases = results.size)
                    }
                }
                PlaybackBenchmarkReport(results, cancelled = false, anonymousSession = config.anonymousSession)
            } catch (_: CancellationException) {
                PlaybackBenchmarkReport(results, cancelled = true, anonymousSession = config.anonymousSession)
            } finally {
                _progress.value = _progress.value.copy(running = false, cancelled = results.size < totalCases)
            }
        }

    private suspend fun runCase(
        profile: PlaybackClientOption?,
        sample: PlaybackBenchmarkSample,
        windowMs: Long,
        config: PlaybackBenchmarkConfig,
        environment: PlaybackBenchmarkEnvironment,
    ): PlaybackBenchmarkCaseResult {
        val caseStartedNanos = System.nanoTime()
        val requestedProfileId = profile?.id ?: AUTOMATIC_PROFILE_ID
        var nonce = generateClientPlaybackNonce()
        val resolver = environment.resolverFor()
        val hints =
            ContentHints(
                isExplicit = sample.case == PlaybackBenchmarkCase.EXPLICIT,
                isKidsContent = sample.case == PlaybackBenchmarkCase.KIDS,
                playbackClientOverrideId = profile?.id,
            )
        var resource: PlaybackResource? = null
        var player: PlaybackBenchmarkPlayer? = null
        var reporter: YouTubePlaybackReporter? = null
        var reporterScope: CoroutineScope? = null
        var startedAt: Long
        var watchtimeEnd: Long
        var duration: Long? = null
        var advanced = 0L
        var forwardSeek: Long? = null
        var backwardSeek: Long? = null
        var usable = false
        var noFailure = true
        var noHttp4xx = false
        var oneAttempt: Boolean
        var requestedProfileUsed = false
        var stable = false
        var seeksSettled = false
        var selectedClientName: String? = null
        var selectedProfileId: String? = null
        var transport: String? = null
        var usedPoToken = false
        var usedAuthenticatedWatchPage = false
        var playerResponseAttempts = 0
        var resolutionMs: Long? = null
        var preparationMs: Long? = null
        val firstMediaResponseElapsedMs = AtomicLong(-1L)
        var timeToPlaybackStartMs: Long? = null
        val mediaStatuses = CopyOnWriteArrayList<Int>()
        val attemptedProfileIds = mutableListOf<String>()
        val watchtimeRecorder = BenchmarkWatchtimeRecorder()
        val playbackStatsRecorder = BenchmarkPlaybackStatsRecorder()
        val watchtimeSegments = WatchTimeSegments()

        return try {
            resource = resolver.resolve(sample.videoId, hints)
            resolutionMs = elapsedMs(caseStartedNanos)
            val resolvedResource = resource
            nonce = resolvedResource.playbackTracking?.clientPlaybackNonce ?: nonce
            val diagnostics = resolvedResource.streamDiagnostics
            val requestedAttempts = diagnostics?.attempts.orEmpty().filterNot { it.outcome.startsWith("selection:") }
            attemptedProfileIds += requestedAttempts.mapNotNull { it.profileId }
            selectedClientName = resolvedResource.clientName
            selectedProfileId = resolvedResource.profileId
            transport =
                when {
                    resolvedResource.isSabr -> "SABR"
                    resolvedResource.url.contains(".m3u8", ignoreCase = true) -> "HLS"
                    else -> "DIRECT"
                }
            usedPoToken = resolvedResource.profileId.endsWith("__po")
            usedAuthenticatedWatchPage = diagnostics?.usedAuthenticatedWatchPage == true
            playerResponseAttempts = requestedAttempts.size
            requestedProfileUsed =
                profile == null || PlaybackClientCatalog.manifestIdFromProfileId(resolvedResource.profileId) == profile.id
            val forcedAttemptMatched =
                requestedAttempts.size == 1 &&
                    requestedAttempts.singleOrNull()?.let {
                        PlaybackClientCatalog.manifestIdFromProfileId(it.profileId) == profile?.id &&
                            it.clientName == resolvedResource.clientName && requestedProfileUsed
                    } == true
            oneAttempt = profile == null || forcedAttemptMatched
            usable = resolvedResource.url.isNotBlank() && resolvedResource.videoId == sample.videoId
            val firstDuration = resolvedResource.sabrBootstrap?.durationMs ?: 0L
            val benchmarkPlayer = environment.playerFactory()
            player = benchmarkPlayer
            environment.youtubeRepository?.let { repository ->
                reporterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                reporter =
                    YouTubePlaybackReporter(
                        scope = reporterScope,
                        repository = repository,
                        onPingResult = playbackStatsRecorder::record,
                    )
            }
            val preparationStartedNanos = System.nanoTime()
            val prepared =
                benchmarkPlayer.prepare(resolvedResource, environment.httpClient) { status ->
                    mediaStatuses += status
                    firstMediaResponseElapsedMs.compareAndSet(-1L, elapsedMs(caseStartedNanos))
                }
            preparationMs = elapsedMs(preparationStartedNanos)
            if (!prepared) {
                noFailure = false
            } else {
                duration = benchmarkPlayer.duration().takeIf { it > 0L } ?: firstDuration.takeIf { it > 0L }
                benchmarkPlayer.play()
                timeToPlaybackStartMs = awaitPlaybackStart(benchmarkPlayer, caseStartedNanos)
                if (timeToPlaybackStartMs == null) noFailure = false
                startedAt = benchmarkPlayer.currentPosition()
                watchtimeSegments.onPlay(startedAt)
                val initialPlaybackMs = minOf(windowMs, INITIAL_PLAYBACK_WINDOW_MS)
                advanced +=
                    playFor(
                        benchmarkPlayer,
                        initialPlaybackMs,
                        watchtimeSegments,
                        reporter,
                        resolvedResource,
                    )
                val mainWindowEnd = benchmarkPlayer.currentPosition()
                watchtimeSegments.onProgress(mainWindowEnd)
                stable =
                    duration != null &&
                    duration == (benchmarkPlayer.duration().takeIf { it > 0L } ?: duration)
                duration?.let { knownDuration ->
                    val forwardTarget = (mainWindowEnd + config.seekOffsetMs).coerceAtMost(knownDuration - 1L)
                    watchtimeSegments.onSeek(mainWindowEnd, forwardTarget, continuePlaying = true)
                    reporter?.onSeek(PLAYBACK_INSTANCE_ID, mainWindowEnd, forwardTarget, continuePlaying = true)
                    benchmarkPlayer.seekTo(forwardTarget)
                    forwardSeek = awaitPosition(benchmarkPlayer, forwardTarget, config.seekSettleTimeoutMs)
                    advanced +=
                        playFor(
                            benchmarkPlayer,
                            POST_FORWARD_PLAYBACK_MS,
                            watchtimeSegments,
                            reporter,
                            resolvedResource,
                        )
                    val forwardEnd = benchmarkPlayer.currentPosition()
                    watchtimeSegments.onProgress(forwardEnd)
                    val backwardTarget = (forwardEnd - config.seekOffsetMs).coerceAtLeast(0L)
                    watchtimeSegments.onSeek(forwardEnd, backwardTarget, continuePlaying = true)
                    reporter?.onSeek(PLAYBACK_INSTANCE_ID, forwardEnd, backwardTarget, continuePlaying = true)
                    benchmarkPlayer.seekTo(backwardTarget)
                    backwardSeek = awaitPosition(benchmarkPlayer, backwardTarget, config.seekSettleTimeoutMs)
                    advanced +=
                        playFor(
                            player = benchmarkPlayer,
                            durationMs = (windowMs - initialPlaybackMs - POST_FORWARD_PLAYBACK_MS).coerceAtLeast(0L),
                            watchtimeSegments = watchtimeSegments,
                            reporter = reporter,
                            resource = resolvedResource,
                        )
                    val backwardEnd = benchmarkPlayer.currentPosition()
                    watchtimeSegments.onProgress(backwardEnd)
                    seeksSettled =
                        forwardTarget >= mainWindowEnd + MIN_SEEK_DIRECTION_MS &&
                        (forwardSeek ?: Long.MIN_VALUE) >= mainWindowEnd + MIN_SEEK_DIRECTION_MS &&
                        forwardEnd >= (forwardSeek ?: Long.MAX_VALUE) + MIN_POST_SEEK_ADVANCE_MS &&
                        backwardTarget <= forwardEnd - MIN_SEEK_DIRECTION_MS &&
                        (backwardSeek ?: Long.MAX_VALUE) <= forwardEnd - MIN_SEEK_DIRECTION_MS &&
                        backwardEnd >= (backwardSeek ?: Long.MAX_VALUE) + MIN_POST_SEEK_ADVANCE_MS
                }
                watchtimeEnd = benchmarkPlayer.currentPosition()
                watchtimeSegments
                    .drain(currentPositionMs = watchtimeEnd, continuePlaying = false)
                    ?.segments
                    .orEmpty()
                    .forEach { (startMs, endMs) ->
                        watchtimeRecorder.recordNonOverlappingWatchtime(nonce, startMs, endMs)
                    }
                reporter?.onPlaybackStopped(PLAYBACK_INSTANCE_ID, watchtimeEnd)
                reporter?.awaitPendingPings()
            }
            noFailure = noFailure && benchmarkPlayer.failure == null
            watchtimeRecorder.recordFinal(nonce)
            noHttp4xx = mediaStatuses.none { it in 400..499 }
            val assertions =
                PlaybackBenchmarkAssertions(
                    oneAttempt,
                    requestedProfileUsed,
                    usable,
                    noHttp4xx && noFailure,
                    stable,
                    advanced >= windowMs - PLAYBACK_ADVANCE_TOLERANCE_MS,
                    seeksSettled,
                    if (reporter != null) playbackStatsRecorder.nonces == setOf(nonce) else watchtimeRecorder.nonces == setOf(nonce),
                    if (reporter != null) playbackStatsRecorder.nonOverlapping else watchtimeRecorder.nonOverlapping,
                    if (reporter != null) playbackStatsRecorder.finalRecorded else watchtimeRecorder.finalRecorded,
                    if (reporter != null) playbackStatsRecorder.successfulStarts == 1 else true,
                )
            PlaybackBenchmarkCaseResult(
                profileId = requestedProfileId,
                case = sample.case,
                sampleId = sample.videoId,
                nonce = nonce,
                status = if (assertions.passed) PlaybackBenchmarkStatus.PASSED else PlaybackBenchmarkStatus.FAILED,
                assertions = assertions,
                durationMs = duration,
                advancedMs = advanced,
                forwardSeekMs = forwardSeek,
                backwardSeekMs = backwardSeek,
                recordedWatchtimeMs =
                    if (reporter != null) playbackStatsRecorder.totalRecordedMs else watchtimeRecorder.totalRecordedMs,
                finalRecorded =
                    if (reporter != null) playbackStatsRecorder.finalRecorded else watchtimeRecorder.finalRecorded,
                attemptedProfileIds = attemptedProfileIds,
                mediaStatuses = mediaStatuses.distinct(),
                selectedClientName = selectedClientName,
                selectedProfileId = selectedProfileId,
                transport = transport,
                usedPoToken = usedPoToken,
                usedAuthenticatedWatchPage = usedAuthenticatedWatchPage,
                playerResponseAttempts = playerResponseAttempts,
                fallbackHops = (playerResponseAttempts - 1).coerceAtLeast(0),
                resolutionMs = resolutionMs,
                preparationMs = preparationMs,
                firstMediaResponseMs = firstMediaResponseElapsedMs.get().takeIf { it >= 0L },
                timeToPlaybackStartMs = timeToPlaybackStartMs,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (reporter != null) {
                val finalPosition = runCatching { player?.currentPosition() ?: 0L }.getOrDefault(0L)
                reporter.onPlaybackStopped(PLAYBACK_INSTANCE_ID, finalPosition)
                reporter.awaitPendingPings()
            } else {
                watchtimeRecorder.recordFinal(nonce)
            }
            val diagnostics = (error as? StreamResolveException)?.diagnostics ?: resource?.streamDiagnostics
            val requestedAttempts = diagnostics?.attempts.orEmpty().filterNot { it.outcome.startsWith("selection:") }
            attemptedProfileIds += requestedAttempts.mapNotNull { it.profileId }.filterNot(attemptedProfileIds::contains)
            playerResponseAttempts = maxOf(playerResponseAttempts, requestedAttempts.size)
            usedAuthenticatedWatchPage = usedAuthenticatedWatchPage || diagnostics?.usedAuthenticatedWatchPage == true
            val forcedAttemptMatched =
                requestedAttempts.size == 1 &&
                    PlaybackClientCatalog.manifestIdFromProfileId(requestedAttempts.singleOrNull()?.profileId) == profile?.id
            oneAttempt = profile == null || forcedAttemptMatched
            if (resolutionMs == null) resolutionMs = elapsedMs(caseStartedNanos)
            PlaybackBenchmarkCaseResult(
                profileId = requestedProfileId,
                case = sample.case,
                sampleId = sample.videoId,
                nonce = nonce,
                status = PlaybackBenchmarkStatus.FAILED,
                assertions =
                    PlaybackBenchmarkAssertions(
                        oneAttempt,
                        requestedProfileUsed,
                        usable,
                        noHttp4xx && noFailure,
                        stable,
                        advanced >= windowMs - PLAYBACK_ADVANCE_TOLERANCE_MS,
                        seeksSettled,
                        if (reporter != null) playbackStatsRecorder.nonces == setOf(nonce) else watchtimeRecorder.nonces == setOf(nonce),
                        if (reporter != null) playbackStatsRecorder.nonOverlapping else watchtimeRecorder.nonOverlapping,
                        if (reporter != null) playbackStatsRecorder.finalRecorded else watchtimeRecorder.finalRecorded,
                        if (reporter != null) playbackStatsRecorder.successfulStarts == 1 else false,
                    ),
                attemptedProfileIds = attemptedProfileIds,
                mediaStatuses = mediaStatuses.distinct(),
                failureCategory = PlaybackFailureClassifier.fromThrowable(error).name,
                failureType = error::class.simpleName,
                selectedClientName = selectedClientName,
                selectedProfileId = selectedProfileId,
                transport = transport,
                usedPoToken = usedPoToken,
                usedAuthenticatedWatchPage = usedAuthenticatedWatchPage,
                playerResponseAttempts = playerResponseAttempts,
                fallbackHops = (playerResponseAttempts - 1).coerceAtLeast(0),
                resolutionMs = resolutionMs,
                preparationMs = preparationMs,
                firstMediaResponseMs = firstMediaResponseElapsedMs.get().takeIf { it >= 0L },
                timeToPlaybackStartMs = timeToPlaybackStartMs,
            )
        } finally {
            reporterScope?.cancel()
            withContext(NonCancellable) {
                try {
                    player?.close()
                } finally {
                    resolver.close()
                }
            }
        }
    }

    private suspend fun awaitPlaybackStart(
        player: PlaybackBenchmarkPlayer,
        caseStartedNanos: Long,
    ): Long? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PLAYBACK_START_TIMEOUT_MS)
        while (System.nanoTime() < deadline && player.failure == null) {
            currentCoroutineContext().ensureActive()
            if (player.isPlaying()) return elapsedMs(caseStartedNanos)
            kotlinx.coroutines.delay(25L.milliseconds)
        }
        return null
    }

    private suspend fun awaitPosition(
        player: PlaybackBenchmarkPlayer,
        target: Long,
        timeoutMs: Long,
    ): Long? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val position = player.currentPosition()
            if (kotlin.math.abs(position - target) <= 1_000L) return position
            kotlinx.coroutines.delay(50L.milliseconds)
        }
        return null
    }

    private suspend fun playFor(
        player: PlaybackBenchmarkPlayer,
        durationMs: Long,
        watchtimeSegments: WatchTimeSegments,
        reporter: YouTubePlaybackReporter?,
        resource: PlaybackResource,
    ): Long {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs)
        var sampledAtNanos = System.nanoTime()
        var sampledPositionMs = player.currentPosition()
        var advancedMs = 0L
        while (System.nanoTime() < deadline && player.failure == null) {
            currentCoroutineContext().ensureActive()
            kotlinx.coroutines.delay(100L.milliseconds)
            val nowNanos = System.nanoTime()
            val positionMs = player.currentPosition()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(nowNanos - sampledAtNanos).coerceAtLeast(0L)
            val deltaMs = positionMs - sampledPositionMs
            if (deltaMs in 0L..(elapsedMs + POSITION_ADVANCE_TOLERANCE_MS)) advancedMs += deltaMs
            sampledAtNanos = nowNanos
            sampledPositionMs = positionMs
            watchtimeSegments.onProgress(positionMs)
            reporter?.onPlaybackSample(
                playbackInstanceId = PLAYBACK_INSTANCE_ID,
                resource = resource,
                positionMs = positionMs,
                isRendering = player.isPlaying(),
                wantsPlayback = player.wantsPlayback(),
                playbackSpeed = 1f,
            )
        }
        return advancedMs
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos).coerceAtLeast(0L)

    private companion object {
        const val TAG = "PlaybackBenchmark"
        const val AUTOMATIC_PROFILE_ID = "AUTO"
        const val PLAYBACK_START_TIMEOUT_MS = 10_000L
        const val INITIAL_PLAYBACK_WINDOW_MS = 45_000L
        const val POST_FORWARD_PLAYBACK_MS = 20_000L
        const val PLAYBACK_ADVANCE_TOLERANCE_MS = 5_000L
        const val POSITION_ADVANCE_TOLERANCE_MS = 1_500L
        const val MIN_SEEK_DIRECTION_MS = 4_000L
        const val MIN_POST_SEEK_ADVANCE_MS = 2_000L
        const val PLAYBACK_INSTANCE_ID = 1L
    }
}

private class BenchmarkWatchtimeRecorder {
    val nonces = linkedSetOf<String>()
    var nonOverlapping = true
        private set
    var finalRecorded = false
        private set
    var totalRecordedMs = 0L
        private set
    private var previousEndMs = Long.MIN_VALUE

    fun recordNonOverlappingWatchtime(
        nonce: String,
        startMs: Long,
        endMs: Long,
    ) {
        nonces += nonce
        if (startMs < previousEndMs || endMs < startMs) nonOverlapping = false
        previousEndMs = maxOf(previousEndMs, endMs)
        totalRecordedMs += (endMs - startMs).coerceAtLeast(0L)
    }

    fun recordFinal(nonce: String) {
        nonces += nonce
        finalRecorded = true
    }
}

private class BenchmarkPlaybackStatsRecorder {
    val nonces = linkedSetOf<String>()
    var successfulStarts = 0
        private set
    var nonOverlapping = true
        private set
    var finalRecorded = false
        private set
    var totalRecordedMs = 0L
        private set
    private var previousEndMs = Long.MIN_VALUE

    @Synchronized
    fun record(result: PlaybackReporterPingResult) {
        nonces += result.clientPlaybackNonce
        when (result) {
            is PlaybackReporterPingResult.Start -> {
                if (result.success) successfulStarts++
            }

            is PlaybackReporterPingResult.Watchtime -> {
                result.segments.forEach { (startMs, endMs) ->
                    if (startMs < previousEndMs || endMs < startMs) nonOverlapping = false
                    previousEndMs = maxOf(previousEndMs, endMs)
                    totalRecordedMs += (endMs - startMs).coerceAtLeast(0L)
                }
                if (result.final && result.success) finalRecorded = true
            }
        }
    }
}
