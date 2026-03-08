package com.metrolist.shared.viewmodels

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.api.artwork.CanvasArtworkState
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.api.artwork.isEnabled
import com.metrolist.shared.api.artwork.youtubeCanvasPlaybackClips
import com.metrolist.shared.api.isLiveLyricsVideo
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.MusicVideoLyricsRetimer
import com.metrolist.shared.api.lyrics.lyricsTimingKind
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.MusicTrackResult
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.api.musicvideo.MusicVideoResult
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.model.AgentInfo
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsTranslationProgress
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxMeta
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.canSwitchToMusicVideo
import com.metrolist.shared.model.isLiveType
import com.metrolist.shared.model.isOfficialVideoType
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.lyricsProviderLabel
import com.metrolist.shared.model.previewAspectRatio
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.PlayerSession
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.isRecommendedQueueSource
import com.metrolist.shared.repository.LyricsProviderSearch
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.repository.USER_LYRICS_PROVIDER
import com.metrolist.shared.repository.isLocalLyricsProvider
import com.metrolist.shared.repository.prefersLyrics
import com.metrolist.shared.settings.AnimatedCoverResolution
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.PlayerThumbnailStyle
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.YouTubeCanvasMode
import com.metrolist.shared.settings.targetHeight
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.theme.DefaultThemeColor
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.utils.collectLatestWhileVisible
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

data class PlayerArtworkColors(
    val dominant: Color,
    val gradient: List<Color>,
)

interface PlayerPlatformServices {
    val supportsCanvasPreloading: Boolean get() = false

    val artworkVisible: Flow<Boolean> get() = flowOf(true)

    suspend fun extractArtworkColors(
        songId: String,
        thumbnailUrl: String,
    ): PlayerArtworkColors?

    fun onMemoryPressure(trim: () -> Unit) {}

    fun shareText(text: String)

    fun copyText(text: String) {}

    /** Returns null on platforms without a Spotify client. */
    suspend fun spotifyCanvas(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        primaryArtist: String?,
    ): String?

    fun musicVideoCanvasHints(song: SongItem): ContentHints

    suspend fun cachedMusicVideoCanvas(
        songId: String,
        mode: YouTubeCanvasMode,
        quality: MusicVideoQuality,
    ): PlayerViewModel.MusicVideoCanvas? = null

    suspend fun prepareMusicVideoCanvas(
        songId: String,
        canvas: PlayerViewModel.MusicVideoCanvas,
        mode: YouTubeCanvasMode,
        quality: MusicVideoQuality,
        preload: Boolean,
    ): PlayerViewModel.MusicVideoCanvas = canvas

    suspend fun cachedAnimatedCover(
        songId: String,
        source: CanvasContentSource,
        resolution: AnimatedCoverResolution,
        tall: Boolean = false,
    ): String? = null

    suspend fun preloadAnimatedCover(
        songId: String,
        source: CanvasContentSource,
        url: String,
        tall: Boolean = false,
    ): String = url
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val playerConnection: PlayerSession,
    private val imageLoader: ImageLoader,
    private val platformContext: PlatformContext,
    private val platform: PlayerPlatformServices,
    private val lyricsRepository: LyricsRepository,
    private val animatedArtworkService: AnimatedArtworkService,
    private val musicVideoResolver: MusicVideoResolver,
    private val viewModelScope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val translationFailures = Channel<LyricsTranslationFailure>(Channel.BUFFERED)
    val lyricsTranslationFailures = translationFailures.receiveAsFlow()
    private var failedTranslationRequestId: Long? = null

    fun currentPositionMs(): Long = playerConnection.currentPositionMs()

    val currentPosition: StateFlow<Long> = playerConnection.currentPosition
    val bufferedPosition: StateFlow<Long> = playerConnection.bufferedPosition
    val playbackSpeed: StateFlow<Float> = playerConnection.playbackSpeedFlow
    val isPlaybackLoading: StateFlow<Boolean> = playerConnection.isPlaybackLoading
    val wantsPlaybackState: StateFlow<Boolean> = playerConnection.wantsPlaybackState
    val progress: StateFlow<Float> = playerConnection.progress
    val bufferedProgress: StateFlow<Float> = playerConnection.bufferedProgress

    private val gradientColorsCache = linkedMapOf<String, PlayerArtworkColors>()
    private var lastProcessedSongId: String? = null
    private var lyricsFetchJob: Job? = null
    private val animatedCoverFetch = CanvasFetchRequest(viewModelScope)
    private val musicVideoCanvasFetch = CanvasFetchRequest(viewModelScope)
    private val canvasVisible = MutableStateFlow(false)
    private var counterpartFetchJob: Job? = null
    private var albumFetchJob: Job? = null
    private var retimeLyricsJob: Job? = null
    private var lyricsOffsetSaveJob: Job? = null
    private var lyricsRequestId = 0L
    private var lyricsRequestKey: String? = null
    private var lyricsRequestCacheOnly = false

    private val canvasPlaybackRecovery = CanvasPlaybackRecovery()
    private val canvasPreloader = CanvasPreloader(viewModelScope, ::preloadCanvas)

    init {
        platform.onMemoryPressure {
            gradientColorsCache.clear()
            canvasPreloader.clear()
        }
        combine(playerConnection.currentSong, SongMetadataState.revision) { song, _ ->
            song?.let(SongMetadataState::apply)
        }.onEach { song ->
            val isNewSong = song?.id != lastProcessedSongId
            if (isNewSong) {
                canvasPlaybackRecovery.reset()
                lastProcessedSongId = song?.id
                lyricsFetchJob?.cancel()
                _uiState.value.lyricsProviderSearch?.cancel()
                animatedCoverFetch.invalidate()
                musicVideoCanvasFetch.invalidate()
                counterpartFetchJob?.cancel()
                albumFetchJob?.cancel()
                retimeLyricsJob?.cancel()
                lyricsRequestId++
                lyricsRequestKey = null
            }

            val preloaded = canvasPreloader.ready.value[song?.id].takeIf { Settings.shared.isAnimatedArtworkAllowedOnNetwork() }
            val isSongLiked = song?.let { SongLikeState.isLiked(it.id) } ?: false
            val previousAlbumId = _uiState.value.currentSong?.albumId
            val newAlbumId = song?.album?.id
            val sameAlbum = isNewSong && newAlbumId != null && newAlbumId == previousAlbumId
            val previousAlbum = _uiState.value.currentSong?.album
            val previousSpotifyAnimatedCoverUrl =
                _uiState.value.currentSong
                    ?.spotifyAnimatedCoverUrl
                    .takeUnless { isNewSong }
            val previousAppleMusicAnimatedCoverUrl =
                _uiState.value.currentSong
                    ?.appleMusicAnimatedCoverUrl
                    .takeIf { !isNewSong || sameAlbum }
            val previousAppleMusicTallAnimatedCoverUrl =
                _uiState.value.currentSong
                    ?.appleMusicTallAnimatedCoverUrl
                    .takeIf { !isNewSong || sameAlbum }
            val previousMusicVideoCanvas =
                _uiState.value.currentSong
                    ?.musicVideoCanvas
                    .takeUnless { isNewSong }
            val previousCanvasState =
                _uiState.value.currentSong
                    ?.canvasState
                    .takeUnless { isNewSong } ?: CanvasArtworkState.NOT_REQUESTED
            _uiState.value =
                _uiState.value.copy(
                    isResourceLive = if (isNewSong) false else _uiState.value.isResourceLive,
                    isLiked = isSongLiked,
                    currentSong =
                        song?.let {
                            SongUiModel(
                                id = it.id,
                                title = it.title,
                                artists = it.artists,
                                thumbnail = it.thumbnail,
                                duration = it.duration.toDurationMillis(),
                                albumId = it.album?.id ?: previousAlbumId.takeUnless { isNewSong },
                                album = it.album?.name ?: previousAlbum.takeUnless { isNewSong },
                                isExplicit = it.isExplicit,
                                explicitness = it.artworkExplicitness,
                                isUploaded = it.isUploaded,
                                isVideoSong = it.isVideoSong,
                                musicVideoType = it.musicVideoType,
                                isLiked = isSongLiked,
                                spotifyAnimatedCoverUrl = previousSpotifyAnimatedCoverUrl ?: preloaded?.spotifyAnimatedCoverUrl,
                                appleMusicAnimatedCoverUrl = previousAppleMusicAnimatedCoverUrl ?: preloaded?.appleMusicAnimatedCoverUrl,
                                appleMusicTallAnimatedCoverUrl =
                                    previousAppleMusicTallAnimatedCoverUrl ?: preloaded?.appleMusicTallAnimatedCoverUrl,
                                musicVideoCanvas = previousMusicVideoCanvas ?: preloaded?.musicVideoCanvas,
                                canvasState = preloaded?.canvasState ?: previousCanvasState,
                                pendingCanvasSources =
                                    if (isNewSong) {
                                        emptySet()
                                    } else {
                                        _uiState.value.currentSong
                                            ?.pendingCanvasSources
                                            .orEmpty()
                                    },
                            )
                        },
                    lyrics = if (isNewSong) emptyList() else _uiState.value.lyrics,
                    lyricsAgents = if (isNewSong) emptyMap() else _uiState.value.lyricsAgents,
                    lyricsSongwriters = if (isNewSong) emptyList() else _uiState.value.lyricsSongwriters,
                    lyricsMeta = if (isNewSong) null else _uiState.value.lyricsMeta,
                    rawLyrics = if (isNewSong) null else _uiState.value.rawLyrics,
                    rawLyricsProvider = if (isNewSong) null else _uiState.value.rawLyricsProvider,
                    lyricsUserSelected = !isNewSong && _uiState.value.lyricsUserSelected,
                    isLyricsLoading = if (isNewSong) false else _uiState.value.isLyricsLoading,
                    lyricsProvider = if (isNewSong) null else _uiState.value.lyricsProvider,
                    lyricsQueryTitle = if (isNewSong) null else _uiState.value.lyricsQueryTitle,
                    lyricsQueryArtist = if (isNewSong) null else _uiState.value.lyricsQueryArtist,
                    lyricsOffsetMs = if (isNewSong) 0 else _uiState.value.lyricsOffsetMs,
                    lyricsProviderSearch = if (isNewSong) null else _uiState.value.lyricsProviderSearch,
                    manualAiTranslationEnabled = if (isNewSong) false else _uiState.value.manualAiTranslationEnabled,
                    lyricsTranslationProgress = if (isNewSong) LyricsTranslationProgress() else _uiState.value.lyricsTranslationProgress,
                    mvVideoId = if (isNewSong) null else _uiState.value.mvVideoId,
                    mvPositionMap = if (isNewSong) null else _uiState.value.mvPositionMap,
                    mvLyricsRetimed = if (isNewSong) false else _uiState.value.mvLyricsRetimed,
                    counterpartTrackId = if (isNewSong) null else _uiState.value.counterpartTrackId,
                    isVideoAvailable =
                        if (isNewSong) {
                            song?.let {
                                it.isVideoSong || it.canSwitchToMusicVideo || it.isPodcast
                            } == true
                        } else {
                            _uiState.value.isVideoAvailable
                        },
                )
            if (isNewSong) {
                if (preloaded == null ||
                    (preloaded.spotifyAnimatedCoverUrl == null && preloaded.appleMusicAnimatedCoverUrl == null) ||
                    preloaded.canvasState == CanvasArtworkState.NOT_REQUESTED ||
                    preloaded.canvasState == CanvasArtworkState.LOADING ||
                    preloaded.pendingCanvasSources.any { it != CanvasContentSource.YOUTUBE_MUSIC_VIDEO }
                ) {
                    song?.let { fetchAnimatedCover(it, keepAppleCover = sameAlbum && previousAppleMusicAnimatedCoverUrl != null) }
                }
                if (preloaded?.musicVideoCanvas == null) song?.let { fetchMusicVideoCanvas(it) }
                song?.takeIf { it.isVideoSong }?.let { fetchCounterpart(it) }
                song?.takeIf { it.album?.id == null }?.let(::fetchAlbum)
            } else if (
                Settings.shared.isAnimatedArtworkOrCanvasEnabled &&
                previousSpotifyAnimatedCoverUrl == null &&
                previousAppleMusicAnimatedCoverUrl == null &&
                song != null
            ) {
                fetchAnimatedCover(song)
            }
        }.launchIn(viewModelScope)

        val likedIds =
            SongLikeState.likedSongIdsForDisplay
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)
        likedIds
            .onEach { ids ->
                val currentSong = _uiState.value.currentSong
                val liked = currentSong?.id in ids
                _uiState.value =
                    _uiState.value.copy(
                        isLiked = liked,
                        currentSong = currentSong?.takeIf { it.isLiked == liked } ?: currentSong?.copy(isLiked = liked),
                    )
            }.launchIn(viewModelScope)

        val artworkVisible = platform.artworkVisible.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        viewModelScope.launch {
            combine(
                playerConnection.currentSong.map { it?.id to it?.thumbnail }.distinctUntilChanged(),
                snapshotFlow { Settings.shared.playerBackgroundStyle },
            ) { (id, thumbnail), style ->
                id to
                    thumbnail.takeIf {
                        style == PlayerBackgroundStyle.GRADIENT || style == PlayerBackgroundStyle.ANIMATED_GRADIENT
                    }
            }.distinctUntilChanged().collectLatestWhileVisible(artworkVisible) { (id, thumbnail) ->
                extractColors(id, thumbnail)
            }
        }
        viewModelScope.launch {
            combine(
                playerConnection.queueState,
                snapshotFlow { Settings.shared.maxImageCacheSize != 0 },
            ) { queue, cacheEnabled ->
                if (cacheEnabled && queue.currentIndex >= 0) {
                    queue.entries
                        .asSequence()
                        .drop(queue.currentIndex + 1)
                        .take(QUEUE_PRELOAD_COUNT)
                        .mapNotNull { it.song.thumbnail }
                        .toList()
                } else {
                    emptyList()
                }
            }.distinctUntilChanged().collectLatestWhileVisible(artworkVisible) { urls ->
                preloadUpcomingThumbnails(urls)
            }
        }

        playerConnection.isPlaying
            .onEach { isPlaying ->
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }.launchIn(viewModelScope)

        playerConnection.duration
            .onEach { duration ->
                _uiState.value = _uiState.value.copy(duration = duration)
            }.launchIn(viewModelScope)

        playerConnection.currentPlaybackResource
            .onEach { resource ->
                val isLive = resource?.isLive == true
                if (_uiState.value.isResourceLive != isLive) {
                    _uiState.value = _uiState.value.copy(isResourceLive = isLive)
                }
            }.launchIn(viewModelScope)

        val queueItems =
            combine(
                playerConnection.queueState.map { it.entries }.distinctUntilChanged(),
                SongMetadataState.revision,
            ) { entries, _ -> entries }
                .mapLatest { entries ->
                    withContext(Dispatchers.Default) {
                        entries to
                            Triple(
                                entries.map { it.key },
                                entries.map { it.song.toQueueItemUiModel(isSuggested = false) },
                                entries.map { it.song.queueSourceTitle.isRecommendedQueueSource() },
                            )
                    }
                }.flowOn(Dispatchers.Default)
        var previousQueueItems: List<QueueItemUiModel>? = null
        combine(
            queueItems,
            playerConnection.queueState,
            likedIds,
        ) { (entries, presentation), state, ids ->
            if (entries != state.entries) return@combine
            val (keys, items, recommended) = presentation
            val index = state.currentIndex
            val previous = if (items === previousQueueItems) _uiState.value.queueItems else items
            val flagged = withContext(Dispatchers.Default) { updateQueueFlags(previous, recommended, index, ids) }
            previousQueueItems = items
            _uiState.value = _uiState.value.copy(queueEntryKeys = keys, queueItems = flagged, currentQueueIndex = index)
        }.launchIn(viewModelScope)

        var preloadOptions: List<Any>? = null
        combine(
            playerConnection.queueState,
            playerConnection.currentSong,
            playerConnection.repeatMode,
            canvasVisible,
            combine(
                snapshotFlow {
                    listOf(
                        Settings.shared.isCanvasEnabled,
                        Settings.shared.enableAnimatedCovers,
                        Settings.shared.canvasEnabledSources,
                        Settings.shared.musicVideoPlaybackEnabled,
                        Settings.shared.youtubeCanvasMode,
                        Settings.shared.youtubeCanvasQuality,
                        Settings.shared.animatedCoverResolution,
                        Settings.shared.playerThumbnailStyle == PlayerThumbnailStyle.FULLSCREEN,
                        Settings.shared.disableAnimatedArtworkOnMobileData,
                    )
                },
                PlaybackNetworkStateStore.state,
            ) { options, network -> options + Settings.shared.isAnimatedArtworkAllowedOnNetwork(network) },
        ) { queue, current, repeat, visible, options ->
            if (preloadOptions != options) {
                canvasPreloader.clear()
                preloadOptions = options
            }
            if (queue.entries
                    .getOrNull(queue.currentIndex)
                    ?.song
                    ?.id != current?.id
            ) {
                return@combine
            }
            val next =
                if (platform.supportsCanvasPreloading && Settings.shared.isAnimatedArtworkOrCanvasEnabled &&
                    Settings.shared.isAnimatedArtworkAllowedOnNetwork() && current != null
                ) {
                    queue.entries.getOrNull(queue.currentIndex + 1)?.song
                        ?: queue.entries
                            .firstOrNull()
                            ?.song
                            ?.takeIf { repeat == 2 }
                } else {
                    null
                }
            val previous =
                if (platform.supportsCanvasPreloading && Settings.shared.isAnimatedArtworkOrCanvasEnabled &&
                    Settings.shared.isAnimatedArtworkAllowedOnNetwork() && current != null
                ) {
                    queue.entries.getOrNull(queue.currentIndex - 1)?.song
                        ?: queue.entries
                            .lastOrNull()
                            ?.song
                            ?.takeIf { repeat == 2 }
                } else {
                    null
                }
            canvasPreloader.update(current?.id, next, previous, enabled = visible && repeat != 1)
            _uiState.value = _uiState.value.copy(nextCanvasSongId = next?.id, previousCanvasSongId = previous?.id)
            _uiState.value.currentSong
                ?.takeIf {
                    Settings.shared.isAnimatedArtworkAllowedOnNetwork() &&
                        (it.musicVideoCanvas != null || it.spotifyAnimatedCoverUrl != null || it.appleMusicAnimatedCoverUrl != null)
                }?.let { canvasPreloader.rememberCurrent(it.id, it) }
        }.launchIn(viewModelScope)
        _uiState
            .map { it.currentSong }
            .distinctUntilChanged()
            .onEach { song ->
                if (song != null && platform.supportsCanvasPreloading && Settings.shared.isAnimatedArtworkAllowedOnNetwork() &&
                    (song.musicVideoCanvas != null || song.spotifyAnimatedCoverUrl != null || song.appleMusicAnimatedCoverUrl != null)
                ) {
                    canvasPreloader.rememberCurrent(song.id, song)
                }
            }.launchIn(viewModelScope)
        canvasPreloader.ready
            .onEach { prepared ->
                _uiState.value = _uiState.value.copy(preloadedCanvases = prepared)
            }.launchIn(viewModelScope)

        playerConnection.isQueueLoading
            .onEach { isLoading ->
                _uiState.value = _uiState.value.copy(isQueueLoading = isLoading)
            }.launchIn(viewModelScope)

        playerConnection.queueTitle
            .onEach { queueTitle ->
                _uiState.value = _uiState.value.copy(queueTitle = queueTitle)
            }.launchIn(viewModelScope)

        playerConnection.queueChips
            .onEach { queueChips ->
                _uiState.value = _uiState.value.copy(queueChips = queueChips)
            }.launchIn(viewModelScope)

        playerConnection.repeatMode
            .onEach { repeatMode ->
                _uiState.value = _uiState.value.copy(repeatMode = repeatMode)
            }.launchIn(viewModelScope)

        playerConnection.shuffleModeEnabled
            .onEach { isShuffleOn ->
                _uiState.value = _uiState.value.copy(isShuffleOn = isShuffleOn)
            }.launchIn(viewModelScope)

        playerConnection.isVideoModeActive
            .onEach { isActive ->
                _uiState.value = _uiState.value.copy(isVideoModeActive = isActive)
                val currentSong = _uiState.value.currentSong
                if (currentSong != null) {
                    syncLyricsOffsetForVideoMode(currentSong.id, isActive)
                    retimeLyricsIfVideoModeActive(currentSong.id)
                }
            }.launchIn(viewModelScope)

        playerConnection.isVideoModeResolving
            .onEach { isResolving ->
                _uiState.value = _uiState.value.copy(isVideoModeResolving = isResolving)
            }.launchIn(viewModelScope)

        combine(
            snapshotFlow {
                listOf(
                    Settings.shared.isCanvasEnabled,
                    Settings.shared.enableAnimatedCovers,
                    Settings.shared.animatedCoverResolution,
                    Settings.shared.playerThumbnailStyle == PlayerThumbnailStyle.FULLSCREEN,
                    Settings.shared.canvasEnabledSources,
                    Settings.shared.musicVideoPlaybackEnabled,
                    Settings.shared.disableAnimatedArtworkOnMobileData,
                )
            },
            PlaybackNetworkStateStore.state,
        ) { options, network -> options to Settings.shared.isAnimatedArtworkAllowedOnNetwork(network) }
            .distinctUntilChanged()
            .onEach { (_, allowed) ->
                val current = _uiState.value.currentSong ?: return@onEach
                animatedCoverFetch.invalidate()
                musicVideoCanvasFetch.invalidate()
                _uiState.value =
                    _uiState.value.copy(
                        currentSong =
                            current.copy(
                                spotifyAnimatedCoverUrl = null,
                                appleMusicAnimatedCoverUrl = null,
                                appleMusicTallAnimatedCoverUrl = null,
                                musicVideoCanvas = null,
                                canvasState = CanvasArtworkState.NOT_REQUESTED,
                                pendingCanvasSources = emptySet(),
                            ),
                    )
                if (Settings.shared.isAnimatedArtworkOrCanvasEnabled && allowed) {
                    playerConnection.currentSong.value?.let(::fetchAnimatedCover)
                    playerConnection.currentSong.value?.let(::fetchMusicVideoCanvas)
                }
            }.launchIn(viewModelScope)

        snapshotFlow { Settings.shared.spotifySession.isNotBlank() }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                animatedCoverFetch.invalidate()
                _uiState.value =
                    _uiState.value.copy(
                        currentSong =
                            _uiState.value.currentSong?.copy(
                                spotifyAnimatedCoverUrl = null,
                                canvasState = CanvasArtworkState.NOT_REQUESTED,
                            ),
                    )
                playerConnection.currentSong.value?.let(::fetchAnimatedCover)
            }.launchIn(viewModelScope)

        snapshotFlow { Settings.shared.youtubeCanvasQuality to Settings.shared.youtubeCanvasMode }
            .distinctUntilChanged()
            .onEach {
                val current = _uiState.value.currentSong ?: return@onEach
                musicVideoCanvasFetch.invalidate()
                _uiState.value = _uiState.value.copy(currentSong = current.copy(musicVideoCanvas = null))
                playerConnection.currentSong.value?.let(::fetchMusicVideoCanvas)
            }.launchIn(viewModelScope)
    }

    private fun fetchAlbum(song: SongItem) {
        albumFetchJob =
            viewModelScope.launch {
                val album =
                    GlobalYouTubeRepository.instance.resolveSongAlbum(song)?.takeIf { !it.id.isNullOrBlank() }
                        ?: return@launch
                if (_uiState.value.currentSong?.id == song.id) {
                    _uiState.value =
                        _uiState.value.copy(
                            currentSong =
                                _uiState.value.currentSong?.copy(
                                    albumId = album.id,
                                    album = album.name,
                                ),
                        )
                    val enrichedSong = song.copy(album = album)
                    invalidateAnimatedCoverRequest(song.id)
                    fetchAnimatedCover(enrichedSong)
                }
            }
    }

    private fun fetchCounterpart(song: SongItem) {
        counterpartFetchJob?.cancel()
        counterpartFetchJob =
            viewModelScope.launch {
                if (song.isVideoSong) {
                    _uiState.value = _uiState.value.copy(isVideoAvailable = true)
                    val result = musicVideoResolver.resolveMusicTrack(song)
                    if (result is MusicTrackResult.Found && _uiState.value.currentSong?.id == song.id) {
                        val track = result.song
                        _uiState.value =
                            _uiState.value.copy(
                                counterpartTrackId = track.id,
                                mvVideoId = song.id,
                                mvPositionMap = result.positionMap?.reversed(),
                                currentSong =
                                    _uiState.value.currentSong?.copy(
                                        album = track.album?.name ?: _uiState.value.currentSong?.album,
                                        albumId = track.album?.id ?: _uiState.value.currentSong?.albumId,
                                    ),
                            )
                        val artworkSong = track.copy(id = song.id)
                        invalidateAnimatedCoverRequest(song.id)
                        fetchAnimatedCover(artworkSong)
                        if (!_uiState.value.isVideoModeActive) {
                            syncLyricsOffsetForVideoMode(song.id, false)
                        }
                    }
                    retimeLyricsIfVideoModeActive(song.id)
                }
            }
    }

    fun setCanvasVisible(visible: Boolean) {
        if (canvasVisible.value == visible) return
        canvasVisible.value = visible
        animatedCoverFetch.setVisible(visible)
        musicVideoCanvasFetch.setVisible(visible)
        if (!visible) canvasPreloader.pause()
        if (visible) {
            playerConnection.currentSong.value?.let { song ->
                fetchAnimatedCover(song)
                if (_uiState.value.currentSong?.musicVideoCanvas == null) fetchMusicVideoCanvas(song)
            }
        }
    }

    private fun invalidateAnimatedCoverRequest(songId: String) {
        if (_uiState.value.currentSong?.id != songId) return
        animatedCoverFetch.invalidate()
    }

    fun retryMusicVideoCanvas(
        songId: String,
        url: String,
    ) {
        if (!canvasVisible.value) return
        val current = _uiState.value.currentSong ?: return
        if (current.id != songId || current.musicVideoCanvas?.url != url) return
        val song = playerConnection.currentSong.value?.takeIf { it.id == songId } ?: return
        _uiState.value = _uiState.value.copy(currentSong = current.copy(musicVideoCanvas = null))
        canvasPreloader.forget(songId)
        fetchMusicVideoCanvas(song, failedResource = current.musicVideoCanvas.resource)
    }

    private fun fetchMusicVideoCanvas(
        song: SongItem,
        failedResource: PlaybackResource? = null,
    ) {
        if (!canvasVisible.value || !Settings.shared.isAnimatedArtworkAllowedOnNetwork()) return
        if (failedResource != null) musicVideoCanvasFetch.invalidate()
        musicVideoCanvasFetch
            .launch(song.id) {
                if (failedResource == null) canvasPlaybackRecovery.reset()
                _uiState.value =
                    _uiState.value.copy(
                        currentSong =
                            _uiState.value.currentSong?.let {
                                it.copy(pendingCanvasSources = it.pendingCanvasSources + CanvasContentSource.YOUTUBE_MUSIC_VIDEO)
                            },
                    )
                val canvas =
                    try {
                        if (failedResource == null) {
                            retryCanvasLookup { loadMusicVideoCanvas(song) }
                        } else {
                            canvasPlaybackRecovery.recover(
                                expired = failedResource.expiresAt?.let { it <= Clock.System.now() } == true,
                                reportFailure = {
                                    GlobalYouTubeRepository.streamResolver?.reportPlaybackFailure(
                                        videoId = failedResource.videoId,
                                        clientName = failedResource.clientName,
                                        responseCode = null,
                                        profileId = failedResource.profileId,
                                        hints = platform.musicVideoCanvasHints(song),
                                    )
                                },
                                refresh = {
                                    delay(5_000L)
                                    retryCanvasLookup { loadMusicVideoCanvas(song, refresh = true) }
                                },
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Logger.w("Canvas", "Music video Canvas lookup failed", error)
                        null
                    }
                if (_uiState.value.currentSong?.id == song.id) {
                    _uiState.value =
                        _uiState.value.copy(
                            currentSong =
                                _uiState.value.currentSong?.let {
                                    it.copy(
                                        musicVideoCanvas = canvas,
                                        pendingCanvasSources =
                                            it.pendingCanvasSources - CanvasContentSource.YOUTUBE_MUSIC_VIDEO,
                                    )
                                },
                        )
                }
            }
    }

    private suspend fun loadMusicVideoCanvas(
        song: SongItem,
        preload: Boolean = false,
        refresh: Boolean = false,
    ): MusicVideoCanvas? {
        if (!Settings.shared.isCanvasEnabled ||
            !Settings.shared.isAnimatedArtworkAllowedOnNetwork() ||
            !Settings.shared.musicVideoPlaybackEnabled ||
            CanvasContentSource.YOUTUBE_MUSIC_VIDEO !in CanvasContentSource.parseEnabled(Settings.shared.canvasEnabledSources)
        ) {
            return null
        }
        val songId = song.id
        val mode = Settings.shared.youtubeCanvasMode
        val quality = Settings.shared.youtubeCanvasQuality
        val cached = if (refresh) null else withContext(Dispatchers.IO) { platform.cachedMusicVideoCanvas(songId, mode, quality) }
        if (cached != null) return cached
        val resolved =
            withContext(Dispatchers.IO) {
                if (song.isVideoSong) {
                    MusicVideoResult.Found(
                        song.id,
                    )
                } else {
                    musicVideoResolver.resolveMusicVideo(song) as? MusicVideoResult.Found
                }
            } ?: return null
        val target =
            if (resolved.videoId == song.id) {
                song
            } else {
                withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.getSongMetadata(resolved.videoId) }
            } ?: return null
        PlaybackEventLog.info(
            PlaybackEventKind.RESOLVE,
            "Canvas resource requested.",
            videoId = target.id,
            details =
                mapOf(
                    "queueSongId" to songId,
                    "counterpartPositionMap" to (resolved.positionMap != null).toString(),
                    "canvasMode" to mode.name,
                    "refresh" to refresh.toString(),
                    "preload" to preload.toString(),
                ),
        )
        val resource =
            withContext(Dispatchers.IO) {
                val resolver = GlobalYouTubeRepository.streamResolver ?: return@withContext null
                val hints =
                    platform.musicVideoCanvasHints(target).let { hints ->
                        hints.copy(maxVideoHeight = quality.targetHeight ?: hints.maxVideoHeight)
                    }
                if (refresh) {
                    resolver.refresh(target.id, hints, AudioQuality.AUTO, sourceSong = target)
                } else {
                    resolver.resolve(target.id, hints, AudioQuality.AUTO, sourceSong = target)
                }
            }?.takeIf { it.hasVideo } ?: return null
        val canvas =
            withContext(Dispatchers.IO) {
                platform.prepareMusicVideoCanvas(
                    songId,
                    MusicVideoCanvas(
                        resource,
                        youtubeCanvasPlaybackClips(mode, resource),
                    ),
                    mode,
                    quality,
                    preload,
                )
            }
        return canvas
    }

    private fun fetchAnimatedCover(
        song: SongItem,
        keepAppleCover: Boolean = false,
    ) {
        if (!canvasVisible.value || !Settings.shared.isAnimatedArtworkAllowedOnNetwork()) return
        val enabledSources = activeCanvasSources().let { if (keepAppleCover) it - CanvasContentSource.APPLE_MUSIC else it }
        if (CanvasContentSource.SPOTIFY_CANVAS !in enabledSources && CanvasContentSource.APPLE_MUSIC !in enabledSources) return
        val artist = song.artists.joinToString(", ") { it.name }
        if (artist.isBlank()) return
        val requestKey =
            listOf(song.id, song.title, artist, song.album?.name, Settings.shared.animatedCoverResolution).joinToString("|")
        animatedCoverFetch
            .launch(requestKey) {
                val songId = song.id
                _uiState.value =
                    _uiState.value.copy(
                        currentSong =
                            _uiState.value.currentSong?.let {
                                it.copy(
                                    pendingCanvasSources =
                                        it.pendingCanvasSources + (enabledSources - CanvasContentSource.YOUTUBE_MUSIC_VIDEO),
                                    canvasState =
                                        if (it.spotifyAnimatedCoverUrl !=
                                            null
                                        ) {
                                            CanvasArtworkState.AVAILABLE
                                        } else {
                                            CanvasArtworkState.LOADING
                                        },
                                )
                            },
                    )

                loadAnimatedCovers(song, enabledSources) { source, url, tallUrl ->
                    val current = _uiState.value.currentSong?.takeIf { it.id == songId } ?: return@loadAnimatedCovers
                    if (source == CanvasContentSource.APPLE_MUSIC && keepAppleCover) return@loadAnimatedCovers
                    _uiState.value =
                        _uiState.value.copy(
                            currentSong =
                                current.copy(
                                    spotifyAnimatedCoverUrl =
                                        if (source ==
                                            CanvasContentSource.SPOTIFY_CANVAS
                                        ) {
                                            url
                                        } else {
                                            current.spotifyAnimatedCoverUrl
                                        },
                                    appleMusicAnimatedCoverUrl =
                                        if (source ==
                                            CanvasContentSource.APPLE_MUSIC
                                        ) {
                                            url
                                        } else {
                                            current.appleMusicAnimatedCoverUrl
                                        },
                                    appleMusicTallAnimatedCoverUrl =
                                        if (source == CanvasContentSource.APPLE_MUSIC) tallUrl else current.appleMusicTallAnimatedCoverUrl,
                                    canvasState =
                                        if (source == CanvasContentSource.SPOTIFY_CANVAS) {
                                            if (url != null) CanvasArtworkState.AVAILABLE else CanvasArtworkState.UNAVAILABLE
                                        } else {
                                            current.canvasState
                                        },
                                    pendingCanvasSources = current.pendingCanvasSources - source,
                                ),
                        )
                }
            }
    }

    private fun activeCanvasSources(): Set<CanvasContentSource> =
        CanvasContentSource.parseEnabled(Settings.shared.canvasEnabledSources).filterTo(mutableSetOf()) {
            it.isEnabled(Settings.shared.isCanvasEnabled, Settings.shared.enableAnimatedCovers)
        }

    private suspend fun loadAnimatedCovers(
        song: SongItem,
        enabledSources: Set<CanvasContentSource> = activeCanvasSources(),
        onResult: (CanvasContentSource, String?, String?) -> Unit = { _, _, _ -> },
    ): AnimatedCovers {
        if (!Settings.shared.isAnimatedArtworkAllowedOnNetwork()) return AnimatedCovers()
        val songId = song.id
        val artist = song.artists.joinToString(", ") { it.name }
        if (artist.isBlank()) return AnimatedCovers()
        val resolution = Settings.shared.animatedCoverResolution
        val appleId = appleCoverId(song)
        val wantsTall = Settings.shared.playerThumbnailStyle == PlayerThumbnailStyle.FULLSCREEN
        var appleTall: String? = null
        val (spotifyUrl, appleUrl) =
            loadCanvasSources(
                spotify = {
                    if (CanvasContentSource.SPOTIFY_CANVAS !in enabledSources) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            platform.cachedAnimatedCover(songId, CanvasContentSource.SPOTIFY_CANVAS, resolution)
                                ?: platform.spotifyCanvas(
                                    title = song.title,
                                    artist = artist,
                                    album = song.album?.name,
                                    durationMs = song.duration.toDurationMillis(),
                                    primaryArtist = song.artists.firstOrNull()?.name,
                                )
                        }
                    }
                },
                apple = {
                    if (CanvasContentSource.APPLE_MUSIC !in enabledSources) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            var square = platform.cachedAnimatedCover(appleId, CanvasContentSource.APPLE_MUSIC, resolution)
                            var tall =
                                if (wantsTall) {
                                    platform.cachedAnimatedCover(appleId, CanvasContentSource.APPLE_MUSIC, resolution, tall = true)
                                } else {
                                    null
                                }
                            if (square == null || (wantsTall && tall == null)) {
                                val fetched =
                                    animatedArtworkService.fetchAnimatedCover(
                                        song.title,
                                        artist,
                                        song.album?.name,
                                        song.artworkExplicitness,
                                    )
                                square = square ?: fetched?.url
                                tall = tall ?: fetched?.tallUrl.takeIf { wantsTall }
                            }
                            appleTall = tall
                            square
                        }
                    }
                },
                onResult = { source, url -> onResult(source, url, appleTall.takeIf { source == CanvasContentSource.APPLE_MUSIC }) },
            )
        return AnimatedCovers(spotifyUrl, appleUrl, appleTall)
    }

    private fun appleCoverId(song: SongItem): String = song.album?.id ?: song.id

    private class AnimatedCovers(
        val spotify: String? = null,
        val apple: String? = null,
        val appleTall: String? = null,
    )

    private suspend fun preloadCanvas(song: SongItem): SongUiModel {
        animatedCoverFetch.join()
        musicVideoCanvasFetch.join()
        val musicVideo =
            try {
                loadMusicVideoCanvas(song, preload = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.w("Canvas", "Canvas preload failed", error)
                null
            }
        val covers = loadAnimatedCovers(song)
        val spotify =
            covers.spotify?.let {
                withContext(Dispatchers.IO) { platform.preloadAnimatedCover(song.id, CanvasContentSource.SPOTIFY_CANVAS, it) }
            }
        val apple =
            covers.apple?.let {
                withContext(Dispatchers.IO) { platform.preloadAnimatedCover(appleCoverId(song), CanvasContentSource.APPLE_MUSIC, it) }
            }
        val appleTall =
            covers.appleTall?.let {
                withContext(Dispatchers.IO) {
                    platform.preloadAnimatedCover(appleCoverId(song), CanvasContentSource.APPLE_MUSIC, it, tall = true)
                }
            }
        return SongUiModel(
            id = song.id,
            title = song.title,
            artists = song.artists,
            thumbnail = song.thumbnail,
            duration = song.duration.toDurationMillis(),
            spotifyAnimatedCoverUrl = spotify,
            appleMusicAnimatedCoverUrl = apple,
            appleMusicTallAnimatedCoverUrl = appleTall,
            musicVideoCanvas = musicVideo,
            canvasState = if (covers.spotify != null) CanvasArtworkState.AVAILABLE else CanvasArtworkState.UNAVAILABLE,
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun preloadUpcomingThumbnails(urls: List<String>) =
        coroutineScope {
            urls
                .map { url ->
                    async {
                        val request =
                            ImageRequest
                                .Builder(platformContext)
                                .data(url)
                                .size(256)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .decoderFactory(BlackholeDecoder.Factory())
                                .build()
                        imageLoader.execute(request)
                    }
                }.awaitAll()
        }

    private suspend fun extractColors(
        songId: String?,
        thumbnailUrl: String?,
    ) {
        if (songId == null || thumbnailUrl == null) {
            _uiState.value =
                _uiState.value.copy(
                    dominantColor = DefaultThemeColor,
                    gradientColors = createDefaultGradient(DefaultThemeColor),
                )
            return
        }

        val cached = gradientColorsCache.remove(thumbnailUrl)?.also { gradientColorsCache[thumbnailUrl] = it }
        val colors =
            cached ?: try {
                platform.extractArtworkColors(songId, thumbnailUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        currentCoroutineContext().ensureActive()
        if (colors != null) {
            gradientColorsCache[thumbnailUrl] = colors
            while (gradientColorsCache.size > 32) gradientColorsCache.remove(gradientColorsCache.keys.first())
        }
        if (playerConnection.currentSong.value?.let { it.id == songId && it.thumbnail == thumbnailUrl } != true) return
        _uiState.value =
            _uiState.value.copy(
                dominantColor = colors?.dominant ?: DefaultThemeColor,
                gradientColors = colors?.gradient ?: createDefaultGradient(DefaultThemeColor),
            )
    }

    private fun createDefaultGradient(color: Color): List<Color> =
        listOf(
            color,
            color.copy(
                red = (color.red * 0.6f).coerceAtLeast(0f),
                green = (color.green * 0.6f).coerceAtLeast(0f),
                blue = (color.blue * 0.6f).coerceAtLeast(0f),
            ),
            Color.Black,
        )

    fun togglePlayPause() {
        playerConnection.togglePlayPause()
    }

    fun next() {
        playerConnection.skipToNext()
    }

    fun previous() {
        playerConnection.skipToPrevious()
    }

    fun playQueueEntry(entryKey: Long) {
        playerConnection.skipToQueueEntry(entryKey)
    }

    fun seekTo(
        progress: Float,
        durationMs: Long,
        songId: String?,
    ): Boolean {
        if (!progress.isFinite() || durationMs <= 0L || durationMs != playerConnection.duration.value ||
            songId != playerConnection.currentSong.value?.id
        ) {
            return false
        }
        val position = (progress.coerceIn(0f, 1f).toDouble() * durationMs).toLong()
        playerConnection.seekTo(
            position,
            PlaybackSeekSource("seek_bar", mapOf("fraction" to progress.toString(), "durationMs" to durationMs.toString())),
        )
        return true
    }

    fun seekToPosition(positionMs: Long) = seekToPosition(positionMs, PlaybackSeekSource.Other)

    fun seekToPosition(
        positionMs: Long,
        source: PlaybackSeekSource,
    ) {
        playerConnection.seekTo(positionMs, source)
    }

    fun seekBy(deltaMs: Long) {
        val duration = _uiState.value.duration
        playerConnection.seekTo(
            computeSeekTarget(playerConnection.currentPositionMs(), duration, deltaMs),
            PlaybackSeekSource("player_skip", mapOf("deltaMs" to deltaMs.toString())),
        )
    }

    fun retry() {
        _uiState.value = _uiState.value.copy(error = null)
        if (!_uiState.value.isPlaying) {
            playerConnection.togglePlayPause()
        }
    }

    fun toggleLike() {
        val currentSong = playerConnection.currentSong.value ?: return
        val newIsLiked = SongLikeState.toggleLike(currentSong)

        _uiState.value =
            _uiState.value.copy(
                isLiked = newIsLiked,
                currentSong = _uiState.value.currentSong?.copy(isLiked = newIsLiked),
                queueItems =
                    _uiState.value.queueItems.map { item ->
                        if (item.id == currentSong.id) item.copy(isLiked = newIsLiked) else item
                    },
            )
    }

    fun toggleLikeQueueEntry(entryKey: Long) {
        toggleLikeQueueSong(playerConnection.queueSong(entryKey) ?: return)
    }

    private fun toggleLikeQueueSong(song: SongItem) {
        val liked = SongLikeState.toggleLike(song)
        _uiState.value =
            _uiState.value.copy(
                isLiked = if (_uiState.value.currentSong?.id == song.id) liked else _uiState.value.isLiked,
                currentSong =
                    _uiState.value.currentSong?.let { current ->
                        if (current.id == song.id) current.copy(isLiked = liked) else current
                    },
                queueItems =
                    _uiState.value.queueItems.map { item ->
                        if (item.id == song.id) item.copy(isLiked = liked) else item
                    },
            )
    }

    fun likeQueueEntries(entryKeys: Set<Long>) {
        likeQueueSongs(playerConnection.queueSongs(entryKeys))
    }

    private fun likeQueueSongs(songs: List<SongItem>) {
        val uniqueSongs = songs.distinctBy { it.id }
        SongLikeState.addLikes(uniqueSongs)
        val likedIds = uniqueSongs.mapTo(mutableSetOf()) { it.id }
        _uiState.value =
            _uiState.value.copy(
                isLiked =
                    _uiState.value.currentSong
                        ?.id
                        ?.let(SongLikeState::isLiked) == true,
                currentSong =
                    _uiState.value.currentSong?.let { current ->
                        if (current.id in likedIds) current.copy(isLiked = true) else current
                    },
                queueItems =
                    _uiState.value.queueItems.map { item ->
                        if (item.id in likedIds) item.copy(isLiked = true) else item
                    },
            )
    }

    fun startRadio(song: SongItem) {
        playerConnection.startRadio(song)
    }

    fun selectQueueChip(chipId: String) {
        playerConnection.selectQueueChip(chipId)
    }

    fun addToQueue(song: SongItem) {
        playerConnection.addToQueue(song)
    }

    fun addQueueEntry(entryKey: Long) {
        playerConnection.addQueueEntry(entryKey)
    }

    fun setLyricsExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(isLyricsExpanded = expanded)
    }

    fun setLyricsVisible(visible: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                isLyricsVisible = visible,
                isLyricsExpanded = if (visible) _uiState.value.isLyricsExpanded else false,
                isQueueVisible = if (visible) false else _uiState.value.isQueueVisible,
            )
    }

    fun setQueueVisible(visible: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                isQueueVisible = visible,
                isLyricsVisible = if (visible) false else _uiState.value.isLyricsVisible,
            )
    }

    fun setSubtitleActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(isSubtitleActive = active)
    }

    fun shareSong() {
        val song = _uiState.value.currentSong ?: return
        platform.shareText(ShareUrls.song(song.id))
    }

    fun shareSong(song: SongItem) {
        platform.shareText(ShareUrls.song(song.id))
    }

    fun copySongLink(): String? {
        val song = _uiState.value.currentSong ?: return null
        return copySongLink(song.id)
    }

    fun copySongLink(song: SongItem): String = copySongLink(song.id)

    private fun copySongLink(songId: String): String {
        val url = ShareUrls.song(songId)
        platform.copyText(url)
        return url
    }

    fun toggleShuffle() {
        playerConnection.toggleShuffle()
    }

    fun changeMusicVideoResolution(quality: MusicVideoQuality) {
        playerConnection.changeMusicVideoResolution(quality)
    }

    fun toggleVideoMode() {
        playerConnection.toggleVideoMode()
    }

    fun setVideoMode(wantVideo: Boolean) {
        playerConnection.setVideoMode(wantVideo)
    }

    fun toggleFullscreen() {
        _uiState.value = _uiState.value.copy(isFullscreen = !_uiState.value.isFullscreen)
    }

    fun setFullscreen(isFullscreen: Boolean) {
        if (_uiState.value.isFullscreen != isFullscreen) {
            _uiState.value = _uiState.value.copy(isFullscreen = isFullscreen)
        }
    }

    fun toggleRepeat() {
        playerConnection.cycleRepeatMode()
    }

    fun removeQueueEntry(entryKey: Long) = playerConnection.removeQueueEntry(entryKey)

    fun removeQueueEntries(entryKeys: Set<Long>) = playerConnection.removeQueueEntries(entryKeys)

    fun pendingQueueRemovals(): List<QueueRemoval> = playerConnection.pendingQueueRemovals()

    fun discardQueueUndo(removals: List<QueueRemoval>) {
        playerConnection.discardQueueUndo(removals)
    }

    fun restoreQueueItems(removals: List<QueueRemoval>) {
        playerConnection.restoreQueueItems(removals)
    }

    fun moveQueueEntry(
        fromEntryKey: Long,
        toEntryKey: Long,
    ): Boolean = playerConnection.moveQueueEntry(fromEntryKey, toEntryKey)

    fun reorderQueueEntries(entryKeys: List<Long>): Boolean = playerConnection.reorderQueueEntries(entryKeys)

    fun playNextQueueEntry(entryKey: Long) {
        playerConnection.playNextQueueEntry(entryKey)
    }

    fun blockCurrentArtist() {
        val song = playerConnection.currentSong.value ?: return
        val artist = song.artists.firstOrNull { it.id != null } ?: return
        BlockedContent.blockArtist(artist)
        artist.id?.let(playerConnection::blockArtist)
    }

    fun toggleDislikeCurrentSong() {
        val song = playerConnection.currentSong.value ?: return
        playerConnection.toggleDislike(song)
        val isLiked = SongLikeState.isLiked(song.id)
        _uiState.value =
            _uiState.value.copy(
                isLiked = if (_uiState.value.currentSong?.id == song.id) isLiked else _uiState.value.isLiked,
                currentSong =
                    _uiState.value.currentSong?.let { current ->
                        if (current.id == song.id) current.copy(isLiked = isLiked) else current
                    },
                queueItems =
                    _uiState.value.queueItems.map { item ->
                        if (item.id == song.id) item.copy(isLiked = isLiked) else item
                    },
            )
    }

    fun getQueueSong(entryKey: Long): SongItem? = playerConnection.queueSong(entryKey)

    fun getQueueSongs(entryKeys: Set<Long>): List<SongItem> = playerConnection.queueSongs(entryKeys)

    suspend fun prefetchLyrics(song: QueueItemUiModel) {
        try {
            lyricsFetchJob?.join()
            lyricsRepository.getLyrics(
                videoId = song.id,
                title = song.title,
                artist = song.artists,
                duration = song.duration,
                preferVideoProviders = isLiveLyricsVideo(song.musicVideoType, song.title),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    fun loadLyrics(
        song: SongUiModel,
        skipCache: Boolean = false,
        forceAiTranslation: Boolean = false,
        cacheOnly: Boolean = false,
    ) {
        loadLyrics(
            songId = song.id,
            title = song.title,
            artist = song.artists.joinToString { it.name },
            duration = song.duration,
            album = song.album,
            skipCache = skipCache,
            forceAiTranslation = forceAiTranslation,
            cacheOnly = cacheOnly,
            preferVideoProviders = isLiveLyricsVideo(song.musicVideoType, song.title),
        )
    }

    fun loadLyrics(
        songId: String,
        title: String,
        artist: String = "",
        duration: Long? = null,
        album: String? = null,
        skipCache: Boolean = false,
        forceAiTranslation: Boolean = false,
        preferVideoProviders: Boolean = false,
        isCustomQuery: Boolean = false,
        cacheOnly: Boolean = false,
    ) {
        val requestKey =
            "$songId\u0000$title\u0000$artist\u0000$isCustomQuery\u0000$forceAiTranslation\u0000" +
                "${Settings.shared.lyricsProviderOrder}\u0000${Settings.shared.lyricsEnabledProviders}\u0000${Settings.shared.preferWordSyncedLyrics}"
        if (!skipCache && lyricsRequestKey == requestKey && (cacheOnly || !lyricsRequestCacheOnly)) return
        lyricsRequestKey = requestKey
        lyricsRequestCacheOnly = cacheOnly
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = retimeLyricsJob
        previousFetchJob?.cancel()
        previousRetimeJob?.cancel()
        _uiState.value =
            _uiState.value.copy(
                lyricsQueryTitle = title,
                lyricsQueryArtist = artist,
                lyricsTranslationProgress = LyricsTranslationProgress(),
            )
        lyricsFetchJob =
            viewModelScope.launch {
                previousFetchJob?.join()
                previousRetimeJob?.join()
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                _uiState.value = _uiState.value.copy(isLyricsLoading = skipCache || _uiState.value.lyrics.isEmpty())
                var loaded = false
                try {
                    val currentSong = _uiState.value.currentSong
                    val isVideo = !isCustomQuery && (_uiState.value.isVideoModeActive || currentSong?.isVideoSong == true)
                    val resolvedDuration =
                        if (isCustomQuery) {
                            null
                        } else {
                            duration?.takeIf { it > 0L }
                                ?: _uiState.value
                                    .takeIf { it.currentSong?.id == songId }
                                    ?.duration
                                    ?.takeIf { it > 0L }
                        }
                    val resolvedAlbum = album.takeUnless { isCustomQuery }
                    val publicationContext = currentCoroutineContext()
                    val activeOffsetVideoId = resolveActiveLyricsOffsetVideoId(songId, isVideo)
                    val lyricsOffsetMs = lyricsRepository.getLyricsOffset(activeOffsetVideoId)

                    var publishedResult: LyricsWithProvider? = null
                    var publishedDocument: MlxDocument? = null

                    suspend fun publishLyrics(result: LyricsWithProvider?): MlxDocument? {
                        if (result != null && result == publishedResult) return publishedDocument
                        if (!isCurrentLyricsRequest(songId, requestId)) return null
                        val rawLyrics = result?.lyrics
                        val (lyricsDoc, parsedLyrics) =
                            withContext(Dispatchers.Default) {
                                val doc = rawLyrics?.let(LyricsUtils::parseMlxDocument)
                                val lines = doc?.lines ?: rawLyrics?.let(LyricsUtils::parseLyrics).orEmpty()
                                doc to lines
                            }
                        if (skipCache && parsedLyrics.isEmpty() && _uiState.value.lyrics.isNotEmpty()) return null
                        var published = false

                        fun publishEntries(entries: List<LyricsEntry>) {
                            if (!isCurrentLyricsRequest(songId, requestId)) return
                            if (published) {
                                if (!_uiState.value.mvLyricsRetimed) _uiState.value = _uiState.value.copy(lyrics = entries)
                                return
                            }
                            published = true
                            publishedResult = result
                            publishedDocument = lyricsDoc
                            retimeLyricsJob?.cancel()
                            _uiState.value =
                                _uiState.value.copy(
                                    lyrics = entries,
                                    lyricsAgents = lyricsDoc?.agents ?: emptyMap(),
                                    lyricsSongwriters = lyricsDoc?.song?.songwriters.orEmpty(),
                                    lyricsMeta = lyricsDoc?.meta,
                                    rawLyrics = rawLyrics,
                                    rawLyricsProvider = result?.provider,
                                    lyricsUserSelected = result?.isUserSelected == true,
                                    lyricsProvider = lyricsProviderLabel(result?.provider, lyricsDoc),
                                    lyricsOffsetMs = if (loaded) _uiState.value.lyricsOffsetMs else lyricsOffsetMs,
                                    isLyricsLoading = skipCache || entries.isEmpty(),
                                    manualAiTranslationEnabled = forceAiTranslation,
                                    mvLyricsRetimed = false,
                                )
                            loaded = entries.isNotEmpty()
                        }
                        val enriched =
                            enrichLyrics(
                                songId,
                                parsedLyrics,
                                requestId,
                                forceAiTranslation,
                                allowRemoteTranslation = false,
                                onDictionaryPreparation = {
                                    if (!published && _uiState.value.lyrics.isEmpty()) publishEntries(parsedLyrics)
                                },
                            )
                        publishEntries(enriched)
                        return lyricsDoc
                    }
                    val onLyricsAvailable: suspend (LyricsWithProvider) -> Unit = { candidate ->
                        withContext(publicationContext) { publishLyrics(candidate) }
                    }
                    val result =
                        withContext(Dispatchers.Default) {
                            if (cacheOnly) {
                                lyricsRepository.getCachedLyrics(songId, isVideoContext = isVideo)
                            } else {
                                lyricsRepository.getLyrics(
                                    videoId = songId,
                                    title = title,
                                    artist = artist,
                                    duration = resolvedDuration,
                                    album = resolvedAlbum,
                                    skipCache = skipCache,
                                    onLyricsAvailable = onLyricsAvailable,
                                    isVideoContext = isVideo,
                                    preferVideoProviders =
                                        !isCustomQuery && (preferVideoProviders || isLiveLyricsVideo(currentSong?.musicVideoType, title)),
                                    ignoreVideoIdentity = isCustomQuery,
                                )
                            }
                        }
                    val lyricsDoc = publishLyrics(result)
                    if (cacheOnly) return@launch
                    val parsedLyrics =
                        lyricsDoc?.lines
                            ?: withContext(Dispatchers.Default) { result?.lyrics?.let(LyricsUtils::parseLyrics) }.orEmpty()
                    val mlxSongwriters = lyricsDoc?.song?.songwriters.orEmpty()
                    if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                    if (mlxSongwriters.isEmpty()) {
                        launch {
                            val ytWriters = lyricsRepository.getSongwriters(songId)
                            if (isCurrentLyricsRequest(songId, requestId) && ytWriters.isNotEmpty()) {
                                _uiState.value = _uiState.value.copy(lyricsSongwriters = ytWriters)
                            }
                        }
                    }
                    if (parsedLyrics.isEmpty()) return@launch

                    val enrichedLyrics = enrichLyrics(songId, parsedLyrics, requestId, forceAiTranslation)
                    if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                    if (enrichedLyrics != parsedLyrics && !_uiState.value.mvLyricsRetimed) {
                        _uiState.value =
                            _uiState.value.copy(
                                lyrics = enrichedLyrics,
                            )
                    }

                    retimeLyricsIfVideoModeActive(songId, forceAiTranslation, requestId, skipCache)
                } finally {
                    if (isCurrentLyricsRequest(songId, requestId)) {
                        if (!loaded) lyricsRequestKey = null
                        _uiState.value = _uiState.value.copy(isLyricsLoading = false)
                    }
                }
            }
    }

    fun setManualLyricsTranslation(enabled: Boolean) {
        val state = _uiState.value
        val songId = state.currentSong?.id ?: return
        if (state.lyrics.isEmpty()) return
        lyricsRequestCacheOnly = false
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = retimeLyricsJob
        previousFetchJob?.cancel()
        previousRetimeJob?.cancel()
        _uiState.value = state.copy(manualAiTranslationEnabled = enabled, lyricsTranslationProgress = LyricsTranslationProgress())
        lyricsFetchJob =
            viewModelScope.launch {
                previousFetchJob?.join()
                previousRetimeJob?.join()
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                try {
                    val enriched = enrichLyrics(songId, state.lyrics, requestId, forceAiTranslation = enabled)
                    if (isCurrentLyricsRequest(songId, requestId)) {
                        _uiState.value = _uiState.value.copy(lyrics = enriched)
                    }
                } finally {
                    if (isCurrentLyricsRequest(songId, requestId)) _uiState.value = _uiState.value.copy(isLyricsLoading = false)
                }
            }
    }

    private fun isCurrentLyricsRequest(
        songId: String,
        requestId: Long,
    ): Boolean = requestId == lyricsRequestId && _uiState.value.currentSong?.id == songId

    private fun retimeLyricsIfVideoModeActive(
        songId: String,
        forceAiTranslation: Boolean = _uiState.value.manualAiTranslationEnabled,
        requestId: Long = lyricsRequestId,
        skipCache: Boolean = false,
    ) {
        if (lyricsRequestCacheOnly || !isCurrentLyricsRequest(songId, requestId)) return
        val currentSong = _uiState.value.currentSong ?: return
        val rawLyrics = _uiState.value.rawLyrics ?: return
        val isVideoContext = _uiState.value.isVideoModeActive || currentSong.isVideoSong
        val isUserSelected = _uiState.value.lyricsUserSelected
        retimeLyricsJob?.cancel()
        if (isUserSelected && currentSong.isVideoSong) return

        Logger.d(
            TAG,
            "[LyricsRetimer] retimeLyricsIfVideoModeActive: songId=$songId, isVideoContext=$isVideoContext, " +
                "isVideoModeActive=${_uiState.value.isVideoModeActive}, isVideoSong=${currentSong.isVideoSong}, " +
                "alreadyRetimed=${_uiState.value.mvLyricsRetimed}",
        )

        if (!isVideoContext) {
            if (_uiState.value.mvLyricsRetimed && !currentSong.isVideoSong) {
                Logger.d(
                    TAG,
                    "[LyricsRetimer] restoring original studio lyrics timing for $songId",
                )
                retimeLyricsJob =
                    viewModelScope.launch {
                        val lyricsDoc =
                            withContext(Dispatchers.Default) {
                                LyricsUtils.parseMlxDocument(rawLyrics)
                            } ?: return@launch
                        val parsedLyrics = lyricsDoc.lines
                        val enrichedLyrics =
                            enrichLyrics(songId, parsedLyrics, requestId, forceAiTranslation)
                        currentCoroutineContext().ensureActive()
                        if (isCurrentLyricsRequest(songId, requestId)) {
                            _uiState.value =
                                _uiState.value.copy(
                                    lyrics = enrichedLyrics,
                                    lyricsProvider = lyricsProviderLabel(_uiState.value.rawLyricsProvider, lyricsDoc),
                                    lyricsMeta = lyricsDoc.meta,
                                    lyricsAgents = lyricsDoc.agents,
                                    lyricsSongwriters =
                                        lyricsDoc.song.songwriters.takeIf { it.isNotEmpty() } ?: _uiState.value.lyricsSongwriters,
                                    mvLyricsRetimed = false,
                                )
                        }
                    }
            }
            return
        }

        retimeLyricsJob =
            viewModelScope.launch {
                val lyricsDoc =
                    withContext(Dispatchers.Default) {
                        LyricsUtils.parseMlxDocument(rawLyrics)
                    } ?: return@launch
                if (lyricsDoc.meta.ignoreRetiming) {
                    Logger.d(TAG, "[LyricsRetimer] skipping retiming because ignoreRetiming is true")
                    return@launch
                }
                var positionMap = _uiState.value.mvPositionMap
                if (positionMap == null && _uiState.value.mvVideoId == null && !currentSong.isVideoSong) {
                    val resolved = runCatching { musicVideoResolver.resolveMusicVideo(currentSong.toSongItem()) }.getOrNull()
                    if (resolved is MusicVideoResult.Found) {
                        positionMap = resolved.positionMap
                        if (isCurrentLyricsRequest(songId, requestId)) {
                            _uiState.value =
                                _uiState.value.copy(
                                    mvVideoId = resolved.videoId,
                                    mvPositionMap = resolved.positionMap,
                                )
                            if (_uiState.value.isVideoModeActive) {
                                syncLyricsOffsetForVideoMode(songId, true)
                            }
                        }
                    }
                }

                val videoId =
                    if (currentSong.isVideoSong) {
                        currentSong.id
                    } else {
                        _uiState.value.mvVideoId ?: currentSong.id
                    }
                val videoLyrics =
                    if (isUserSelected) {
                        null
                    } else {
                        lyricsRepository.getVideoSpecificLyrics(
                            videoId = videoId,
                            title = currentSong.title,
                            artist = currentSong.artists.joinToString { it.name },
                            duration = currentSong.duration,
                            album = currentSong.album,
                            skipCache = skipCache,
                        )
                    }
                val videoLyricsDoc =
                    videoLyrics?.let { result ->
                        withContext(Dispatchers.Default) { LyricsUtils.parseMlxDocument(result.lyrics) }
                    }
                if (videoLyrics != null && videoLyricsDoc?.lines?.isNotEmpty() == true &&
                    (
                        videoLyrics.isUserSelected || isLocalLyricsProvider(videoLyrics.provider) ||
                            (videoLyrics.lyrics == rawLyrics && videoLyrics.provider == _uiState.value.rawLyricsProvider) ||
                            prefersLyrics(videoLyrics, LyricsWithProvider(rawLyrics, _uiState.value.rawLyricsProvider.orEmpty()))
                    )
                ) {
                    val enrichedVideoLyrics = enrichLyrics(songId, videoLyricsDoc.lines, requestId, forceAiTranslation)
                    currentCoroutineContext().ensureActive()
                    if (isCurrentLyricsRequest(songId, requestId)) {
                        _uiState.value =
                            _uiState.value.copy(
                                lyrics = enrichedVideoLyrics,
                                lyricsAgents = videoLyricsDoc.agents,
                                lyricsSongwriters =
                                    videoLyricsDoc.song.songwriters.takeIf { it.isNotEmpty() } ?: _uiState.value.lyricsSongwriters,
                                lyricsProvider = lyricsProviderLabel(videoLyrics.provider, videoLyricsDoc),
                                lyricsMeta = videoLyricsDoc.meta,
                                mvLyricsRetimed = true,
                            )
                    }
                    Logger.d(TAG, "[LyricsRetimer] using video-specific lyrics from ${videoLyrics.provider} for $videoId")
                    return@launch
                }

                if (lyricsTimingKind(lyricsDoc.lines) == LyricsTimingKind.UNSYNCED) return@launch

                val mapRetimedDoc =
                    positionMap?.let {
                        Logger.d(
                            TAG,
                            "[LyricsRetimer] applying CounterpartPositionMap",
                        )
                        withContext(Dispatchers.Default) {
                            MusicVideoLyricsRetimer.retimeLyricsWithPositionMap(lyricsDoc, it)
                        }
                    }

                if (mapRetimedDoc != null) {
                    applyRetimedLyrics(songId, requestId, forceAiTranslation, mapRetimedDoc)
                }

                val subtitleRetimedDoc =
                    lyricsRepository.getRetimedMvLyrics(
                        videoId = videoId,
                        richLyrics = lyricsDoc,
                        title = currentSong.title,
                        artist = currentSong.artists.joinToString { it.name },
                        duration = currentSong.duration,
                        album = currentSong.album,
                        minimumScore =
                            if (mapRetimedDoc != null) {
                                MusicVideoLyricsRetimer.SCORE_PRECISE_CONFIDENCE
                            } else {
                                MusicVideoLyricsRetimer.SCORE_MEDIUM_CONFIDENCE
                            },
                    )

                if (subtitleRetimedDoc != null) {
                    applyRetimedLyrics(songId, requestId, forceAiTranslation, subtitleRetimedDoc)
                } else if (mapRetimedDoc == null) {
                    Logger.d(
                        TAG,
                        "[LyricsRetimer] retimedDoc is null or song changed (currentSong=${_uiState.value.currentSong?.id})",
                    )
                }
            }
    }

    private suspend fun applyRetimedLyrics(
        songId: String,
        requestId: Long,
        forceAiTranslation: Boolean,
        retimedDoc: MlxDocument,
    ) {
        if (!isCurrentLyricsRequest(songId, requestId)) return
        Logger.d(
            TAG,
            "[LyricsRetimer] successfully retimed ${retimedDoc.lines.size} lines for $songId",
        )
        val enrichedRetimed = enrichLyrics(songId, retimedDoc.lines, requestId, forceAiTranslation)
        currentCoroutineContext().ensureActive()
        if (isCurrentLyricsRequest(songId, requestId)) {
            _uiState.value =
                _uiState.value.copy(
                    lyrics = enrichedRetimed,
                    lyricsAgents = retimedDoc.agents,
                    lyricsMeta = retimedDoc.meta,
                    lyricsSongwriters =
                        retimedDoc.song.songwriters.takeIf { it.isNotEmpty() } ?: _uiState.value.lyricsSongwriters,
                    mvLyricsRetimed = true,
                )
        }
    }

    fun loadLyricsProviderResults(
        songId: String,
        title: String,
        artist: String = "",
        duration: Long? = null,
        album: String? = null,
        providerOrder: String = Settings.shared.lyricsProviderOrder,
        isCustomQuery: Boolean = false,
    ) {
        _uiState.value.lyricsProviderSearch?.cancel()
        val resolvedDuration =
            if (isCustomQuery) {
                null
            } else {
                duration?.takeIf { it > 0L }
                    ?: _uiState.value
                        .takeIf { it.currentSong?.id == songId }
                        ?.duration
                        ?.takeIf { it > 0L }
            }
        val search =
            LyricsProviderSearch(viewModelScope, providerOrder) { provider ->
                lyricsRepository.getProviderResults(
                    videoId = songId.takeUnless { isCustomQuery && provider != LyricsProvider.USER },
                    title = title,
                    artist = artist,
                    duration = resolvedDuration,
                    album = album.takeUnless { isCustomQuery },
                    preferredProviders = listOf(provider),
                    failOnError = true,
                )
            }
        _uiState.value =
            _uiState.value.copy(
                lyricsQueryTitle = title,
                lyricsQueryArtist = artist,
                lyricsProviderSearch = search,
            )
    }

    fun selectLyricsProviderResult(
        songId: String,
        result: LyricsProviderResult,
    ) {
        lyricsRequestCacheOnly = false
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = retimeLyricsJob
        previousFetchJob?.cancel()
        previousRetimeJob?.cancel()
        _uiState.value.lyricsProviderSearch?.cancel()
        _uiState.value =
            _uiState.value.copy(
                isLyricsLoading = false,
                lyricsTranslationProgress = LyricsTranslationProgress(),
            )
        lyricsFetchJob =
            viewModelScope.launch {
                previousFetchJob?.join()
                previousRetimeJob?.join()
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                val effectiveResult = lyricsRepository.selectProviderResult(songId, result)
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                val document =
                    withContext(Dispatchers.Default) {
                        LyricsUtils.parseMlxDocument(effectiveResult.lyrics)
                    }
                val parsed =
                    document?.lines
                        ?: withContext(Dispatchers.Default) {
                            LyricsUtils.parseLyrics(effectiveResult.lyrics)
                        }
                val mlxSongwriters = document?.song?.songwriters.orEmpty()
                _uiState.value =
                    _uiState.value.copy(
                        lyrics = parsed,
                        lyricsAgents = document?.agents ?: emptyMap(),
                        lyricsSongwriters = mlxSongwriters,
                        lyricsMeta = document?.meta,
                        rawLyrics = effectiveResult.lyrics,
                        rawLyricsProvider = effectiveResult.providerName,
                        lyricsUserSelected = true,
                        lyricsProvider = lyricsProviderLabel(effectiveResult.providerName, document),
                        manualAiTranslationEnabled = false,
                        mvLyricsRetimed = false,
                    )
                if (mlxSongwriters.isEmpty()) {
                    launch {
                        val ytWriters = lyricsRepository.getSongwriters(songId)
                        if (isCurrentLyricsRequest(songId, requestId) && ytWriters.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(lyricsSongwriters = ytWriters)
                        }
                    }
                }
                val enriched = enrichLyrics(songId, parsed, requestId)
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                if (enriched != parsed) {
                    _uiState.value = _uiState.value.copy(lyrics = enriched)
                }
                retimeLyricsIfVideoModeActive(songId, requestId = requestId)
            }
    }

    private suspend fun enrichLyrics(
        songId: String,
        entries: List<LyricsEntry>,
        requestId: Long,
        forceAiTranslation: Boolean = false,
        allowRemoteTranslation: Boolean = true,
        onDictionaryPreparation: suspend () -> Unit = {},
    ): List<LyricsEntry> {
        try {
            return lyricsRepository.enrichLyricsEntries(
                songId,
                entries,
                forceAiTranslation,
                allowRemoteTranslation && failedTranslationRequestId != requestId,
                onDictionaryPreparation,
                onTranslationProgress = { progress ->
                    if (isCurrentLyricsRequest(songId, requestId)) {
                        _uiState.value = _uiState.value.copy(lyricsTranslationProgress = progress)
                    }
                },
                onTranslationFailure = { failure ->
                    if (isCurrentLyricsRequest(songId, requestId)) {
                        failedTranslationRequestId = requestId
                        _uiState.value =
                            _uiState.value.copy(manualAiTranslationEnabled = false, lyricsTranslationProgress = LyricsTranslationProgress())
                        translationFailures.trySend(failure)
                    }
                },
            )
        } finally {
            if (isCurrentLyricsRequest(songId, requestId)) {
                _uiState.value = _uiState.value.copy(lyricsTranslationProgress = LyricsTranslationProgress())
            }
        }
    }

    private fun resolveActiveLyricsOffsetVideoId(
        songId: String,
        isVideoActive: Boolean,
    ): String {
        val currentSong = _uiState.value.currentSong
        return if (currentSong?.isVideoSong == true) {
            currentSong.id
        } else if (isVideoActive) {
            _uiState.value.mvVideoId ?: songId
        } else {
            songId
        }
    }

    private fun syncLyricsOffsetForVideoMode(
        songId: String,
        isVideoActive: Boolean,
    ) {
        viewModelScope.launch {
            val targetId = resolveActiveLyricsOffsetVideoId(songId, isVideoActive)
            val offset = lyricsRepository.getLyricsOffset(targetId)
            if (_uiState.value.currentSong?.id == songId && _uiState.value.isVideoModeActive == isVideoActive) {
                _uiState.value = _uiState.value.copy(lyricsOffsetMs = offset)
            }
        }
    }

    fun setLyricsOffset(
        songId: String,
        offsetMs: Int,
    ) {
        val targetId = resolveActiveLyricsOffsetVideoId(songId, _uiState.value.isVideoModeActive)
        if (_uiState.value.currentSong?.id == songId) {
            _uiState.value = _uiState.value.copy(lyricsOffsetMs = offsetMs)
        }
        lyricsOffsetSaveJob?.cancel()
        lyricsOffsetSaveJob =
            viewModelScope.launch {
                delay(150.milliseconds)
                lyricsRepository.setLyricsOffset(targetId, offsetMs)
            }
    }

    fun refreshEditedLyrics(songId: String) {
        val song = _uiState.value.currentSong?.takeIf { it.id == songId } ?: return
        lyricsRequestKey = null
        loadLyrics(song)
    }

    fun saveCustomLyrics(
        songId: String,
        lyricsText: String,
    ) {
        lyricsRequestCacheOnly = false
        val requestId = ++lyricsRequestId
        val previousFetchJob = lyricsFetchJob
        val previousRetimeJob = retimeLyricsJob
        previousFetchJob?.cancel()
        previousRetimeJob?.cancel()
        _uiState.value = _uiState.value.copy(isLyricsLoading = false, lyricsTranslationProgress = LyricsTranslationProgress())
        lyricsFetchJob =
            viewModelScope.launch {
                previousFetchJob?.join()
                previousRetimeJob?.join()
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                lyricsRepository.saveCustomLyrics(songId, lyricsText)
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                val lyricsDoc =
                    withContext(Dispatchers.Default) {
                        LyricsUtils.parseMlxDocument(lyricsText)
                    }
                val parsed =
                    lyricsDoc?.lines
                        ?: withContext(Dispatchers.Default) {
                            LyricsUtils.parseLyrics(lyricsText)
                        }
                val mlxSongwriters = lyricsDoc?.song?.songwriters.orEmpty()
                _uiState.value =
                    _uiState.value.copy(
                        lyrics = parsed,
                        lyricsAgents = lyricsDoc?.agents ?: emptyMap(),
                        lyricsSongwriters = mlxSongwriters,
                        lyricsMeta = lyricsDoc?.meta,
                        rawLyrics = lyricsText,
                        rawLyricsProvider = USER_LYRICS_PROVIDER,
                        lyricsUserSelected = true,
                        lyricsProvider = USER_LYRICS_PROVIDER,
                        mvLyricsRetimed = false,
                    )
                if (mlxSongwriters.isEmpty()) {
                    launch {
                        val ytWriters = lyricsRepository.getSongwriters(songId)
                        if (isCurrentLyricsRequest(songId, requestId) && ytWriters.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(lyricsSongwriters = ytWriters)
                        }
                    }
                }
                val enriched = enrichLyrics(songId, parsed, requestId)
                if (!isCurrentLyricsRequest(songId, requestId)) return@launch
                if (enriched != parsed) {
                    _uiState.value = _uiState.value.copy(lyrics = enriched)
                }
                retimeLyricsIfVideoModeActive(songId, requestId = requestId)
            }
    }

    data class MusicVideoCanvas(
        val resource: PlaybackResource,
        val clips: List<YouTubeCanvasClip>,
        val cacheKey: String? = null,
        val videoBytes: ByteArray? = null,
        val cachedUrl: String? = null,
    ) {
        val url: String get() = cachedUrl ?: resource.videoUrl.orEmpty()
    }

    data class PlayerUiState(
        val nextCanvasSongId: String? = null,
        val previousCanvasSongId: String? = null,
        val preloadedCanvases: Map<String, SongUiModel> = emptyMap(),
        val currentSong: SongUiModel? = null,
        val isResourceLive: Boolean = false,
        val isPlaying: Boolean = false,
        val duration: Long = 0L,
        val error: String? = null,
        val lyrics: List<LyricsEntry> = emptyList(),
        val lyricsAgents: Map<String, AgentInfo> = emptyMap(),
        val lyricsSongwriters: List<String> = emptyList(),
        val lyricsMeta: MlxMeta? = null,
        val rawLyrics: String? = null,
        val rawLyricsProvider: String? = null,
        val lyricsUserSelected: Boolean = false,
        val isLyricsLoading: Boolean = false,
        val lyricsProvider: String? = null,
        val lyricsQueryTitle: String? = null,
        val lyricsQueryArtist: String? = null,
        val lyricsOffsetMs: Int = 0,
        val lyricsProviderSearch: LyricsProviderSearch? = null,
        val manualAiTranslationEnabled: Boolean = false,
        val lyricsTranslationProgress: LyricsTranslationProgress = LyricsTranslationProgress(),
        val isLyricsVisible: Boolean = false,
        val isLyricsExpanded: Boolean = false,
        val dominantColor: Color = DefaultThemeColor,
        val gradientColors: List<Color> =
            listOf(
                DefaultThemeColor,
                Color(
                    (DefaultThemeColor.red * 0.6f),
                    (DefaultThemeColor.green * 0.6f),
                    (DefaultThemeColor.blue * 0.6f),
                ),
                Color.Black,
            ),
        val isLiked: Boolean = false,
        val repeatMode: Int = 0,
        val isShuffleOn: Boolean = false,
        val queueItems: List<QueueItemUiModel> = emptyList(),
        val queueEntryKeys: List<Long> = emptyList(),
        val currentQueueIndex: Int = -1,
        val isQueueLoading: Boolean = false,
        val queueTitle: String? = null,
        val queueChips: List<RadioQueueChip> = emptyList(),
        val isVideoModeActive: Boolean = false,
        val isVideoModeResolving: Boolean = false,
        val isFullscreen: Boolean = false,
        val isQueueVisible: Boolean = false,
        val isVideoAvailable: Boolean = false,
        val isSubtitleActive: Boolean = false,
        val mvVideoId: String? = null,
        val mvPositionMap: CounterpartPositionMap? = null,
        val counterpartTrackId: String? = null,
        val mvLyricsRetimed: Boolean = false,
    ) {
        val isLive: Boolean get() = isResourceLive || currentSong?.isLive == true
    }

    data class SongUiModel(
        val id: String,
        val title: String,
        val artists: List<ArtistTiny>,
        val thumbnail: String?,
        val duration: Long?,
        val albumId: String? = null,
        val album: String? = null,
        val isExplicit: Boolean = false,
        val explicitness: Boolean? = null,
        val isUploaded: Boolean = false,
        val isVideoSong: Boolean = false,
        val musicVideoType: String? = null,
        val isLiked: Boolean = false,
        val isSaved: Boolean = false,
        val spotifyAnimatedCoverUrl: String? = null,
        val appleMusicAnimatedCoverUrl: String? = null,
        val appleMusicTallAnimatedCoverUrl: String? = null,
        val musicVideoCanvas: MusicVideoCanvas? = null,
        val canvasState: CanvasArtworkState = CanvasArtworkState.NOT_REQUESTED,
        val pendingCanvasSources: Set<CanvasContentSource> = emptySet(),
    ) {
        val displayThumbnail: String? get() = toSongItem().previewThumbnailUrl
        val isOfficialMusicVideo: Boolean
            get() = isOfficialVideoType(musicVideoType)
        val isLive: Boolean
            get() = isLiveType(musicVideoType)

        fun toSongItem(): SongItem =
            SongItem(
                id = id,
                title = title,
                thumbnail = thumbnail,
                artists = artists,
                album = album?.let { AlbumTiny(id = albumId, name = it) },
                duration = duration?.div(1000L),
                isExplicit = isExplicit,
                isUploaded = isUploaded,
                musicVideoType = musicVideoType,
                explicitness = explicitness,
            )
    }

    data class QueueItemUiModel(
        val id: String,
        val title: String,
        val artists: String,
        val thumbnail: String?,
        val duration: Long?,
        val isExplicit: Boolean = false,
        val isLiked: Boolean = false,
        val isSaved: Boolean = false,
        val isSuggested: Boolean = false,
        val thumbnailAspectRatio: Float = 1f,
        val musicVideoType: String? = null,
    )

    private suspend fun SongItem.toQueueItemUiModel(isSuggested: Boolean): QueueItemUiModel =
        with(SongMetadataState.apply(this)) {
            QueueItemUiModel(
                id = id,
                title = title,
                artists = artists.joinToString(", ") { it.name }.ifBlank { getString(Res.string.unknown) },
                thumbnail = thumbnail,
                duration = duration.toDurationMillis(),
                isExplicit = isExplicit,
                isLiked = false,
                isSaved = libraryRemoveToken != null,
                isSuggested = isSuggested,
                thumbnailAspectRatio = previewAspectRatio,
                musicVideoType = musicVideoType,
            )
        }

    private fun Long?.toDurationMillis(): Long? = this?.coerceAtLeast(0L)?.times(1000L)

    companion object {
        private const val QUEUE_PRELOAD_COUNT = 5
        private const val TAG = "PlayerViewModel"
    }
}

internal fun computeSeekTarget(
    currentMs: Long,
    durationMs: Long,
    deltaMs: Long,
): Long =
    if (durationMs > 0L) {
        (currentMs + deltaMs).coerceIn(0L, durationMs)
    } else {
        (currentMs + deltaMs).coerceAtLeast(0L)
    }

internal fun updateQueueFlags(
    items: List<PlayerViewModel.QueueItemUiModel>,
    recommended: List<Boolean>,
    currentIndex: Int,
    likedIds: Set<String>,
): List<PlayerViewModel.QueueItemUiModel> {
    var updated: MutableList<PlayerViewModel.QueueItemUiModel>? = null
    items.forEachIndexed { index, item ->
        val liked = item.id in likedIds
        val suggested = recommended[index] && index > currentIndex
        if (item.isLiked != liked || item.isSuggested != suggested) {
            val result = updated ?: items.toMutableList().also { updated = it }
            result[index] = item.copy(isLiked = liked, isSuggested = suggested)
        }
    }
    return updated ?: items
}
