package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.listen_together
import com.metrolist.shared.generated.resources.playback_pitch
import com.metrolist.shared.generated.resources.playback_speed
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.tempo_and_pitch
import com.metrolist.shared.generated.resources.unavailable
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.components.ModalSheetState
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SleepTimerDialog
import com.metrolist.shared.ui.components.TempoPitchControls
import com.metrolist.shared.ui.components.rememberModalSheetState
import com.metrolist.shared.ui.equalizer.EqualizerDialog
import com.metrolist.shared.ui.equalizer.MetroEqSheet
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.LyricsMenu
import com.metrolist.shared.ui.menu.MediaDownloadState
import com.metrolist.shared.ui.menu.MediaMenuSheetContent
import com.metrolist.shared.ui.menu.equalizerMenuAction
import com.metrolist.shared.ui.menu.rememberDeleteUploadAction
import com.metrolist.shared.ui.menu.songMediaMenuModel
import com.metrolist.shared.ui.menu.tempoPitchMenuAction
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.player.PlayerArtworkCrossfade
import com.metrolist.shared.ui.player.PlayerPlatform
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.viewmodels.LyricsUiState
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
internal class MobilePlayerPlatform(
    private val database: MusicDatabase,
    private val onDownloadSongs: (List<SongItem>) -> Unit,
    private val onRemoveDownloads: (List<String>) -> Unit,
    private val onAddToPlaylist: (List<SongItem>) -> Unit,
    private val listenTogether: ListenTogetherManager,
    private val touchExplorationEnabled: Boolean,
    private val shareLyrics: @Composable (PlayerViewModel) -> ((String) -> Unit)?,
) : PlayerPlatform {
    @Composable
    override fun lyricsShareAction(viewModel: PlayerViewModel): ((String) -> Unit)? = shareLyrics(viewModel)

    override val supportsPictureInPicture = false
    override val supportsVideoPlayback = false
    override val supportsCasting = false

    @Composable
    override fun isListenTogetherGuest(): Boolean {
        val inRoom by listenTogether.isInRoom.collectAsState()
        val role by listenTogether.role.collectAsState()
        return inRoom && role == ListenTogetherRoomRole.GUEST
    }

    @Composable
    override fun isDeviceConnectActive() = false

    @Composable
    override fun isTouchExplorationEnabled() = touchExplorationEnabled

    @Composable
    override fun artworkCrossfade(currentSongId: String?) = PlayerArtworkCrossfade()

    @Composable
    override fun rememberCastDialogLauncher(onShowCastDialog: () -> Unit): () -> Unit = onShowCastDialog

    @Composable
    override fun PlayerWindowEffects(
        hideSystemBars: Boolean,
        lockLandscape: Boolean,
    ) = Unit

    override fun enterPictureInPicture(
        isPlaying: Boolean,
        hasVideo: Boolean,
    ) = Unit

    @Composable
    override fun MusicVideoSurface(
        modifier: Modifier,
        cornerRadius: Dp,
        fillContainer: Boolean,
        showControls: Boolean,
        useSurfaceView: Boolean,
        onFullscreenClick: (() -> Unit)?,
        onPiPClick: (() -> Unit)?,
    ) = Box(modifier)

    @Composable
    override fun AnimatedCoverVideo(
        url: String,
        headers: Map<String, String>,
        playbackResource: PlaybackResource?,
        cacheKey: String?,
        videoBytes: ByteArray?,
        clips: List<YouTubeCanvasClip>,
        playing: Boolean,
        onError: (() -> Unit)?,
        onReadyChanged: ((Boolean) -> Unit)?,
        onFrame: ((ImageBitmap?) -> Unit)?,
        modifier: Modifier,
    ) {
        if (playbackResource != null || videoBytes != null || clips.isNotEmpty()) {
            LaunchedEffect(url) { onError?.invoke() }
            return
        }
        MobileAnimatedCoverVideo(
            url = url,
            headers = headers,
            playing = playing,
            onError = onError,
            onReadyChanged = onReadyChanged,
            modifier = modifier,
        )
    }

    @Composable
    override fun PlayerMenu(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
        onNavigateToAlbum: (String) -> Unit,
        onNavigateToArtist: (String) -> Unit,
        onNavigateToListenTogether: () -> Unit,
        onShowSongDetails: () -> Unit,
    ) {
        val uiState by viewModel.uiState.collectAsState()
        val song = uiState.currentSong?.toSongItem() ?: return onDismiss()
        val deleteUploadAction = rememberDeleteUploadAction(song, database, onDismiss)
        val isDownloaded = song.id in downloadedSongIds()
        val playerSession = LocalPlayerSession.current
        val isGuest = isListenTogetherGuest()
        val controller = LocalPlaybackController.current
        val playbackState by controller.state.collectAsState()
        val inRoom by listenTogether.isInRoom.collectAsState()
        val scope = rememberCoroutineScope()
        val menuSheetState = rememberModalSheetState()
        var showEqualizer by remember { mutableStateOf(false) }
        var showTempoPitch by remember { mutableStateOf(false) }
        if (showEqualizer) {
            EqualizerDialog(
                onDismiss = onDismiss,
                isAudioProcessingAvailable = playbackState.isAudioProcessingAvailable,
                metroEqContent = { close, searchAutoEq ->
                    MetroEqSheet(
                        onDismiss = close,
                        initialAutoEqSearch = searchAutoEq,
                        varispeed = Settings.shared.varispeed,
                        isAudioProcessingAvailable = playbackState.isAudioProcessingAvailable,
                    ) { onBack ->
                        Column {
                            if (!playbackState.isPitchProcessingAvailable) {
                                Text(stringResource(Res.string.playback_pitch))
                                Text(stringResource(Res.string.unavailable))
                            }
                            TempoPitchControls(
                                speed = playbackState.playbackSpeed,
                                pitch = playbackState.playbackPitch,
                                onSpeedChange = controller::setPlaybackSpeed,
                                onPitchChange = if (playbackState.isPitchProcessingAvailable) controller::setPlaybackPitch else null,
                                speedEnabled = !inRoom,
                                varispeed = Settings.shared.varispeed,
                                onDone = onBack,
                            )
                        }
                    }
                },
            )
            return
        }
        PopupVisibility(visible = showTempoPitch) {
            AlertDialog(
                onDismissRequest = {
                    showTempoPitch = false
                    onDismiss()
                },
                title = { Text(stringResource(if (Settings.shared.varispeed) Res.string.playback_speed else Res.string.tempo_and_pitch)) },
                text = {
                    Column {
                        if (!playbackState.isPitchProcessingAvailable) {
                            Text(stringResource(Res.string.playback_pitch))
                            Text(stringResource(Res.string.unavailable))
                        }
                        TempoPitchControls(
                            speed = playbackState.playbackSpeed,
                            pitch = playbackState.playbackPitch,
                            onSpeedChange = controller::setPlaybackSpeed,
                            onPitchChange = if (playbackState.isPitchProcessingAvailable) controller::setPlaybackPitch else null,
                            speedEnabled = !inRoom,
                            varispeed = Settings.shared.varispeed,
                            onDone = {
                                showTempoPitch = false
                                onDismiss()
                            },
                        )
                    }
                },
                confirmButton = {},
            )
        }
        var showSleepTimer by remember { mutableStateOf(false) }
        if (playerSession != null) {
            PopupVisibility(visible = showSleepTimer) {
                val timerState by playerSession.sleepTimerState.collectAsState()
                SleepTimerDialog(
                    state = timerState,
                    onDismiss = {
                        showSleepTimer = false
                        onDismiss()
                    },
                    onStart = playerSession::startSleepTimer,
                    onStopAfterCurrentSong = playerSession::stopAfterCurrentSong,
                    onCancelTimer = playerSession::cancelSleepTimer,
                )
            }
        }
        MenuSheet(onDismiss, sheetState = menuSheetState) {
            MediaMenuSheetContent(
                songMediaMenuModel(
                    song = song,
                    deleteUploadAction = deleteUploadAction,
                    isLiked = uiState.isLiked,
                    isDisliked = SongLikeState.isDisliked(song.id),
                    isGuest = isGuest,
                    onSuggestToHost = { listenTogether.suggestTrack(song) },
                    onDismiss = onDismiss,
                    onLike = viewModel::toggleLike,
                    onDislike = playerSession?.let { { it.toggleDislike(song) } },
                    onBlockArtist =
                        song.artists.firstOrNull { it.id != null }?.let { artist ->
                            { BlockedContent.blockArtist(artist) }
                        },
                    onStartRadio = { viewModel.startRadio(song) },
                    onAddToPlaylist = { onAddToPlaylist(listOf(song)) },
                    downloadState = downloadState(isDownloaded),
                    onDownload = { if (isDownloaded) onRemoveDownloads(listOf(song.id)) else onDownloadSongs(listOf(song)) },
                    onGoToArtist = onNavigateToArtist,
                    onGoToAlbum = song.album?.id?.let { albumId -> { onNavigateToAlbum(albumId) } },
                    onShare = viewModel::shareSong,
                    onAltShare = { viewModel.copySongLink() },
                    isPlayerMenu = true,
                    onShowDetails = onShowSongDetails,
                    additionalActions =
                        listOfNotNull(
                            equalizerMenuAction(
                                onOpen = {
                                    scope.launch {
                                        if (menuSheetState.hide()) showEqualizer = true
                                    }
                                },
                                showDescription = false,
                            ),
                            tempoPitchMenuAction(varispeed = Settings.shared.varispeed) { showTempoPitch = true },
                            playerSession?.takeUnless { isGuest }?.let {
                                PlayerMenuAction(
                                    icon = MetrolistIcons.Outlined.Bedtime,
                                    title = stringResource(Res.string.sleep_timer),
                                    onClick = { showSleepTimer = true },
                                )
                            },
                            PlayerMenuAction(
                                icon = MetrolistIcons.Outlined.Group,
                                title = stringResource(Res.string.listen_together),
                                onClick = {
                                    onDismiss()
                                    onNavigateToListenTogether()
                                },
                            ).takeIf { Settings.shared.listenTogetherEnabled },
                        ),
                ),
            )
        }
    }

    @Composable
    override fun SongDetailsSheet(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
    ) {
        val controller = LocalPlaybackController.current
        val state by controller.state.collectAsState()
        MobileSongDetailsSheet(state = state, database = database, onDismiss = onDismiss)
    }

    @Composable
    override fun LyricsMenu(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
        onShowLyricsOffset: () -> Unit,
    ) {
        val uiState by viewModel.uiState.collectAsState()
        val current = uiState.currentSong ?: return onDismiss()
        val song = current.toSongItem()
        val artist = current.artists.joinToString { it.name }
        LyricsMenu(
            song = song,
            lyricsUiState =
                LyricsUiState(
                    songId = current.id,
                    lyrics = uiState.lyrics,
                    rawLyrics = uiState.rawLyrics,
                    songwriters = uiState.lyricsSongwriters,
                    agents = uiState.lyricsAgents,
                    provider = uiState.lyricsProvider,
                    queryTitle = uiState.lyricsQueryTitle,
                    queryArtist = uiState.lyricsQueryArtist,
                    timingOffsetMs = uiState.lyricsOffsetMs,
                    isLoading = uiState.isLyricsLoading,
                    providerSearch = uiState.lyricsProviderSearch,
                    manualAiTranslationEnabled = uiState.manualAiTranslationEnabled,
                ),
            onDismiss = onDismiss,
            onRefetch = { customTitle, customArtist ->
                val resolvedTitle = customTitle?.takeIf { it.isNotBlank() } ?: current.title
                val resolvedArtist = customArtist?.trim() ?: artist
                val isCustomQuery =
                    (customTitle != null || customArtist != null) &&
                        LyricsUtils.isCustomLyricsQuery(resolvedTitle, resolvedArtist, current.title, artist)
                viewModel.loadLyrics(
                    songId = current.id,
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    duration = current.duration,
                    album = current.album,
                    skipCache = true,
                    forceAiTranslation = uiState.manualAiTranslationEnabled,
                    isCustomQuery = isCustomQuery,
                )
            },
            onLoadProviders = { customTitle, customArtist ->
                val resolvedTitle = customTitle?.takeIf { it.isNotBlank() } ?: current.title
                val resolvedArtist = customArtist?.trim() ?: artist
                val isCustomQuery =
                    (customTitle != null || customArtist != null) &&
                        LyricsUtils.isCustomLyricsQuery(resolvedTitle, resolvedArtist, current.title, artist)
                viewModel.loadLyricsProviderResults(
                    songId = current.id,
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    duration = current.duration,
                    album = current.album,
                    isCustomQuery = isCustomQuery,
                )
            },
            onSelectProvider = { result -> viewModel.selectLyricsProviderResult(current.id, result) },
            onShowLyricsOffset = onShowLyricsOffset,
            onLyricsChanged = { viewModel.refreshEditedLyrics(current.id) },
            onManualAiTranslationChange = viewModel::setManualLyricsTranslation,
        )
    }

    @Composable
    override fun DeviceConnectSheet(
        onDismiss: () -> Unit,
        onNavigateToIntegrations: () -> Unit,
    ) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun ModalSheet(
        onDismissRequest: () -> Unit,
        sheetMaxWidth: Dp,
        dragHandle: @Composable () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) = ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetMaxWidth = sheetMaxWidth,
        shape = ComponentShapes.BottomSheetShape,
        dragHandle = dragHandle,
        content = content,
    )

    @Composable
    override fun downloadedSongIds(): Set<String> {
        val downloaded by remember(database) { database.dao().cachedAudioByKind(KIND_DOWNLOAD) }.collectAsState(emptyList())
        return remember(downloaded) { downloaded.mapTo(mutableSetOf()) { it.songId } }
    }

    @Composable
    override fun rememberSongDownloader(): (List<SongItem>) -> Unit = onDownloadSongs

    @Composable
    override fun rememberPlaylistAdder(): (List<SongItem>) -> Unit = onAddToPlaylist

    @Composable
    override fun QueueMenu(
        song: SongItem,
        durationText: String?,
        isLiked: Boolean,
        onDismiss: () -> Unit,
        onRemoveFromQueue: (() -> Unit)?,
        onPlayNext: () -> Unit,
        onStartRadio: () -> Unit,
        onAddToQueue: () -> Unit,
        onLike: () -> Unit,
        onGoToAlbum: (() -> Unit)?,
        onGoToArtist: (String) -> Unit,
        onShare: () -> Unit,
        onAltShare: (() -> Unit)?,
        onDownload: () -> Unit,
        onNavigateToListenTogether: () -> Unit,
    ) {
        val isDownloaded = song.id in downloadedSongIds()
        val playerSession = LocalPlayerSession.current
        val menuSheetState = rememberModalSheetState()
        val scope = rememberCoroutineScope()
        var showDetails by remember(song.id) { mutableStateOf(false) }
        if (showDetails) {
            val playbackState by LocalPlaybackController.current.state.collectAsState()
            MobileSongDetailsSheet(state = playbackState, song = song, database = database, onDismiss = onDismiss)
            return
        }
        MenuSheet(onDismiss, sheetState = menuSheetState) {
            MediaMenuSheetContent(
                songMediaMenuModel(
                    song = song,
                    deleteUploadAction = rememberDeleteUploadAction(song, database, onDismiss),
                    onDismiss = onDismiss,
                    isLiked = isLiked,
                    isGuest = isListenTogetherGuest(),
                    onSuggestToHost = { listenTogether.suggestTrack(song) },
                    isDisliked = SongLikeState.isDisliked(song.id),
                    onLike = onLike,
                    onDislike = playerSession?.let { { it.toggleDislike(song) } },
                    onStartRadio = onStartRadio,
                    onShare = onShare,
                    onAltShare = onAltShare,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    downloadState = downloadState(isDownloaded),
                    onDownload = { if (isDownloaded) onRemoveDownloads(listOf(song.id)) else onDownload() },
                    onShowDetails = {
                        scope.launch { if (menuSheetState.hide()) showDetails = true }
                    },
                    onRemove = onRemoveFromQueue,
                    onGoToArtist = onGoToArtist,
                    onGoToAlbum = onGoToAlbum,
                    onAddToPlaylist = {
                        onDismiss()
                        onAddToPlaylist(listOf(song))
                    },
                ),
            )
        }
    }

    private fun downloadState(isDownloaded: Boolean) =
        if (isDownloaded) MediaDownloadState.DOWNLOADED else MediaDownloadState.NOT_DOWNLOADED

    override fun prepareListenTogetherQueueImport(
        title: String,
        songs: List<SongItem>,
    ) = listenTogether.prepareQueueImport(title, songs)

    @Composable
    private fun MenuSheet(
        onDismiss: () -> Unit,
        sheetState: ModalSheetState = rememberModalSheetState(),
        content: @Composable ColumnScope.() -> Unit,
    ) = ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = ComponentShapes.BottomSheetShape) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            content = content,
        )
    }
}
