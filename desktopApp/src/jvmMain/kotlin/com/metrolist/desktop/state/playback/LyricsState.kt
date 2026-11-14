/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.api.isLiveLyricsVideo
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.MusicVideoLyricsRetimer
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTranslationProgress
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.repository.LyricsProviderSearch
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.repository.USER_LYRICS_PROVIDER
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

object LyricsState : DesktopLyricsState()

open class DesktopLyricsState internal constructor(
    private val scope: CoroutineScope = AppState.scope,
    private val currentTrack: () -> SongItem? = { PlayerState.currentTrack },
    private val videoActive: () -> Boolean = {
        PlayerState.isVideoActive || UiState.showVideoViewer || UiState.showVideoFullscreen
    },
    private val counterpartVideoId: () -> String? = { PlayerState.mvVideoId },
    private val resolveCounterpart: suspend (SongItem) -> Pair<String?, CounterpartPositionMap?> =
        { PlayerState.resolveVideoCounterpartForLyrics(it) },
    repository: () -> LyricsRepository = { GlobalContext.get().get() },
) {
    var cancelSelection: (() -> Unit)? = null

    private val lyricsRepository: LyricsRepository by lazy {
        repository()
    }
    var currentLyrics by mutableStateOf<String?>(null)
    var currentLyricsAgents by mutableStateOf<Map<String, AgentInfo>>(emptyMap())
    var currentLyricsSongwriters by mutableStateOf<List<String>>(emptyList())
    var currentLyricsEntries by mutableStateOf<List<LyricsEntry>>(emptyList())
    var isLyricsLoading by mutableStateOf(false)
    var translationProgress by mutableStateOf(LyricsTranslationProgress())
    private val translationFailures = Channel<LyricsTranslationFailure>(Channel.BUFFERED)
    val lyricsTranslationFailures = translationFailures.receiveAsFlow()
    private var failedTranslationRequestId: Long? = null
    var manualAiTranslationEnabled by mutableStateOf(false)
    var currentLyricsProvider by mutableStateOf<String?>(null)
    var currentLyricsOffsetMs by mutableStateOf(0)
    var showLyricsOffsetEditor by mutableStateOf(false)
    var providerSearch by mutableStateOf<LyricsProviderSearch?>(null)
    var currentLyricsSongId by mutableStateOf<String?>(null)
    var queryTitle by mutableStateOf<String?>(null)
    var queryArtist by mutableStateOf<String?>(null)

    var mvLyricsRetimed by mutableStateOf(false)

    private var lyricsFetchJob: Job? = null
    private var lyricsRetimingJob: Job? = null
    private var lyricsOffsetSaveJob: Job? = null
    private var lyricsTimingGeneration = 0L
    private var lyricsRequestId = 0L
    private var lyricsRequestKey: List<Any?>? = null
    private var lyricsRequestCacheOnly = false
    private var lyricsMissExpiresAt: Long? = null

    private val defaultLyricsOrder = LyricsProvider.userConfigurable

    val lyricsProviderOrder: List<LyricsProvider>
        get() =
            LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder).takeIf { it.isNotEmpty() }
                ?: defaultLyricsOrder

    val lyricsEnabledProviders: Set<LyricsProvider>
        get() = LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders)

    fun isMvActive(song: SongItem? = currentTrack()): Boolean {
        if (song == null) return false
        return song.isVideoSong || videoActive()
    }

    fun activeLyricsVideoId(song: SongItem): String =
        if (isMvActive(song)) {
            counterpartVideoId() ?: song.id
        } else {
            song.id
        }

    fun syncLyricsOffsetForVideoMode() {
        val song = currentTrack() ?: return
        val targetId = activeLyricsVideoId(song)
        val repository = GlobalContext.getOrNull()?.getOrNull<LyricsRepository>() ?: return
        scope.launch {
            val offset = repository.getLyricsOffset(targetId)
            if (currentTrack()?.id == song.id) {
                currentLyricsOffsetMs = offset
            }
        }
    }

    fun clearLyrics() {
        ++lyricsRequestId
        lyricsRequestKey = null
        lyricsMissExpiresAt = null
        lyricsFetchJob?.cancel()
        providerSearch?.cancel()
        lyricsRetimingJob?.cancel()
        lyricsOffsetSaveJob?.cancel()
        currentLyrics = null
        currentLyricsAgents = emptyMap()
        currentLyricsSongwriters = emptyList()
        currentLyricsEntries = emptyList()
        translationProgress = LyricsTranslationProgress()
        manualAiTranslationEnabled = false
        currentLyricsSongId = null
        isLyricsLoading = false
        currentLyricsProvider = null
        currentLyricsOffsetMs = 0
        showLyricsOffsetEditor = false
        providerSearch = null
        queryTitle = null
        queryArtist = null
        mvLyricsRetimed = false
    }

    fun toggleLyricsDrawer() {
        val showLyrics = !UiState.showLyricsDrawer
        UiState.showLyricsDrawer = showLyrics
        if (showLyrics) UiState.showDeviceConnectPanel = false
        if (showLyrics && currentLyrics == null && !isLyricsLoading) {
            currentTrack()?.let(::fetchLyrics)
        }
    }

    fun fetchLyrics(
        song: SongItem,
        skipCache: Boolean = false,
        customTitle: String? = null,
        customArtist: String? = null,
        cacheOnly: Boolean = false,
    ) {
        val defaultArtist = song.artists.joinToString { it.name }
        val resolvedTitle = customTitle?.takeIf { it.isNotBlank() } ?: song.title
        val resolvedArtist = customArtist?.trim() ?: defaultArtist
        val isCustomQuery =
            (customTitle != null || customArtist != null) &&
                LyricsUtils.isCustomLyricsQuery(resolvedTitle, resolvedArtist, song.title, defaultArtist)
        val resolvedDuration = song.durationMillis().takeUnless { isCustomQuery }
        val resolvedAlbum = song.album?.name.takeUnless { isCustomQuery }
        val preferred =
            LyricsProvider.preferredProvidersForFetch(
                lyricsProviderOrder,
                lyricsEnabledProviders,
            )
        val isVideoContext = !isCustomQuery && isMvActive(song)
        val preferVideoProviders = !isCustomQuery && isLiveLyricsVideo(song.musicVideoType, song.title)
        val requestKey =
            listOf(
                song.id,
                resolvedTitle,
                resolvedArtist,
                isCustomQuery,
                resolvedDuration,
                resolvedAlbum,
                preferred,
                isVideoContext,
                preferVideoProviders,
                Settings.shared.preferWordSyncedLyrics,
            )
        if (!skipCache && lyricsRequestKey == requestKey && (cacheOnly || !lyricsRequestCacheOnly) &&
            (
                lyricsFetchJob?.isActive == true || !currentLyrics.isNullOrBlank() ||
                    lyricsMissExpiresAt?.let { Clock.System.now().toEpochMilliseconds() < it } == true
            )
        ) {
            return
        }
        lyricsRequestKey = requestKey
        lyricsRequestCacheOnly = cacheOnly
        lyricsMissExpiresAt = null
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = lyricsRetimingJob
        previousFetchJob?.cancel()
        providerSearch?.cancel()
        previousRetimeJob?.cancel()
        lyricsOffsetSaveJob?.cancel()
        val keepCurrentLyrics = skipCache && currentLyricsSongId == song.id && currentLyricsEntries.isNotEmpty()
        if (!keepCurrentLyrics) {
            currentLyrics = null
            currentLyricsAgents = emptyMap()
            currentLyricsSongwriters = emptyList()
            currentLyricsEntries = emptyList()
            manualAiTranslationEnabled = false
            currentLyricsProvider = null
            currentLyricsOffsetMs = 0
            mvLyricsRetimed = false
        }
        translationProgress = LyricsTranslationProgress()
        currentLyricsSongId = song.id
        showLyricsOffsetEditor = false
        providerSearch = null
        queryTitle = resolvedTitle
        queryArtist = resolvedArtist
        isLyricsLoading = skipCache
        lyricsFetchJob =
            scope.launch {
                withContext(Dispatchers.IO) {
                    previousFetchJob?.join()
                    previousRetimeJob?.join()
                }
                if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                try {
                    suspend fun cachedMissExpiry(): Long? =
                        withContext(Dispatchers.IO) {
                            lyricsRepository.getCachedLyricsMissExpiry(
                                videoId = song.id.takeUnless { isCustomQuery }.orEmpty(),
                                title = resolvedTitle,
                                artist = resolvedArtist,
                                duration = resolvedDuration,
                                album = resolvedAlbum,
                                preferredProviders = preferred,
                                isVideoContext = isVideoContext,
                                preferVideoProviders = preferVideoProviders,
                            )
                        }
                    val cachedMiss = if (skipCache) null else cachedMissExpiry()
                    val offsetMs = withContext(Dispatchers.IO) { lyricsRepository.getLyricsOffset(activeLyricsVideoId(song)) }
                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                    lyricsMissExpiresAt = cachedMiss
                    if (!keepCurrentLyrics) currentLyricsOffsetMs = offsetMs
                    if (cachedMiss != null) return@launch
                    isLyricsLoading = true
                    val publicationContext = currentCoroutineContext()

                    var publishedResult: LyricsWithProvider? = null
                    var publishedDocument: MlxDocument? = null

                    suspend fun publishLyrics(result: LyricsWithProvider?): MlxDocument? {
                        if (result != null && result == publishedResult) return publishedDocument
                        if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return null
                        val document = withContext(Dispatchers.Default) { result?.lyrics?.let(LyricsUtils::parseMlxDocument) }
                        val parsed =
                            document?.lines
                                ?: withContext(Dispatchers.Default) { result?.lyrics?.let(LyricsUtils::parseLyrics).orEmpty() }
                        var published = false

                        fun publishEntries(entries: List<LyricsEntry>) {
                            if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return
                            if (published) {
                                if (!mvLyricsRetimed) currentLyricsEntries = entries
                                return
                            }
                            published = true
                            publishedResult = result
                            publishedDocument = document
                            lyricsRetimingJob?.cancel()
                            if (currentLyrics == null) currentLyricsOffsetMs = offsetMs
                            currentLyrics = result?.lyrics
                            currentLyricsAgents = document?.agents ?: emptyMap()
                            currentLyricsSongwriters = document?.song?.songwriters.orEmpty()
                            currentLyricsEntries = entries
                            currentLyricsProvider = result?.provider
                            mvLyricsRetimed = false
                            isLyricsLoading = skipCache
                        }
                        val prepared =
                            enrichLyrics(
                                song.id,
                                parsed,
                                requestId,
                                allowRemoteTranslation = false,
                                onDictionaryPreparation = {
                                    withContext(publicationContext) {
                                        if (!published && currentLyricsEntries.isEmpty()) publishEntries(parsed)
                                    }
                                },
                            )
                        publishEntries(prepared)
                        return document
                    }
                    val result =
                        withContext(Dispatchers.IO) {
                            if (cacheOnly) {
                                lyricsRepository.getCachedLyrics(song.id, preferred, isVideoContext)
                            } else {
                                lyricsRepository.getLyrics(
                                    videoId = song.id,
                                    title = resolvedTitle,
                                    artist = resolvedArtist,
                                    duration = resolvedDuration,
                                    album = resolvedAlbum,
                                    preferredProviders = preferred,
                                    skipCache = skipCache,
                                    isVideoContext = isVideoContext,
                                    preferVideoProviders = preferVideoProviders,
                                    ignoreVideoIdentity = isCustomQuery,
                                    onLyricsAvailable = { candidate ->
                                        withContext(publicationContext) { publishLyrics(candidate) }
                                    },
                                )
                            }
                        }

                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch

                    if (result == null) {
                        val expiresAt = cachedMissExpiry()
                        if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                        lyricsMissExpiresAt = expiresAt
                        return@launch
                    }

                    val document = publishLyrics(result)
                    if (cacheOnly) return@launch
                    val parsed = document?.lines ?: withContext(Dispatchers.Default) { LyricsUtils.parseLyrics(result.lyrics) }
                    val mlxSongwriters = document?.song?.songwriters.orEmpty()
                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch

                    if (mlxSongwriters.isEmpty()) {
                        launch {
                            val ytSongwriters = withContext(Dispatchers.IO) { lyricsRepository.getSongwriters(song.id) }
                            if (currentTrack()?.id == song.id && requestId == lyricsRequestId && ytSongwriters.isNotEmpty()) {
                                currentLyricsSongwriters = ytSongwriters
                            }
                        }
                    }

                    val enriched = enrichLyrics(song.id, parsed, requestId)
                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                    if (enriched != parsed && !mvLyricsRetimed) currentLyricsEntries = enriched
                    Logger.i(
                        "LyricsRetimer",
                        "fetchLyrics loaded lyrics for songId=${song.id}: isVideoContext=$isVideoContext " +
                            "(isVideoSong=${song.isVideoSong}, videoActive=${videoActive()}), " +
                            "hasMlxDoc=${document != null}",
                    )

                    val timingGeneration = lyricsTimingGeneration
                    if (isCurrentLyricsTiming(song, requestId, timingGeneration, videoMode = true) &&
                        document != null && !document.meta.ignoreRetiming
                    ) {
                        val (mvId, positionMap) = resolveCounterpart(song)
                        Logger.i(
                            "LyricsRetimer",
                            "fetchLyrics: Initiating retiming for songId=${song.id}, mvId=$mvId, hasPositionMap=${positionMap != null}",
                        )
                        val retimedDoc =
                            if (positionMap != null) {
                                withContext(Dispatchers.Default) {
                                    MusicVideoLyricsRetimer.retimeLyricsWithPositionMap(document, positionMap)
                                }
                            } else if (mvId != null) {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        lyricsRepository.getRetimedMvLyrics(
                                            videoId = mvId,
                                            richLyrics = document,
                                            title = song.title,
                                            artist = song.artists.joinToString { it.name },
                                            duration = song.durationMillis(),
                                            album = song.album?.name,
                                        )
                                    }
                                }.onFailure {
                                    Logger.w("LyricsRetimer", "fetchLyrics: getRetimedMvLyrics failed: ${it::class.simpleName}")
                                }.getOrNull()
                            } else {
                                null
                            }
                        if (retimedDoc != null && isCurrentLyricsTiming(song, requestId, timingGeneration, videoMode = true)) {
                            Logger.i(
                                "LyricsRetimer",
                                "fetchLyrics: Retiming succeeded (${retimedDoc.lines.size} lines retimed). Updating currentLyricsEntries.",
                            )
                            val enrichedRetimed =
                                enrichLyrics(song.id, retimedDoc.lines, requestId)
                            if (!isCurrentLyricsTiming(song, requestId, timingGeneration, videoMode = true)) return@launch
                            currentLyricsEntries = enrichedRetimed
                            currentLyricsAgents = retimedDoc.agents
                            currentLyricsSongwriters = retimedDoc.song.songwriters
                            mvLyricsRetimed = true
                        } else {
                            Logger.i(
                                "LyricsRetimer",
                                "fetchLyrics: Retiming result=${retimedDoc != null}, trackMatches=${currentTrack()?.id == song.id}",
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Logger.w("LyricsState", "Lyrics lookup failed: ${error::class.simpleName}")
                } finally {
                    if (currentTrack()?.id == song.id && requestId == lyricsRequestId) {
                        if (currentLyrics.isNullOrBlank() && lyricsMissExpiresAt == null) lyricsRequestKey = null
                        isLyricsLoading = false
                    }
                }
            }
    }

    fun fetchProviderResults(
        song: SongItem,
        customTitle: String? = null,
        customArtist: String? = null,
    ) {
        val defaultArtist = song.artists.joinToString { it.name }
        val resolvedTitle = customTitle?.takeIf { it.isNotBlank() } ?: song.title
        val resolvedArtist = customArtist?.trim() ?: defaultArtist
        val isCustomQuery =
            (customTitle != null || customArtist != null) &&
                LyricsUtils.isCustomLyricsQuery(resolvedTitle, resolvedArtist, song.title, defaultArtist)
        providerSearch?.cancel()
        queryTitle = resolvedTitle
        queryArtist = resolvedArtist
        providerSearch =
            LyricsProviderSearch(scope, Settings.shared.lyricsProviderOrder) { provider ->
                lyricsRepository.getProviderResults(
                    videoId = song.id.takeUnless { isCustomQuery && provider != LyricsProvider.USER },
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    duration = song.durationMillis().takeUnless { isCustomQuery },
                    album = song.album?.name.takeUnless { isCustomQuery },
                    preferredProviders = listOf(provider),
                    failOnError = true,
                )
            }
    }

    fun selectProviderResult(
        song: SongItem,
        result: LyricsProviderResult,
    ) {
        lyricsRequestCacheOnly = false
        lyricsMissExpiresAt = null
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = lyricsRetimingJob
        previousFetchJob?.cancel()
        previousRetimeJob?.cancel()
        providerSearch?.cancel()
        isLyricsLoading = true
        translationProgress = LyricsTranslationProgress()
        manualAiTranslationEnabled = false
        lyricsFetchJob =
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        previousFetchJob?.join()
                        previousRetimeJob?.join()
                    }
                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                    val effectiveResult = withContext(Dispatchers.IO) { lyricsRepository.selectProviderResult(song.id, result) }
                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                    val document = withContext(Dispatchers.Default) { LyricsUtils.parseMlxDocument(effectiveResult.lyrics) }
                    val parsed = document?.lines ?: withContext(Dispatchers.Default) { LyricsUtils.parseLyrics(effectiveResult.lyrics) }
                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                    currentLyrics = effectiveResult.lyrics
                    currentLyricsAgents = document?.agents ?: emptyMap()
                    val mlxSongwriters = document?.song?.songwriters.orEmpty()
                    currentLyricsSongwriters = mlxSongwriters
                    currentLyricsEntries = parsed
                    currentLyricsProvider = effectiveResult.providerName
                    mvLyricsRetimed = false

                    if (mlxSongwriters.isEmpty()) {
                        launch {
                            val ytSongwriters = withContext(Dispatchers.IO) { lyricsRepository.getSongwriters(song.id) }
                            if (currentTrack()?.id == song.id && requestId == lyricsRequestId && ytSongwriters.isNotEmpty()) {
                                currentLyricsSongwriters = ytSongwriters
                            }
                        }
                    }
                    val enriched = enrichLyrics(song.id, parsed, requestId)
                    if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                    if (enriched != parsed) currentLyricsEntries = enriched
                    retimeCurrentLyricsIfPossible()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Logger.e("LyricsState", "Failed to select lyrics provider ${result.providerName}", error)
                } finally {
                    if (currentTrack()?.id == song.id && requestId == lyricsRequestId) {
                        isLyricsLoading = false
                    }
                }
            }
    }

    fun updateLyricsOffset(offsetMs: Int) {
        val song = currentTrack() ?: return
        val targetId = activeLyricsVideoId(song)
        currentLyricsOffsetMs = offsetMs
        lyricsOffsetSaveJob?.cancel()
        lyricsOffsetSaveJob =
            scope.launch {
                delay(150.milliseconds)
                withContext(Dispatchers.IO) {
                    lyricsRepository.setLyricsOffset(targetId, offsetMs)
                }
            }
    }

    fun refreshEditedLyrics(song: SongItem) {
        if (currentTrack()?.id != song.id) return
        lyricsRequestKey = null
        fetchLyrics(song)
    }

    fun saveCustomLyrics(
        song: SongItem,
        lyricsText: String,
    ) {
        lyricsRequestCacheOnly = false
        lyricsMissExpiresAt = null
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = lyricsRetimingJob
        previousFetchJob?.cancel()
        previousRetimeJob?.cancel()
        isLyricsLoading = false
        translationProgress = LyricsTranslationProgress()
        manualAiTranslationEnabled = false
        lyricsFetchJob =
            scope.launch {
                withContext(Dispatchers.IO) {
                    previousFetchJob?.join()
                    previousRetimeJob?.join()
                }
                if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                withContext(Dispatchers.IO) { lyricsRepository.saveCustomLyrics(song.id, lyricsText) }
                if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                val document = withContext(Dispatchers.Default) { LyricsUtils.parseMlxDocument(lyricsText) }
                val parsed = document?.lines ?: withContext(Dispatchers.Default) { LyricsUtils.parseLyrics(lyricsText) }
                if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                currentLyrics = lyricsText
                currentLyricsAgents = document?.agents ?: emptyMap()
                currentLyricsSongwriters = document?.song?.songwriters ?: emptyList()
                currentLyricsEntries = parsed
                currentLyricsProvider = USER_LYRICS_PROVIDER
                mvLyricsRetimed = false
                val enriched = enrichLyrics(song.id, parsed, requestId)
                if (currentTrack()?.id != song.id || requestId != lyricsRequestId) return@launch
                if (enriched != parsed) currentLyricsEntries = enriched
                retimeCurrentLyricsIfPossible()
            }
    }

    fun setManualLyricsTranslation(enabled: Boolean) {
        val songId = currentTrack()?.id ?: return
        if (currentLyricsSongId != songId || currentLyricsEntries.isEmpty()) return
        val entries = currentLyricsEntries
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = lyricsRetimingJob
        previousFetchJob?.cancel()
        previousRetimeJob?.cancel()
        lyricsRequestCacheOnly = false
        manualAiTranslationEnabled = enabled
        translationProgress = LyricsTranslationProgress()
        lyricsFetchJob =
            scope.launch {
                previousFetchJob?.join()
                previousRetimeJob?.join()
                if (currentTrack()?.id != songId || requestId != lyricsRequestId) return@launch
                try {
                    val enriched = enrichLyrics(songId, entries, requestId)
                    if (currentTrack()?.id == songId && requestId == lyricsRequestId) currentLyricsEntries = enriched
                } finally {
                    if (currentTrack()?.id == songId && requestId == lyricsRequestId) isLyricsLoading = false
                }
            }
    }

    private suspend fun enrichLyrics(
        songId: String,
        entries: List<LyricsEntry>,
        requestId: Long,
        allowRemoteTranslation: Boolean = true,
        onDictionaryPreparation: suspend () -> Unit = {},
    ): List<LyricsEntry> {
        try {
            return withContext(Dispatchers.IO) {
                lyricsRepository.enrichLyricsEntries(
                    songId,
                    entries,
                    forceAiTranslation = manualAiTranslationEnabled,
                    allowRemoteTranslation = allowRemoteTranslation && failedTranslationRequestId != requestId,
                    onDictionaryPreparation = onDictionaryPreparation,
                    onTranslationProgress = { progress ->
                        withContext(Dispatchers.Main) {
                            if (currentTrack()?.id == songId && requestId == lyricsRequestId) translationProgress = progress
                        }
                    },
                    onTranslationFailure = { failure ->
                        withContext(Dispatchers.Main) {
                            if (currentTrack()?.id == songId && requestId == lyricsRequestId) {
                                failedTranslationRequestId = requestId
                                manualAiTranslationEnabled = false
                                translationProgress = LyricsTranslationProgress()
                                translationFailures.trySend(failure)
                            }
                        }
                    },
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (currentTrack()?.id == songId && requestId == lyricsRequestId) translationProgress = LyricsTranslationProgress()
            }
        }
    }

    fun retimeCurrentLyricsIfPossible() {
        if (lyricsRequestCacheOnly) return
        val song =
            currentTrack() ?: run {
                return
            }
        val rawLyricsText =
            currentLyrics ?: run {
                return
            }
        if (!isMvActive(song)) return
        val timingGeneration = ++lyricsTimingGeneration
        lyricsRetimingJob?.cancel()
        if (mvLyricsRetimed) {
            return
        }
        val songId = song.id
        val requestId = lyricsRequestId

        lyricsRetimingJob =
            scope.launch {
                val document = withContext(Dispatchers.Default) { LyricsUtils.parseMlxDocument(rawLyricsText) } ?: return@launch
                if (document.meta.ignoreRetiming) return@launch
                val (mvId, positionMap) = resolveCounterpart(song)
                val retimedDoc =
                    if (positionMap != null) {
                        withContext(Dispatchers.Default) {
                            MusicVideoLyricsRetimer.retimeLyricsWithPositionMap(document, positionMap)
                        }
                    } else if (mvId != null) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                lyricsRepository.getRetimedMvLyrics(
                                    videoId = mvId,
                                    richLyrics = document,
                                    title = song.title,
                                    artist = song.artists.joinToString { it.name },
                                    duration = song.durationMillis(),
                                    album = song.album?.name,
                                )
                            }
                        }.onFailure {
                        }.getOrNull()
                    } else {
                        null
                    }
                if (retimedDoc != null && isCurrentLyricsTiming(song, requestId, timingGeneration, videoMode = true)) {
                    val enriched = enrichLyrics(songId, retimedDoc.lines, requestId)
                    if (isCurrentLyricsTiming(song, requestId, timingGeneration, videoMode = true)) {
                        currentLyricsEntries = enriched
                        currentLyricsAgents = retimedDoc.agents
                        currentLyricsSongwriters = retimedDoc.song.songwriters.takeIf { it.isNotEmpty() } ?: currentLyricsSongwriters
                        mvLyricsRetimed = true
                    }
                }
            }
    }

    fun restoreOriginalLyricsIfRetimed() {
        val song = currentTrack()
        if (song?.isVideoSong == true) return
        val timingGeneration = ++lyricsTimingGeneration
        lyricsRetimingJob?.cancel()
        if (song == null) return
        val rawLyricsText = currentLyrics ?: return
        if (!mvLyricsRetimed) return
        val songId = song.id
        val requestId = lyricsRequestId
        lyricsRetimingJob =
            scope.launch {
                val document = withContext(Dispatchers.Default) { LyricsUtils.parseMlxDocument(rawLyricsText) } ?: return@launch
                val enriched = enrichLyrics(songId, document.lines, requestId)
                if (isCurrentLyricsTiming(song, requestId, timingGeneration, videoMode = false)) {
                    currentLyricsEntries = enriched
                    currentLyricsAgents = document.agents
                    currentLyricsSongwriters = document.song.songwriters.takeIf { it.isNotEmpty() } ?: currentLyricsSongwriters
                    mvLyricsRetimed = false
                }
            }
    }

    private fun isCurrentLyricsTiming(
        song: SongItem,
        requestId: Long,
        generation: Long,
        videoMode: Boolean,
    ): Boolean =
        currentTrack()?.id == song.id && requestId == lyricsRequestId &&
            generation == lyricsTimingGeneration && isMvActive(song) == videoMode

    private fun SongItem.durationMillis(): Long? = duration?.takeIf { it > 0 }?.times(1000L)
}
