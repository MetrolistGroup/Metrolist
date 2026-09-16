package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.potoken.PoTokenGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Clock

/** The sole stream extraction entry point for the Android app. */
object InnerTubeXPlayer {
    private const val TAG = "InnerTubeXPlayer"
    private const val STREAM_CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_STREAM_TTL_SECONDS = 5 * 60

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var currentBundle: ExtractionBundle? = null

    private val bundleMutex = Mutex()
    private val streamClientFailures = java.util.concurrent.ConcurrentHashMap<String, FailedStreamClients>()

    @Synchronized
    fun initialize(context: Context) {
        if (applicationContext == null) applicationContext = context.applicationContext
    }

    suspend fun prewarm() = bundle().extractor.prewarm()

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        contentHints: ContentHints = ContentHints(),
        allowBoundedRange: Boolean = true,
    ): Result<PlaybackData> =
        try {
            val hints =
                contentHints.copy(
                    isUploaded =
                        contentHints.isUploaded == true ||
                            playlistId == "MLPT" ||
                            playlistId?.contains("MLPT") == true,
                ).withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    allowBoundedRange = allowBoundedRange,
                )
            val excludedClients = failedStreamClients(videoId)
            val stream =
                requireNotNull(
                    bundle().extractor.extract(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excludedClients,
                        audioQuality = audioQuality.toInnerTubeX(connectivityManager),
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    ),
                ) { "InnerTubeX returned no playable stream" }
            check(stream.sabrBootstrap == null) { "SABR is not supported by this playback engine" }
            Result.success(stream.toPlaybackData())
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            val cause = error.cause
            Result.failure(
                if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) {
                    cause
                } else {
                    error
                },
            )
        } catch (error: Exception) {
            Result.failure(error)
        }

    internal fun markStreamClientFailed(
        videoId: String,
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        streamClientFailures.compute(videoId) { _, failures ->
            FailedStreamClients(failures?.clientNames.orEmpty() + clientName, nowMs)
        }
    }

    fun clearStreamClientFailures() {
        streamClientFailures.clear()
    }

    suspend fun refreshAfterStreamRejection(): Boolean = bundle().cipherService.refreshAfterStreamRejection()

    internal fun failedStreamClients(
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Set<String> {
        val failures = streamClientFailures[videoId] ?: return emptySet()
        if ((nowMs - failures.failedAtMs) !in 0 until STREAM_CLIENT_FAILURE_TTL_MS) {
            streamClientFailures.remove(videoId, failures)
            return emptySet()
        }
        return failures.clientNames
    }

    private suspend fun bundle(): ExtractionBundle {
        val transport = YouTube.extractionTransport()
        currentBundle?.takeIf { it.transportGeneration == transport.generation }?.let { return it }
        return bundleMutex.withLock {
            val latestTransport = YouTube.extractionTransport()
            currentBundle?.takeIf { it.transportGeneration == latestTransport.generation }?.let { return@withLock it }
            try {
                currentBundle?.cipherService?.dispose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.log(
                    com.metrolist.innertubex.InnerTubeLogEvent(
                        level = InnerTubeLogLevel.WARN,
                        tag = TAG,
                        message = "old cipher disposal failed",
                        details = mapOf("exceptionType" to (error::class.simpleName ?: "unknown")),
                    ),
                )
            }

            val remoteStore = RemotePlayerConfigStore(latestTransport.httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(latestTransport.httpClient, remoteStore, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        YtConfigParserImpl(
                            latestTransport.httpClient,
                            latestTransport.innerTube,
                            remoteStore,
                            logger,
                        ).withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = latestTransport.innerTube,
                    tokenProvider = tokenProvider,
                    logger = logger,
                )
            ExtractionBundle(latestTransport.generation, cipherService, extractor).also { currentBundle = it }
        }
    }

    private val configRepository: PlayerConfigRepository by lazy {
        AndroidPlayerConfigRepository(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private val poTokenGenerator: PoTokenGenerator by lazy {
        PoTokenGenerator(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private val tokenProvider =
        object : TokenProvider {
            override val capabilities =
                TokenProviderCapabilities(
                    providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    usesWebView = true,
                )

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? =
                poTokenGenerator.getWebClientPoToken(videoId, visitorData)?.let { token ->
                    PoTokenResult(
                        playerRequestToken = token.playerRequestPoToken,
                        streamingDataToken = token.streamingDataPoToken,
                        visitorData = visitorData,
                    )
                }

            override suspend fun close() {
                poTokenGenerator.close()
            }
        }

    private val logger =
        InnerTubeLogger { event ->
            val details = event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
                InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
                InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
                InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
            }
        }

    class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamClient: String,
        val streamHeaders: Map<String, String>,
        val requireBoundedRange: Boolean,
        val rangeChunkSizeBytes: Long,
        val useRangeChunks: Boolean,
    )

    private data class ExtractionBundle(
        val transportGeneration: Long,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    private data class FailedStreamClients(
        val clientNames: Set<String>,
        val failedAtMs: Long,
    )

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) = preferences.edit().putString("json", value).apply()
        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) = preferences.edit().putLong("cached_at_ms", value).apply()
        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) = preferences.edit().putString("source_url", value).apply()
        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) = preferences.edit().putString("etag", value).apply()

        private companion object {
            const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
    }

    internal fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private fun AudioQuality.toInnerTubeX(connectivityManager: ConnectivityManager): InnerTubeXAudioQuality =
        when (this) {
            AudioQuality.HIGH -> InnerTubeXAudioQuality.HIGH
            AudioQuality.LOW -> InnerTubeXAudioQuality.LOW
            AudioQuality.AUTO ->
                if (connectivityManager.isActiveNetworkMetered) {
                    InnerTubeXAudioQuality.LOW
                } else {
                    InnerTubeXAudioQuality.AUTO
                }
        }

    private fun ExtractedStream.toPlaybackData(): PlaybackData {
        val metadata = mediaMetadata
        val tracking = playbackTracking
        val fullMimeType =
            if (codecs.isNullOrBlank()) {
                mimeType.orEmpty()
            } else {
                "${mimeType.orEmpty()}; codecs=\"$codecs\""
            }
        return PlaybackData(
            audioConfig =
                if (loudnessDb != null || perceptualLoudnessDb != null) {
                    PlayerResponse.PlayerConfig.AudioConfig(loudnessDb, perceptualLoudnessDb)
                } else {
                    null
                },
            videoDetails =
                metadata?.let {
                    PlayerResponse.VideoDetails(
                        videoId = videoId,
                        title = it.title,
                        author = it.author,
                        channelId = it.channelId.orEmpty(),
                        lengthSeconds = it.durationSeconds?.toString().orEmpty(),
                        musicVideoType = it.musicVideoType,
                        viewCount = it.viewCount,
                        thumbnail =
                            Thumbnails(
                                it.thumbnails.map { thumbnail ->
                                    Thumbnail(thumbnail.url, thumbnail.width, thumbnail.height)
                                },
                            ),
                    )
                },
            playbackTracking =
                tracking?.let {
                    PlayerResponse.PlaybackTracking(
                        videostatsPlaybackUrl =
                            it.playbackUrl?.let(PlayerResponse.PlaybackTracking::VideostatsPlaybackUrl),
                        videostatsWatchtimeUrl =
                            it.watchtimeUrl?.let(PlayerResponse.PlaybackTracking::VideostatsWatchtimeUrl),
                        atrUrl = null,
                    )
                },
            format =
                PlayerResponse.StreamingData.Format(
                    itag = itag,
                    url = audioUrl,
                    mimeType = fullMimeType,
                    bitrate = bitrate ?: 0,
                    width = null,
                    height = null,
                    contentLength = contentLengthBytes,
                    quality = "",
                    fps = null,
                    qualityLabel = null,
                    averageBitrate = bitrate,
                    audioQuality = null,
                    approxDurationMs = metadata?.durationSeconds?.times(1000)?.toString(),
                    audioSampleRate = sampleRate,
                    audioChannels = null,
                    loudnessDb = loudnessDb,
                    lastModified = null,
                    signatureCipher = null,
                    cipher = null,
                    audioTrack = null,
                ),
            streamUrl = audioUrl,
            streamExpiresInSeconds =
                expiresAt
                    ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                    ?.coerceAtLeast(1)
                    ?: DEFAULT_STREAM_TTL_SECONDS,
            streamClient = clientName,
            streamHeaders = headers,
            requireBoundedRange = this.requireBoundedRange,
            rangeChunkSizeBytes = this.rangeChunkSizeBytes,
            useRangeChunks = this.useRangeChunks,
        )
    }
}
