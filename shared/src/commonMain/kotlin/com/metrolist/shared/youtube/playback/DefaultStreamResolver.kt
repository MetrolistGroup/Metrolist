package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamAttemptDiagnostic
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.diagnosticSummary
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackTransport
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.shared.api.PLAYER_NEXT_RESPONSE_LIMIT
import com.metrolist.shared.api.bodyLimited
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.api.musicvideo.MusicVideoResult
import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isUploadedType
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackFailureClassifier
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.targetHeight
import com.metrolist.shared.youtube.logging.YtLogger
import com.metrolist.shared.youtube.strategy.ClientHealthTracker
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class DefaultStreamResolver(
    private val extractor: StreamExtractor,
    private val innerTube: InnerTube,
    private val tokenProvider: TokenProvider? = null,
    private val clientHealthTracker: ClientHealthTracker = ClientHealthTracker(),
    private val playerConfigRefresh: suspend () -> Boolean = { false },
    private val musicVideoResolver: MusicVideoResolver? = null,
    private val resolveTimeoutMs: Long = DEFAULT_RESOLVE_TIMEOUT_MS,
    private val networkState: () -> PlaybackNetworkState = { PlaybackNetworkStateStore.current },
    networkStateFlow: StateFlow<PlaybackNetworkState>? = PlaybackNetworkStateStore.state,
    private val localAudioSource: LocalAudioSource? = null,
    private val elapsedMs: () -> Long = ::playbackElapsedMs,
    private val liveHttpClientFactory: () -> HttpClient = ::createHttpClient,
    resolveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : StreamResolver {
    private val resolverScope = CoroutineScope(SupervisorJob() + resolveDispatcher)

    companion object {
        private const val TAG = "StreamResolver"
        private const val MAX_INVALIDATION_RETRIES = 3
        private const val FAILED_PROFILE_TTL_MS = 120_000L
        private const val FALLBACK_RESOURCE_TTL_MS = 120_000L
        private const val CACHE_EXPIRY_SAFETY_WINDOW_MS = 10_000L
        private const val MAX_RESOURCE_CACHE_ENTRIES = 64
        private const val DEFAULT_RESOLVE_TIMEOUT_MS = 55_000L
        private const val DIRECT_AUDIO_TIMEOUT_MS = 10_000L

        private const val PREFERRED_CLIENT_TIMEOUT_MS = 20_000L
        private val CUTOFF_PROFILES = setOf("VISIONOS_0_1__nopo", "VISIONOS_SABR__nopo")
        private const val TOKENLESS_AUDIO_TIMEOUT_MS = 8_000L
    }

    private data class CacheKey(
        val videoId: String,
        val hints: ContentHints,
        val audioQuality: AudioQuality,
        val sessionFingerprint: String,
        val networkScope: String,
        val excludedProfiles: Set<String>,
    )

    private data class FailureKey(
        val videoId: String,
        val sessionFingerprint: String,
        val networkScope: String,
    )

    private data class CachedPlaybackResource(
        val resource: PlaybackResource,
        val expiresAtMs: Long,
    )

    private data class Flight(
        val deferred: CompletableDeferred<PlaybackResource>,
        val generation: Long,
        val networkGeneration: Long,
        var waiters: Int = 1,
    )

    private class ResolutionInvalidatedException(
        message: String,
    ) : CancellationException(message)

    private val stateMutex = Mutex()
    private val failedProfiles = mutableMapOf<FailureKey, MutableMap<String, Long>>()
    private val resourceCache = linkedMapOf<CacheKey, CachedPlaybackResource>()
    private val normalFlights = mutableMapOf<CacheKey, Flight>()
    private val refreshFlights = mutableMapOf<CacheKey, Flight>()
    private val videoGenerations = mutableMapOf<String, Long>()
    private var cooldownScope: Pair<String, Long>? = null
    private var extractionCooldown = ExtractionCooldown(elapsedMs)
    private var networkGeneration = 0L
    private var closed = false
    private var currentNetworkScope = networkState().cacheScope()

    init {
        require(resolveTimeoutMs > 0L) { "Stream resolve timeout must be positive" }
        networkStateFlow?.let { flow ->
            resolverScope.launch {
                flow.collect(::invalidateNetworkScope)
            }
        }
    }

    override suspend fun prewarm() {
        val startTime = Clock.System.now().toEpochMilliseconds()
        try {
            currentExtractionCooldown().run(resetOnSuccess = false) { extractor.prewarm() }
            YtLogger.d(TAG, "prewarm done elapsed=${Clock.System.now().toEpochMilliseconds() - startTime}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.d(
                TAG,
                "prewarm failed elapsed=${Clock.System.now().toEpochMilliseconds() - startTime}ms " +
                    "type=${e::class.simpleName ?: "unknown"}",
            )
        }
    }

    override suspend fun clearPlaybackFailures(videoId: String) {
        stateMutex.withLock {
            failedProfiles.keys.removeAll { it.videoId == videoId }
            resourceCache.keys.removeAll { it.videoId == videoId }
            (normalFlights.filterKeys { it.videoId == videoId }.values + refreshFlights.filterKeys { it.videoId == videoId }.values)
                .forEach { it.deferred.cancel(CancellationException("Playback resolution invalidated")) }
            normalFlights.keys.removeAll { it.videoId == videoId }
            refreshFlights.keys.removeAll { it.videoId == videoId }
            videoGenerations[videoId] = (videoGenerations[videoId] ?: 0L) + 1L
        }
        YtLogger.d(TAG, "playback failure state cleared")
    }

    override suspend fun invalidateNetworkScope(state: PlaybackNetworkState) {
        stateMutex.withLock {
            val scope = state.cacheScope()
            if (currentNetworkScope == scope) return
            currentNetworkScope = scope
            networkGeneration++
            failedProfiles.clear()
            resourceCache.clear()
            videoGenerations.clear()
            (normalFlights.values + refreshFlights.values).forEach {
                it.deferred.cancel(ResolutionInvalidatedException("Playback network scope changed"))
            }
            normalFlights.clear()
            refreshFlights.clear()
        }
    }

    override suspend fun resetExtractionBackoff() {
        stateMutex.withLock { extractionCooldown = ExtractionCooldown(elapsedMs) }
    }

    override suspend fun invalidateAttestation() {
        tokenProvider?.invalidateAttestation()
    }

    override suspend fun refreshPlayerConfigAfterStreamRejection(): Boolean =
        try {
            playerConfigRefresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            YtLogger.d(TAG, "player config rejection refresh failed type=${error::class.simpleName ?: "unknown"}")
            false
        }

    override suspend fun resolve(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource = resolveInternal(videoId, hints, audioQuality, sourceSong, refresh = false)

    override suspend fun refresh(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource {
        YtLogger.d(TAG, "refresh requested hints=${hints.diagnosticSummary()}")
        return resolveInternal(videoId, hints, audioQuality, sourceSong, refresh = true)
    }

    override suspend fun resolveRemote(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        excludedProfiles: Set<String>,
    ): PlaybackResource = resolveInternal(videoId, hints, audioQuality, sourceSong, refresh, preferLocal = false, excludedProfiles)

    override suspend fun reportPlaybackFailure(
        videoId: String,
        clientName: String?,
        responseCode: Int?,
        profileId: String?,
        hints: ContentHints,
        category: PlaybackFailureCategory,
    ) {
        val effectiveCategory =
            category.takeUnless { it == PlaybackFailureCategory.UNKNOWN }
                ?: responseCode?.let(PlaybackFailureClassifier::fromHttpStatus)
                ?: PlaybackFailureCategory.UNKNOWN
        val sessionFingerprint = playbackSessionFingerprint()
        val untokenizedProfile403 = responseCode == 403 && profileId?.endsWith("_nopo") == true
        if (effectiveCategory == PlaybackFailureCategory.SABR_ATTESTATION ||
            (responseCode == 403 && profileId?.endsWith("__po") == true)
        ) {
            WebPoTokenHealth.recordRejection()
        }
        val directProfile =
            PlaybackClientCatalog.manifests
                .firstOrNull { it.id == PlaybackClientCatalog.manifestIdFromProfileId(profileId) }
                ?.transports
                ?.contains(PlaybackTransport.DIRECT) == true
        val failureKeys =
            buildSet {
                if (effectiveCategory == PlaybackFailureCategory.URL_EXPIRED) return@buildSet
                profileId?.takeIf { it.isNotBlank() }?.let(::add)
                if ((!untokenizedProfile403 && responseCode == 403 && !directProfile) || isEmpty()) {
                    clientName?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        stateMutex.withLock {
            resourceCache.keys.removeAll { it.videoId == videoId }
            (normalFlights.filterKeys { it.videoId == videoId }.values + refreshFlights.filterKeys { it.videoId == videoId }.values)
                .forEach { it.deferred.cancel(ResolutionInvalidatedException("Playback resolution invalidated")) }
            normalFlights.keys.removeAll { it.videoId == videoId }
            refreshFlights.keys.removeAll { it.videoId == videoId }
            videoGenerations[videoId] = (videoGenerations[videoId] ?: 0L) + 1L
            if (failureKeys.isNotEmpty()) {
                val expiresAt = Clock.System.now().toEpochMilliseconds() + FAILED_PROFILE_TTL_MS
                val failures =
                    failedProfiles.getOrPut(FailureKey(videoId, sessionFingerprint, networkState().cacheScope())) {
                        mutableMapOf()
                    }
                failureKeys.forEach { failures[it] = expiresAt }
            }
        }
        if (effectiveCategory == PlaybackFailureCategory.CDN_FORBIDDEN && !untokenizedProfile403) {
            invalidateAttestation()
        }
        if (failureKeys.isNotEmpty()) {
            YtLogger.w(
                TAG,
                "playback failure recorded client=$clientName excluded=${failureKeys.joinToString()} " +
                    "http=$responseCode ttlMs=$FAILED_PROFILE_TTL_MS",
            )
            PlaybackEventLog.warn(
                PlaybackEventKind.RECOVERY,
                buildString {
                    append(
                        "Recorded a playback failure for $videoId and will skip ${failureKeys.joinToString()} for ${FAILED_PROFILE_TTL_MS / 1000}s.",
                    )
                    responseCode?.let { append(" ").append(PlaybackErrorMessages.httpStatus(it)) }
                },
                videoId = videoId,
                clientName = clientName,
                profileId = profileId,
                details =
                    buildMap {
                        put("excluded", failureKeys.joinToString(","))
                        responseCode?.let { put("http", it.toString()) }
                        putAll(hints.toPlaybackLogDetails())
                        putAll(innerTube.toPlaybackSessionLogDetails())
                    },
            )
        }
        if (effectiveCategory == PlaybackFailureCategory.URL_EXPIRED) return
        PlaybackClientCatalog.manifestIdFromProfileId(profileId)?.let { manifestId ->
            clientHealthTracker.recordFailure(
                manifestId,
                when (effectiveCategory) {
                    PlaybackFailureCategory.CDN_FORBIDDEN -> ClientFailureKind.MEDIA_FORBIDDEN
                    PlaybackFailureCategory.TOKEN -> ClientFailureKind.TOKEN
                    PlaybackFailureCategory.PLAYABILITY, PlaybackFailureCategory.UNAVAILABLE -> ClientFailureKind.PLAYABILITY
                    else -> ClientFailureKind.MEDIA_FAILURE
                },
                ClientHealthScope.from(hints, innerTube.hasSapCookieAuth()),
            )
        }
    }

    override suspend fun reportPlaybackSuccess(
        profileId: String?,
        hints: ContentHints,
    ) {
        PlaybackClientCatalog.manifestIdFromProfileId(profileId)?.let { manifestId ->
            clientHealthTracker.recordSuccess(
                manifestId,
                ClientHealthScope.from(hints, innerTube.hasSapCookieAuth()),
            )
        }
    }

    private suspend fun resolveInternal(
        videoId: String,
        requestedHints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        preferLocal: Boolean = true,
        excludedProfiles: Set<String> = emptySet(),
    ): PlaybackResource =
        withTimeoutOrNull(resolveTimeoutMs.milliseconds) {
            var invalidations = 0
            var resource: PlaybackResource? = null
            while (resource == null) {
                try {
                    resource = resolveAttempt(videoId, requestedHints, audioQuality, sourceSong, refresh, preferLocal, excludedProfiles)
                } catch (error: ResolutionInvalidatedException) {
                    currentCoroutineContext().ensureActive()
                    stateMutex.withLock { if (closed) throw error }
                    if (++invalidations >= MAX_INVALIDATION_RETRIES) {
                        throw StreamResolveException(
                            reason = StreamResolveException.Reason.NETWORK,
                            message = "Unable to fetch stream data from YouTube.",
                            cause = error,
                        )
                    }
                }
            }
            resource
        } ?: throw StreamResolveException(
            reason = StreamResolveException.Reason.NETWORK,
            message = "Unable to fetch stream data from YouTube.",
        )

    private suspend fun resolveAttempt(
        videoId: String,
        requestedHints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        preferLocal: Boolean,
        excludedProfiles: Set<String>,
    ): PlaybackResource {
        val uploaded = requestedHints.isUploaded == true || sourceSong?.isUploaded == true || isUploadedType(sourceSong?.musicVideoType)
        val hints = if (uploaded) requestedHints.copy(isUploaded = true, wantVideo = false) else requestedHints
        if (preferLocal && (!hints.wantVideo || !networkState().connected) && hints.playbackClientOverrideId == null) {
            localAudioSource?.resolve(videoId, sourceSong)?.let { return it }
        }
        val sessionFingerprint = playbackSessionFingerprint()
        val sessionIdentityFingerprint = playbackSessionIdentityFingerprint()
        val scope = networkState()
        invalidateNetworkScope(scope)
        val key = CacheKey(videoId, hints, audioQuality, sessionFingerprint, scope.cacheScope(), excludedProfiles)
        val nowMs = Clock.System.now().toEpochMilliseconds()
        var flightMap = if (refresh) refreshFlights else normalFlights
        var owner = false
        val flight =
            stateMutex.withLock {
                check(!closed) { "Stream resolver is closed" }
                pruneExpiredState(nowMs)
                if (!refresh) {
                    refreshFlights[key]
                        ?.takeIf {
                            it.generation == (videoGenerations[videoId] ?: 0L) && it.networkGeneration == networkGeneration
                        }?.let {
                            flightMap = refreshFlights
                            it.waiters++
                            return@withLock it
                        }
                    resourceCache[key]?.let { cached ->
                        if (nowMs < cached.expiresAtMs - CACHE_EXPIRY_SAFETY_WINDOW_MS) {
                            resourceCache.remove(key)
                            resourceCache[key] = cached
                            YtLogger.d(TAG, "resolve cacheHit client=${cached.resource.clientName}")
                            PlaybackEventLog.info(
                                PlaybackEventKind.RESOLVE,
                                "Reused a cached ${if (cached.resource.isSabr) "SABR" else "direct"} stream from ${cached.resource.clientName}.",
                                videoId = videoId,
                                clientName = cached.resource.clientName,
                                profileId = cached.resource.profileId,
                                details = cached.resource.toPlaybackLogDetails(),
                            )
                            return cached.resource.withFreshClientPlaybackNonce()
                        }
                        resourceCache.remove(key)
                    }
                }
                flightMap[key]?.let { flight ->
                    if (
                        flight.generation == (videoGenerations[videoId] ?: 0L) &&
                        flight.networkGeneration == networkGeneration
                    ) {
                        flight.waiters++
                        return@withLock flight
                    }
                    flightMap.remove(key)
                }
                if (refresh) {
                    resourceCache.keys.removeAll { it.videoId == videoId }
                    videoGenerations[videoId] = (videoGenerations[videoId] ?: 0L) + 1L
                }
                owner = true
                Flight(
                    deferred = CompletableDeferred(resolverScope.coroutineContext[Job]),
                    generation = videoGenerations[videoId] ?: 0L,
                    networkGeneration = networkGeneration,
                ).also { flightMap[key] = it }
            }
        if (owner) {
            val job =
                resolverScope.launch {
                    completeFlight(
                        key,
                        flight,
                        flightMap,
                        videoId,
                        hints,
                        audioQuality,
                        sourceSong,
                        sessionFingerprint,
                        sessionIdentityFingerprint,
                        nowMs,
                    )
                }
            flight.deferred.invokeOnCompletion { job.cancel() }
        }
        return try {
            flight.deferred.await().withFreshClientPlaybackNonce()
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    flight.waiters--
                    if (flight.waiters == 0) {
                        if (flightMap[key] === flight) flightMap.remove(key)
                        flight.deferred.cancel()
                    }
                }
            }
        }
    }

    private suspend fun completeFlight(
        key: CacheKey,
        flight: Flight,
        flightMap: MutableMap<CacheKey, Flight>,
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        sessionFingerprint: String,
        sessionIdentityFingerprint: String,
        nowMs: Long,
    ) {
        try {
            val resource =
                currentExtractionCooldown().run {
                    try {
                        withTimeout(resolveTimeoutMs.milliseconds) {
                            resolveUncached(videoId, hints, audioQuality, sourceSong, sessionFingerprint, key.excludedProfiles)
                        }
                    } catch (error: TimeoutCancellationException) {
                        throw StreamResolveException(
                            reason = StreamResolveException.Reason.NETWORK,
                            message = "Unable to fetch stream data from YouTube.",
                            cause = error,
                        )
                    }
                }
            val expiryMs = resource.expiresAt?.toEpochMilliseconds() ?: (nowMs + FALLBACK_RESOURCE_TTL_MS)
            stateMutex.withLock {
                val currentSessionFingerprint = playbackSessionFingerprint()
                val cacheKey =
                    when {
                        currentSessionFingerprint == sessionFingerprint -> {
                            key
                        }

                        playbackSessionIdentityFingerprint() == sessionIdentityFingerprint -> {
                            key.copy(sessionFingerprint = currentSessionFingerprint)
                        }

                        else -> {
                            null
                        }
                    }
                if (
                    flightMap[key] === flight &&
                    (videoGenerations[videoId] ?: 0L) == flight.generation &&
                    networkGeneration == flight.networkGeneration &&
                    cacheKey?.networkScope == currentNetworkScope
                ) {
                    while (resourceCache.size >= MAX_RESOURCE_CACHE_ENTRIES) {
                        resourceCache.remove(resourceCache.keys.first())
                    }
                    resourceCache[cacheKey] = CachedPlaybackResource(resource, expiryMs)
                }
                if (flightMap[key] === flight) {
                    flightMap.remove(key)
                }
                flight.deferred.complete(resource)
            }
        } catch (e: CancellationException) {
            flight.deferred.cancel(e)
        } catch (e: StreamResolveException) {
            if (e.reason == StreamResolveException.Reason.NO_MUSIC_VIDEO) {
                PlaybackEventLog.debug(
                    PlaybackEventKind.RESOLVE,
                    PlaybackErrorMessages.describe(e),
                    videoId = videoId,
                    details = hints.toPlaybackLogDetails() + innerTube.toPlaybackSessionLogDetails(),
                )
            } else {
                PlaybackEventLog.error(
                    PlaybackEventKind.RESOLVE,
                    PlaybackErrorMessages.describe(e),
                    videoId = videoId,
                    details = hints.toPlaybackLogDetails() + innerTube.toPlaybackSessionLogDetails(),
                    error = e,
                )
            }
            flight.deferred.completeExceptionally(e)
        } catch (e: Exception) {
            val wrapped =
                StreamResolveException(
                    reason = StreamResolveException.Reason.NETWORK,
                    message = "Unable to fetch stream data from YouTube.",
                    cause = e,
                )
            PlaybackEventLog.error(
                PlaybackEventKind.RESOLVE,
                PlaybackErrorMessages.describe(wrapped),
                videoId = videoId,
                details = hints.toPlaybackLogDetails() + innerTube.toPlaybackSessionLogDetails(),
                error = wrapped,
            )
            flight.deferred.completeExceptionally(wrapped)
        } finally {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (flightMap[key] === flight) flightMap.remove(key)
                }
            }
        }
    }

    private fun pruneExpiredState(nowMs: Long) {
        resourceCache.entries.removeAll { (_, cached) ->
            nowMs >= cached.expiresAtMs - CACHE_EXPIRY_SAFETY_WINDOW_MS
        }
        failedProfiles.entries.removeAll { (_, failures) ->
            failures.entries.removeAll { it.value <= nowMs }
            failures.isEmpty()
        }
        val activeVideoIds = resourceCache.keys.mapTo(mutableSetOf()) { it.videoId }
        activeVideoIds += normalFlights.keys.map { it.videoId }
        activeVideoIds += refreshFlights.keys.map { it.videoId }
        activeVideoIds += failedProfiles.keys.map { it.videoId }
        videoGenerations.keys.retainAll(activeVideoIds)
    }

    override suspend fun close() {
        stateMutex.withLock {
            closed = true
            (normalFlights.values + refreshFlights.values).forEach {
                it.deferred.cancel(CancellationException("Stream resolver closed"))
            }
            normalFlights.clear()
            refreshFlights.clear()
        }
        resolverScope.cancel()
    }

    private suspend fun resolveUncached(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        sessionFingerprint: String,
        excludedProfiles: Set<String>,
    ): PlaybackResource {
        val startTime = Clock.System.now().toEpochMilliseconds()
        val failureKey = FailureKey(videoId, sessionFingerprint, networkState().cacheScope())
        val excludedClients =
            stateMutex.withLock {
                val active = failedProfiles[failureKey].orEmpty().filterValues { it > startTime }
                if (active.isEmpty()) failedProfiles.remove(failureKey) else failedProfiles[failureKey] = active.toMutableMap()
                active.keys + excludedProfiles
            }
        YtLogger.d(TAG, "resolve start hints=${hints.diagnosticSummary()} quality=$audioQuality")
        PlaybackEventLog.info(
            PlaybackEventKind.RESOLVE,
            buildString {
                append("Resolving stream for $videoId at $audioQuality")
                hints.playbackClientOverrideId?.let { append(" with DevTools client override $it") }
                if (excludedClients.isNotEmpty()) append(" excluding ${excludedClients.joinToString()}")
                append('.')
            },
            videoId = videoId,
            details =
                buildMap {
                    put("quality", audioQuality.name)
                    if (excludedClients.isNotEmpty()) {
                        put("excluded", excludedClients.joinToString(","))
                    }
                    putAll(hints.toPlaybackLogDetails())
                    putAll(innerTube.toPlaybackSessionLogDetails())
                    tokenProvider?.capabilities?.let { putAll(it.toPlaybackLogDetails()) }
                    putAll(clientHealthTracker.toPlaybackLogDetails())
                },
        )

        val extractVideoId: String
        var counterpartSource: String? = null
        var counterpartPositionMap: CounterpartPositionMap? = null
        var extractWantVideo: Boolean = hints.wantVideo
        val shouldLookupMv =
            hints.wantVideo &&
                sourceSong != null &&
                !sourceSong.isVideoSong &&
                !sourceSong.isLocal &&
                !sourceSong.isPodcast &&
                musicVideoResolver != null
        if (shouldLookupMv) {
            YtLogger.d(TAG, "resolving music video counterpart type=${sourceSong.musicVideoType}")
            val mvResult =
                try {
                    musicVideoResolver.resolveMusicVideo(sourceSong)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    YtLogger.w(TAG, "music video lookup failed type=${e::class.simpleName ?: "unknown"}")
                    MusicVideoResult.Error
                }
            when (mvResult) {
                is MusicVideoResult.Found -> {
                    YtLogger.d(TAG, "resolved music video counterpart source=${mvResult.source}")
                    extractVideoId = mvResult.videoId
                    counterpartSource = mvResult.source.displayName
                    counterpartPositionMap = mvResult.positionMap
                }

                is MusicVideoResult.NotFound, is MusicVideoResult.Error -> {
                    YtLogger.d(
                        TAG,
                        "no counterpart music video found in video mode, falling back to audio stream",
                    )
                    extractVideoId = videoId
                    counterpartSource = null
                    extractWantVideo = false
                }
            }
        } else {
            extractVideoId = videoId
            if (sourceSong?.isVideoSong == true) {
                counterpartSource = "Direct Video Track"
            } else if (sourceSong != null && !sourceSong.isVideoSong && hints.wantVideo && !sourceSong.isPodcast) {
                extractWantVideo = false
            }
        }

        val extractHints =
            hints.copy(
                wantVideo = extractWantVideo,
                isUploaded = if (extractVideoId != videoId) false else hints.isUploaded,
            )

        val isLive = hints.isLive == true || sourceSong?.isLive == true
        if (isLive) {
            val liveResource =
                resolveLiveStream(
                    videoId,
                    extractVideoId,
                    extractWantVideo,
                    counterpartSource,
                    hints.maxVideoHeight,
                    counterpartPositionMap,
                )
            if (liveResource != null) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
                YtLogger.d(TAG, "resolve live stream success client=${liveResource.clientName} elapsed=${elapsed}ms")
                PlaybackEventLog.info(
                    PlaybackEventKind.RESOLVE,
                    "Resolved live stream from ${liveResource.clientName} in ${elapsed}ms.",
                    videoId = videoId,
                    clientName = liveResource.clientName,
                    profileId = liveResource.profileId,
                    details =
                        liveResource.toPlaybackLogDetails() +
                            mapOf("elapsedMs" to elapsed.toString()) +
                            hints.toPlaybackLogDetails() +
                            innerTube.toPlaybackSessionLogDetails(),
                )
                return liveResource
            }
        }

        val coolingClients =
            if (hints.playbackClientOverrideId == null) {
                clientHealthTracker.clientsInCooldown(ClientHealthScope.from(extractHints, innerTube.hasSapCookieAuth())) - excludedClients
            } else {
                emptySet()
            }
        val deferredProfiles =
            if (hints.playbackClientOverrideId == null || hints.playbackClientOverrideId == PlaybackClientCatalog.SABR_FIRST_ID) {
                CUTOFF_PROFILES
            } else {
                emptySet()
            }

        var lastSelectionFailure: StreamResolveException? = null

        suspend fun extract(
            exclusions: Set<String>,
            retryHints: ContentHints = extractHints,
        ): ExtractedStream? =
            try {
                extractor.extract(extractVideoId, retryHints, exclusions, audioQuality)
            } catch (error: StreamResolveException) {
                if (error.reason == StreamResolveException.Reason.NETWORK) throw error
                lastSelectionFailure = error
                null
            }

        suspend fun extractPreferred(retryHints: ContentHints = extractHints): ExtractedStream? =
            run {
                extract(excludedClients + coolingClients + deferredProfiles, retryHints)
                    ?: if (coolingClients.isNotEmpty()) {
                        extract(
                            excludedClients + deferredProfiles +
                                PlaybackClientCatalog.manifests
                                    .filterNot { it.id in coolingClients || it.client.clientName in coolingClients }
                                    .flatMap { listOf("${it.id}__po", "${it.id}__nopo") },
                            retryHints,
                        )
                    } else {
                        null
                    }
            }

        suspend fun extractAutomaticAudio(): ExtractedStream? =
            coroutineScope {
                suspend fun attempt(retryHints: ContentHints): Result<ExtractedStream?> =
                    try {
                        Result.success(extractPreferred(retryHints))
                    } catch (deferred: ExtractionDeferred) {
                        currentCoroutineContext().ensureActive()
                        Result.failure(deferred)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }

                val direct = async { attempt(extractHints) }
                var sabr: Deferred<Result<ExtractedStream?>>? = null
                try {
                    withTimeoutOrNull(DIRECT_AUDIO_TIMEOUT_MS) { direct.await() }?.let { return@coroutineScope it.getOrThrow() }
                    PlaybackEventLog.warn(
                        PlaybackEventKind.RESOLVE,
                        "Direct audio extraction is still pending; also trying SABR before the queue deadline.",
                        videoId = videoId,
                        details = mapOf("directWaitMs" to DIRECT_AUDIO_TIMEOUT_MS.toString()),
                    )
                    sabr = async { attempt(extractHints.copy(sabrFirst = true)) }
                    val pending = mutableListOf(direct, sabr)
                    var failure: Throwable? = null
                    while (pending.isNotEmpty()) {
                        val (completed, result) =
                            select {
                                pending.forEach { candidate -> candidate.onAwait { candidate to it } }
                            }
                        pending.remove(completed)
                        result.getOrNull()?.let { return@coroutineScope it }
                        result.exceptionOrNull()?.let { failure = it }
                    }
                    failure?.let { throw it }
                    null
                } finally {
                    direct.cancel()
                    sabr?.cancel()
                }
            }
        var preferredTimedOut = deferredProfiles.isNotEmpty()
        // When web PO tokens were just rejected, tokenless VISIONOS 0.1 is the client that keeps playing; try it first.
        // Video needs tokenized streams, so music videos fall back to their audio until tokens work again.
        val tokenlessStream =
            if (WebPoTokenHealth.tokensRejected() && !isLive && !extractHints.sabrFirst &&
                extractHints.playbackClientOverrideId == null &&
                extractHints.isUploaded != true && WebPoTokenHealth.TOKENLESS_AUDIO_PROFILE !in excludedClients
            ) {
                withTimeoutOrNull(TOKENLESS_AUDIO_TIMEOUT_MS) {
                    extract(
                        excludedClients,
                        extractHints.copy(playbackClientOverrideId = WebPoTokenHealth.TOKENLESS_AUDIO_CLIENT, wantVideo = false),
                    )
                }.also { if (it == null) lastSelectionFailure = null }
            } else {
                null
            }
        if (tokenlessStream != null) preferredTimedOut = false
        val preferredStream =
            tokenlessStream ?: if (deferredProfiles.isEmpty()) {
                extractPreferred()
            } else {
                withTimeoutOrNull(PREFERRED_CLIENT_TIMEOUT_MS) {
                    (
                        if (!extractHints.wantVideo && !isLive && !extractHints.sabrFirst && extractHints.allowSabr &&
                            extractHints.playbackClientOverrideId == null
                        ) {
                            extractAutomaticAudio()
                        } else {
                            extractPreferred()
                        }
                    ).also { preferredTimedOut = false }
                }
            }
        val preferredFailure = lastSelectionFailure
        val stream =
            (
                preferredStream ?: if (deferredProfiles.isNotEmpty()) {
                    extract(
                        excludedClients +
                            PlaybackClientCatalog.manifests
                                .flatMap { listOf("${it.id}__po", "${it.id}__nopo") }
                                .filterNot { it in deferredProfiles },
                    )
                } else {
                    null
                }
            )
                ?: run {
                    val liveResource =
                        resolveLiveStream(
                            videoId,
                            extractVideoId,
                            extractWantVideo,
                            counterpartSource,
                            hints.maxVideoHeight,
                            counterpartPositionMap,
                        )
                    if (liveResource != null) {
                        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
                        YtLogger.d(
                            TAG,
                            "resolve live stream fallback on unplayable stream success client=${liveResource.clientName} elapsed=${elapsed}ms",
                        )
                        PlaybackEventLog.info(
                            PlaybackEventKind.RESOLVE,
                            "Resolved live stream from ${liveResource.clientName} in ${elapsed}ms.",
                            videoId = videoId,
                            clientName = liveResource.clientName,
                            profileId = liveResource.profileId,
                            details =
                                liveResource.toPlaybackLogDetails() +
                                    mapOf("elapsedMs" to elapsed.toString()) +
                                    hints.toPlaybackLogDetails() +
                                    innerTube.toPlaybackSessionLogDetails(),
                        )
                        return liveResource
                    }
                    lastSelectionFailure?.afterPreferredClients(preferredFailure, preferredTimedOut)?.let { failure ->
                        if (failure.reason == StreamResolveException.Reason.UNAVAILABLE) {
                            clientHealthTracker.forgivePlayabilityFailuresSince(startTime)
                        }
                        throw failure
                    }
                    throw StreamResolveException(
                        reason =
                            if (hints.isExplicit == true) {
                                StreamResolveException.Reason.EXPLICIT_UNSUPPORTED
                            } else {
                                StreamResolveException.Reason.NO_PLAYABLE_STREAM
                            },
                        message = "No playable stream found for this track.",
                    )
                }
        if (isLiveStreamUrl(stream.audioUrl)) {
            val liveResource =
                resolveLiveStream(
                    videoId,
                    extractVideoId,
                    extractWantVideo,
                    counterpartSource,
                    hints.maxVideoHeight,
                    counterpartPositionMap,
                )
            if (liveResource != null) {
                val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
                YtLogger.d(TAG, "resolve live stream fallback success client=${liveResource.clientName} elapsed=${elapsed}ms")
                PlaybackEventLog.info(
                    PlaybackEventKind.RESOLVE,
                    "Resolved live stream from ${liveResource.clientName} in ${elapsed}ms.",
                    videoId = videoId,
                    clientName = liveResource.clientName,
                    profileId = liveResource.profileId,
                    details =
                        liveResource.toPlaybackLogDetails() +
                            mapOf("elapsedMs" to elapsed.toString()) +
                            hints.toPlaybackLogDetails() +
                            innerTube.toPlaybackSessionLogDetails(),
                )
                return liveResource
            }
        }
        if (!usesOriginalAudioTrack(stream.audioUrl, stream.sabrBootstrap?.audioFormat?.xtags)) {
            throw StreamResolveException(
                reason = StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                message = "No playable stream found for this track.",
            )
        }

        val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
        YtLogger.d(
            TAG,
            "resolve success client=${stream.clientName} " +
                "profile=${stream.profileId} itag=${stream.itag} videoItag=${stream.videoItag ?: "none"} elapsed=${elapsed}ms",
        )

        // Keep the queue audio ID as the resource identity even when extracting its music-video counterpart.
        val resourceSessionFingerprint = playbackSessionFingerprint()
        val resource =
            stream.toPlaybackResource(videoId).copy(
                counterpartSource = counterpartSource,
                musicVideoId = if (extractVideoId != videoId) extractVideoId else null,
                musicVideoPositionMap = counterpartPositionMap.takeIf { extractVideoId != videoId },
                onSabrAttestationPending =
                    if (stream.sabrBootstrap != null) {
                        {
                            resolverScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                if (playbackSessionFingerprint() == resourceSessionFingerprint) {
                                    reportPlaybackFailure(
                                        videoId,
                                        stream.clientName,
                                        null,
                                        stream.profileId,
                                        hints,
                                        PlaybackFailureCategory.SABR_ATTESTATION,
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
            )

        PlaybackEventLog.info(
            PlaybackEventKind.RESOLVE,
            buildString {
                append("Resolved a ${if (resource.isSabr) "SABR" else "direct"} stream from ${resource.clientName}")
                append(" (profile ${resource.profileId}")
                resource.itag?.let { append(", itag $it") }
                append(") in ${Clock.System.now().toEpochMilliseconds() - startTime}ms.")
            },
            videoId = videoId,
            clientName = resource.clientName,
            profileId = resource.profileId,
            details =
                resource.toPlaybackLogDetails() +
                    mapOf("elapsedMs" to (Clock.System.now().toEpochMilliseconds() - startTime).toString()) +
                    hints.toPlaybackLogDetails() +
                    innerTube.toPlaybackSessionLogDetails(),
        )
        return resource
    }

    private suspend fun currentExtractionCooldown(): ExtractionCooldown {
        invalidateNetworkScope(networkState())
        return stateMutex.withLock {
            val scope = playbackSessionIdentityFingerprint() to networkGeneration
            if (cooldownScope != scope) {
                cooldownScope = scope
                extractionCooldown = ExtractionCooldown(elapsedMs)
            }
            extractionCooldown
        }
    }

    private fun playbackSessionFingerprint(): String {
        val session = innerTube.sessionSnapshot()
        val values =
            listOf(
                session.cookie.orEmpty(),
                session.visitorData.orEmpty(),
                session.dataSyncId.orEmpty(),
                session.authUser,
                session.locale.hl,
                session.locale.gl,
            )
        return values.joinToString("|") { "${it.length}:$it" }.fingerprint()
    }

    private fun playbackSessionIdentityFingerprint(): String {
        val session = innerTube.sessionSnapshot()
        val values =
            listOf(
                session.cookie.orEmpty(),
                session.dataSyncId.orEmpty(),
                session.authUser,
                session.locale.hl,
                session.locale.gl,
            )
        return values.joinToString("|") { "${it.length}:$it" }.fingerprint()
    }

    private suspend fun resolveLiveStream(
        videoId: String,
        extractVideoId: String,
        extractWantVideo: Boolean,
        counterpartSource: String?,
        maxVideoHeight: Int?,
        counterpartPositionMap: CounterpartPositionMap?,
    ): PlaybackResource? =
        try {
            val response =
                innerTube
                    .player(YouTubeClient.ANDROID, extractVideoId)
                    .bodyLimited<PlayerResponse>(PLAYER_NEXT_RESPONSE_LIMIT)
            val hlsUrl = response.streamingData?.hlsManifestUrl?.takeIf { it.isNotBlank() }
            if (hlsUrl != null) {
                var resolvedAudioUrl = hlsUrl
                var resolvedVideoUrl = if (extractWantVideo) hlsUrl else null
                var resolvedWidth: Int? = null
                var resolvedHeight: Int? = null
                var resolvedBitrate: Long? = null

                try {
                    val manifestText =
                        withTimeoutOrNull(4000.milliseconds) {
                            val client = liveHttpClientFactory()
                            try {
                                client
                                    .get(hlsUrl) {
                                        header("User-Agent", YouTubeClient.ANDROID.userAgent)
                                    }.bodyAsTextLimited(64 * 1024)
                            } finally {
                                client.close()
                            }
                        }
                    if (!manifestText.isNullOrBlank()) {
                        val variants = parseHlsMasterPlaylist(hlsUrl, manifestText)
                        if (variants.isNotEmpty()) {
                            val sorted =
                                variants.sortedWith(
                                    compareByDescending<HlsStreamVariant> { it.height }
                                        .thenByDescending { it.bandwidth },
                                )
                            val targetHeight = Settings.shared.musicVideoQuality.targetHeight ?: maxVideoHeight
                            val chosenVideo =
                                if (targetHeight != null) {
                                    sorted.firstOrNull { it.height <= targetHeight } ?: sorted.last()
                                } else {
                                    sorted.firstOrNull { it.height <= 1080 } ?: sorted.first()
                                }
                            val chosenAudio = sorted.firstOrNull { it.height in 360..720 } ?: chosenVideo

                            resolvedAudioUrl = chosenAudio.url
                            resolvedVideoUrl = chosenVideo.url
                            resolvedWidth = chosenVideo.width
                            resolvedHeight = chosenVideo.height
                            resolvedBitrate = chosenVideo.bandwidth

                            YtLogger.d(
                                TAG,
                                "Resolved live stream variants: " +
                                    "video=${chosenVideo.width}x${chosenVideo.height}@${chosenVideo.bandwidth}bps " +
                                    "audio=${chosenAudio.width}x${chosenAudio.height}",
                            )
                        }
                    }
                } catch (e: Exception) {
                    YtLogger.w(TAG, "Failed to resolve HLS variant: ${e::class.simpleName ?: "unknown"}")
                }

                PlaybackResource(
                    videoId = videoId,
                    url = resolvedAudioUrl,
                    headers = mapOf("User-Agent" to YouTubeClient.ANDROID.userAgent),
                    loudnessDb = null,
                    expiresAt = null,
                    mimeType = "application/x-mpegURL",
                    clientName = YouTubeClient.ANDROID.clientName,
                    profileId = "ANDROID_LIVE",
                    requireBoundedRange = false,
                    videoUrl = if (extractWantVideo || resolvedVideoUrl != null) resolvedVideoUrl else null,
                    videoWidth = resolvedWidth,
                    videoHeight = resolvedHeight,
                    videoBitrate = resolvedBitrate?.toInt(),
                    counterpartSource = counterpartSource,
                    musicVideoId = if (extractVideoId != videoId) extractVideoId else null,
                    musicVideoPositionMap = counterpartPositionMap.takeIf { extractVideoId != videoId },
                )
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.w(TAG, "Android live stream resolution failed type=${e::class.simpleName ?: "unknown"}")
            null
        }

    private fun ExtractedStream.toPlaybackResource(videoId: String) =
        PlaybackResource(
            videoId = videoId,
            url = audioUrl,
            headers = headers,
            loudnessDb = loudnessDb,
            perceptualLoudnessDb = perceptualLoudnessDb,
            expiresAt = expiresAt,
            contentLengthBytes = contentLengthBytes,
            itag = itag,
            mimeType = mimeType,
            codecs = codecs,
            bitrate = bitrate,
            sampleRate = sampleRate,
            durationSeconds = mediaMetadata?.durationSeconds,
            clientName = clientName,
            profileId = profileId,
            requireBoundedRange = requireBoundedRange,
            useRangeChunks = useRangeChunks,
            rangeChunkSizeBytes = rangeChunkSizeBytes,
            playbackTracking = playbackTracking,
            streamDiagnostics = streamDiagnostics,
            videoUrl = videoUrl,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoMimeType = videoMimeType,
            videoCodecs = videoCodecs,
            videoBitrate = videoBitrate,
            videoItag = videoItag,
            videoContentLengthBytes = videoContentLengthBytes,
            sabrBootstrap = sabrBootstrap,
            sabrTokenSupplier = sabrBootstrap?.let { sabrStreamingTokenSupplier(it, profileId, innerTube, tokenProvider) },
            sabrVideoBootstrap = sabrVideoBootstrap,
            availableVideoHeights = availableVideoHeights,
        )
}

/**
 * The last-resort stage only asks tokenless visionOS, so its verdict must not hide what the full client set
 * reported: a track visionOS calls unplayable can still play through WEB_REMIX.
 */
private fun StreamResolveException.afterPreferredClients(
    preferred: StreamResolveException?,
    preferredTimedOut: Boolean,
): StreamResolveException {
    if (preferred === this || (preferred == null && !preferredTimedOut)) return this
    val preferredAttempts =
        preferred?.diagnostics?.attempts
            ?: listOf(
                StreamAttemptDiagnostic(
                    clientName = PlaybackClientCatalog.AUTOMATIC_ID,
                    profileId = null,
                    userAgent = "none",
                    outcome = "preferred_clients_timeout",
                ),
            )
    val unprovenUnavailable = preferred == null && reason == StreamResolveException.Reason.UNAVAILABLE
    return StreamResolveException(
        reason = if (unprovenUnavailable) StreamResolveException.Reason.NO_PLAYABLE_STREAM else (preferred ?: this).reason,
        message = (preferred ?: this).message.takeUnless { unprovenUnavailable } ?: "No playable stream found for this track.",
        diagnostics =
            StreamDiagnostics(
                attempts = preferredAttempts + diagnostics?.attempts.orEmpty(),
                usedAuthenticatedWatchPage =
                    preferred?.diagnostics?.usedAuthenticatedWatchPage == true || diagnostics?.usedAuthenticatedWatchPage == true,
            ),
    )
}

internal fun usesOriginalAudioTrack(
    url: String,
    formatTags: String? = null,
): Boolean {
    val tags = runCatching { Url(url).parameters.getAll("xtags").orEmpty() }.getOrDefault(emptyList()) + listOfNotNull(formatTags)
    val audioContent = tags.mapNotNull(::audioContentTag)
    return audioContent.isEmpty() || audioContent.all { it == "original" }
}

private fun audioContentTag(tags: String): String? {
    tags
        .split(':', '&')
        .firstOrNull { it.startsWith("acont=") }
        ?.let { return it.substringAfter('=') }
    if (tags.length > 2048) return null

    val encoded = tags.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
    val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return null
    var offset = 0
    while (offset < bytes.size) {
        val outerTag = readProtoVarint(bytes, offset, bytes.size) ?: return null
        val outerLength = readProtoVarint(bytes, outerTag.second, bytes.size) ?: return null
        if (outerTag.first != 10 || outerLength.first < 0) return null
        val entryEnd = outerLength.second + outerLength.first
        if (entryEnd > bytes.size) return null

        var entryOffset = outerLength.second
        var key: String? = null
        var value: String? = null
        while (entryOffset < entryEnd) {
            val entryTag = readProtoVarint(bytes, entryOffset, entryEnd) ?: return null
            val length = readProtoVarint(bytes, entryTag.second, entryEnd) ?: return null
            if (entryTag.first and 7 != 2 || length.first < 0) return null
            val fieldEnd = length.second + length.first
            if (fieldEnd > entryEnd) return null
            val text = bytes.decodeToString(length.second, fieldEnd)
            when (entryTag.first ushr 3) {
                1 -> key = text
                2 -> value = text
            }
            entryOffset = fieldEnd
        }
        if (key == "acont") return value
        offset = entryEnd
    }
    return null
}

private fun readProtoVarint(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Pair<Int, Int>? {
    var value = 0
    var offset = start
    var shift = 0
    while (offset < limit && shift < 32) {
        val byte = bytes[offset++].toInt() and 0xff
        value = value or ((byte and 0x7f) shl shift)
        if (byte and 0x80 == 0) return value to offset
        shift += 7
    }
    return null
}

private fun PlaybackNetworkState.cacheScope(): String = "$scope:${if (connected) "validated" else "offline"}"

private fun String.fingerprint(): String =
    DeviceCrypto.sha256(encodeToByteArray()).joinToString("") {
        it.toUByte().toString(16).padStart(2, '0')
    }

internal data class HlsStreamVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecs: String = "",
)

internal fun parseHlsMasterPlaylist(
    masterUrl: String,
    content: String,
): List<HlsStreamVariant> {
    val lines = content.lines()
    val variants = mutableListOf<HlsStreamVariant>()
    var currentInfo: String? = null

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
            currentInfo = trimmed
        } else if (!trimmed.startsWith("#") && trimmed.isNotBlank() && currentInfo != null) {
            val variantUrl =
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    trimmed
                } else {
                    resolveHlsVariantUrl(masterUrl, trimmed) ?: trimmed
                }
            val bandwidth =
                Regex("""(?:#EXT-X-STREAM-INF:|,)BANDWIDTH=(\d+)""")
                    .find(currentInfo)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull() ?: 0L
            val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(currentInfo)
            val width = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val codecs =
                Regex("""CODECS="([^"]+)"""")
                    .find(currentInfo)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()

            variants.add(HlsStreamVariant(variantUrl, bandwidth, width, height, codecs))
            currentInfo = null
        }
    }
    return variants
}

private fun resolveHlsVariantUrl(
    masterUrl: String,
    variant: String,
): String? {
    if (variant.startsWith("https://") || variant.startsWith("http://")) return variant
    val match = Regex("^(https?://[^/]+)(/.*)?$").find(masterUrl) ?: return null
    val origin = match.groupValues[1]
    if (variant.startsWith("/")) return origin + variant
    val path =
        match.groupValues
            .getOrNull(2)
            .orEmpty()
            .substringBeforeLast('/', "")
    return "$origin/$path/$variant".replace(Regex("(?<!:)//+"), "/")
}
