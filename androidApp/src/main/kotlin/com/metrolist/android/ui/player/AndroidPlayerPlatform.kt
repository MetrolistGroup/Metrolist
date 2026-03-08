package com.metrolist.android.ui.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import com.metrolist.android.App
import com.metrolist.android.LocalPlaylistAdder
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.android.ui.download.rememberDownloadPermissionGate
import com.metrolist.android.ui.screens.PlayerDeviceConnectSheet
import com.metrolist.android.utils.shareImage
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cast_permission_required
import com.metrolist.shared.generated.resources.queued_songs
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.generated.resources.share_lyrics
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.lyrics.LyricsShareDialog
import com.metrolist.shared.ui.player.PlayerArtworkCrossfade
import com.metrolist.shared.ui.player.PlayerPlatform
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import com.metrolist.android.ui.components.AnimatedCoverVideo as AndroidAnimatedCoverVideo
import com.metrolist.android.ui.menu.LyricsMenu as AndroidLyricsMenu
import com.metrolist.android.ui.menu.PlayerMenu as AndroidPlayerMenu
import com.metrolist.android.ui.menu.QueueMenu as AndroidQueueMenu
import com.metrolist.android.ui.menu.SongDetailsSheet as AndroidSongDetailsSheet
import com.metrolist.shared.ui.components.rememberModalSheetState as rememberBottomSheetState

@OptIn(ExperimentalMaterial3Api::class)
class AndroidPlayerPlatform(
    private val activity: Activity?,
    private val playerConnection: PlayerConnection,
    private val listenTogetherManager: ListenTogetherManager?,
) : PlayerPlatform {
    override val supportsPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    override val supportsVideoPlayback = true
    override val supportsCasting = true

    @Composable
    override fun isListenTogetherGuest(): Boolean {
        val manager = listenTogetherManager
        val role by manager?.role?.collectAsState() ?: remember { mutableStateOf(ListenTogetherRoomRole.NONE) }
        val isInRoom by manager?.isInRoom?.collectAsState() ?: remember { mutableStateOf(false) }
        return isInRoom && role == ListenTogetherRoomRole.GUEST
    }

    @Composable
    override fun isDeviceConnectActive(): Boolean {
        val state by AndroidDeviceConnect.manager.remote.state
            .collectAsState()
        return state.sessionId != null
    }

    @Composable
    override fun isTouchExplorationEnabled(): Boolean {
        val context = LocalContext.current
        val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
        var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
        DisposableEffect(manager) {
            val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
            manager?.addTouchExplorationStateChangeListener(listener)
            enabled = manager?.isTouchExplorationEnabled == true
            onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
        }
        return enabled
    }

    @Composable
    override fun artworkCrossfade(currentSongId: String?): PlayerArtworkCrossfade {
        val crossfade = playerConnection.crossfadeState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
        val nextArtwork by remember(crossfade, currentSongId) {
            derivedStateOf {
                val transition = crossfade.value ?: return@derivedStateOf null
                if (transition.progress >= 1f) {
                    null
                } else if (transition.nextSong.id == currentSongId) {
                    transition.outgoingSong?.thumbnail
                } else {
                    transition.nextSong.thumbnail
                }
            }
        }
        return remember(nextArtwork, crossfade) {
            PlayerArtworkCrossfade(
                nextArtworkUrl = nextArtwork,
                progressProvider = {
                    val transition = crossfade.value
                    transition?.let {
                        if (it.nextSong.id == currentSongId) 1f - it.progress else it.progress
                    } ?: 0f
                },
            )
        }
    }

    @Composable
    override fun rememberCastDialogLauncher(onShowCastDialog: () -> Unit): () -> Unit {
        val castPermissionRequiredText = stringResource(Res.string.cast_permission_required)

        val context = LocalContext.current
        val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted && Settings.shared.castEnabled) {
                    onShowCastDialog()
                } else {
                    Toast.makeText(context, castPermissionRequiredText, Toast.LENGTH_SHORT).show()
                }
            }
        return launch@{
            if (!Settings.shared.castEnabled) return@launch
            if (
                Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
            ) {
                onShowCastDialog()
            } else {
                permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
    }

    @Composable
    override fun PlayerWindowEffects(
        hideSystemBars: Boolean,
        lockLandscape: Boolean,
    ) {
        val isTv = LocalIsTv.current
        val hostView = LocalView.current
        LaunchedEffect(lockLandscape, isTv) {
            if (!isTv) {
                activity?.requestedOrientation =
                    if (lockLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        DisposableEffect(isTv) {
            onDispose {
                if (!isTv) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        DisposableEffect(hostView, hideSystemBars) {
            val window = activity?.window ?: return@DisposableEffect onDispose {}
            val insetsController = WindowCompat.getInsetsController(window, hostView)
            if (hideSystemBars) {
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                if (hideSystemBars) insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun enterPictureInPicture(
        isPlaying: Boolean,
        hasVideo: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val activity = activity ?: return
        val videoPlayer = playerConnection.videoPlayer
        PictureInPictureHelper.enterPictureInPicture(
            activity = activity,
            isPlaying = isPlaying,
            videoSize = videoPlayer?.videoSize,
            hasVideo = hasVideo && videoPlayer != null,
        )
    }

    @Composable
    override fun MusicVideoSurface(
        modifier: Modifier,
        cornerRadius: Dp,
        fillContainer: Boolean,
        showControls: Boolean,
        useSurfaceView: Boolean,
        onFullscreenClick: (() -> Unit)?,
        onPiPClick: (() -> Unit)?,
    ) {
        val isConnected by playerConnection.isConnected.collectAsState()
        val song by playerConnection.currentSong.collectAsState()
        MusicVideoSurface(
            player = if (isConnected) playerConnection.videoPlayer else null,
            artworkUrl = song?.thumbnail,
            modifier = modifier,
            cornerRadius = cornerRadius,
            fillContainer = fillContainer,
            useSurfaceView = useSurfaceView,
            showControls = showControls,
            onFullscreenClick = onFullscreenClick,
            onPiPClick = onPiPClick.takeIf { supportsPictureInPicture },
        )
    }

    override fun canvasCacheKey(
        songId: String,
        source: CanvasContentSource,
        quality: String,
    ): String = CanvasCache.key(songId, source.name, quality)

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
    ) = AndroidAnimatedCoverVideo(
        url = url,
        modifier = modifier,
        headers = headers,
        playbackResource = playbackResource,
        cacheKey = cacheKey,
        playing = playing,
        clips = clips,
        dataSourceFactory = remember(videoBytes) { videoBytes?.let { bytes -> DataSource.Factory { ByteArrayDataSource(bytes) } } },
        onError = onError,
        onReadyChanged = onReadyChanged,
        onFrame = onFrame,
    )

    @Composable
    override fun PlayerMenu(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
        onNavigateToAlbum: (String) -> Unit,
        onNavigateToArtist: (String) -> Unit,
        onNavigateToListenTogether: () -> Unit,
        onShowSongDetails: () -> Unit,
    ) {
        val volume by playerConnection.volume.collectAsState()
        val playbackSpeed by playerConnection.playbackSpeedFlow.collectAsState()
        val playbackPitch by playerConnection.playbackPitchFlow.collectAsState()
        AndroidPlayerMenu(
            playerViewModel = viewModel,
            volume = volume,
            onVolumeChange = playerConnection::setVolume,
            playbackSpeed = playbackSpeed,
            onPlaybackSpeedChange = playerConnection::setPlaybackSpeed,
            playbackPitch = playbackPitch,
            onPlaybackPitchChange = playerConnection::setPlaybackPitch,
            onDismiss = onDismiss,
            onNavigateToAlbum = onNavigateToAlbum,
            onNavigateToArtist = onNavigateToArtist,
            onNavigateToListenTogether = onNavigateToListenTogether,
            onShowSongDetails = onShowSongDetails,
        )
    }

    @Composable
    override fun SongDetailsSheet(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
    ) {
        val uiState by viewModel.uiState.collectAsState()
        val detailSong by playerConnection.currentSong.collectAsState()
        val volume by playerConnection.volume.collectAsState()
        AndroidSongDetailsSheet(
            song = detailSong?.let { it.copy(thumbnail = uiState.currentSong?.displayThumbnail ?: it.thumbnail) },
            playbackResource = playerConnection.getCurrentPlaybackResource(),
            volume = volume,
            onDismiss = onDismiss,
        )
    }

    @Composable
    override fun lyricsShareAction(viewModel: PlayerViewModel): ((String) -> Unit)? {
        val uiState by viewModel.uiState.collectAsState()
        val song = uiState.currentSong ?: return null
        val context = LocalContext.current
        var selectedText by remember(song.id) { mutableStateOf<String?>(null) }
        val shareTitle = stringResource(Res.string.share_lyrics)
        PopupContent(selectedText) { text ->
            LyricsShareDialog(
                text = text,
                songId = song.id,
                title = song.title,
                artist = song.artists.joinToString { it.name },
                artworkUrl = song.displayThumbnail,
                exportLabel = stringResource(Res.string.share),
                onExport = { shareImage(context, it, shareTitle) },
                onShareText = { content ->
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, content)
                            },
                            shareTitle,
                        ),
                    )
                },
                onDismiss = { selectedText = null },
            )
        }
        return { selectedText = it }
    }

    @Composable
    override fun LyricsMenu(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
        onShowLyricsOffset: () -> Unit,
    ) {
        val uiState by viewModel.uiState.collectAsState()
        val currentSong = uiState.currentSong
        AndroidLyricsMenu(
            songId = currentSong?.id.orEmpty(),
            lyrics = uiState.lyrics,
            rawLyrics = uiState.rawLyrics,
            songTitle = currentSong?.title ?: stringResource(Res.string.unknown),
            artistName = currentSong?.artists?.joinToString(", ") { it.displayName } ?: stringResource(Res.string.unknown),
            searchTitle = uiState.lyricsQueryTitle ?: currentSong?.title.orEmpty(),
            searchArtist = uiState.lyricsQueryArtist ?: currentSong?.artists?.joinToString(", ") { it.name }.orEmpty(),
            artworkUrl = currentSong?.displayThumbnail,
            lyricsOffsetMs = uiState.lyricsOffsetMs,
            manualAiTranslationEnabled =
                uiState.manualAiTranslationEnabled ||
                    uiState.lyrics.any { !it.translatedText.isNullOrBlank() || !it.backgroundTranslatedText.isNullOrBlank() },
            providerSearch = uiState.lyricsProviderSearch,
            onDismiss = onDismiss,
            onManualAiTranslationChange = { enabled ->
                viewModel.setManualLyricsTranslation(enabled)
            },
            onRefetchLyrics = { customTitle, customArtist ->
                currentSong?.let { song ->
                    val defaultArtist = song.artists.joinToString { it.name }
                    val resolvedTitle = customTitle?.takeIf { it.isNotBlank() } ?: song.title
                    val resolvedArtist = customArtist?.trim() ?: defaultArtist
                    val isCustomQuery =
                        (customTitle != null || customArtist != null) &&
                            LyricsUtils.isCustomLyricsQuery(resolvedTitle, resolvedArtist, song.title, defaultArtist)
                    viewModel.loadLyrics(
                        songId = song.id,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        duration = song.duration,
                        album = song.album,
                        skipCache = true,
                        forceAiTranslation = uiState.manualAiTranslationEnabled,
                        isCustomQuery = isCustomQuery,
                    )
                }
            },
            onLoadProviderResults = { customTitle, customArtist ->
                currentSong?.let { song ->
                    val defaultArtist = song.artists.joinToString { it.name }
                    val resolvedTitle = customTitle?.takeIf { it.isNotBlank() } ?: song.title
                    val resolvedArtist = customArtist?.trim() ?: defaultArtist
                    val isCustomQuery =
                        (customTitle != null || customArtist != null) &&
                            LyricsUtils.isCustomLyricsQuery(resolvedTitle, resolvedArtist, song.title, defaultArtist)
                    viewModel.loadLyricsProviderResults(
                        songId = song.id,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        duration = song.duration,
                        album = song.album,
                        isCustomQuery = isCustomQuery,
                    )
                }
            },
            onSelectProviderResult = { result ->
                currentSong?.let { song -> viewModel.selectLyricsProviderResult(song.id, result) }
            },
            onShowLyricsOffset = onShowLyricsOffset,
            onLyricsChanged = {
                currentSong?.let { song -> viewModel.refreshEditedLyrics(song.id) }
            },
            onNewAiTranslation = {
                viewModel.setManualLyricsTranslation(true)
            },
        )
    }

    @Composable
    override fun DeviceConnectSheet(
        onDismiss: () -> Unit,
        onNavigateToIntegrations: () -> Unit,
    ) = PlayerDeviceConnectSheet(onDismissRequest = onDismiss, onNavigateToIntegrations = onNavigateToIntegrations)

    @Composable
    override fun ModalSheet(
        onDismissRequest: () -> Unit,
        sheetMaxWidth: Dp,
        dragHandle: @Composable () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) = AdaptiveModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        sheetMaxWidth = sheetMaxWidth,
        dragHandle = dragHandle,
        content = content,
    )

    @Composable
    override fun downloadedSongIds(): Set<String> {
        val database: MusicDatabase = koinInject()
        val downloaded by remember(database) { database.dao().cachedAudioByKind(KIND_DOWNLOAD) }.collectAsState(initial = emptyList())
        return remember(downloaded) { downloaded.mapTo(mutableSetOf()) { it.songId } }
    }

    @Composable
    override fun rememberSongDownloader(): (List<SongItem>) -> Unit {
        val context = LocalContext.current
        val resources = LocalResources.current
        val downloadManager: SongDownloadManager = koinInject()
        val requireDownloadNotifications = rememberDownloadPermissionGate()
        val coroutineScope = rememberCoroutineScope()
        return { songs ->
            requireDownloadNotifications {
                coroutineScope.launch {
                    val requested = songs.distinctBy { it.id }.size
                    val completed = downloadManager.downloadAll(songs)
                    Toast
                        .makeText(
                            context,
                            getPluralString(Res.plurals.queued_songs, requested, completed, requested),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }

    @Composable
    override fun rememberPlaylistAdder(): (List<SongItem>) -> Unit = LocalPlaylistAdder.current

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
        val addToPlaylist = LocalPlaylistAdder.current
        AndroidQueueMenu(
            song = song,
            songTitle = song.title,
            artistName = song.artists.joinToString(", ") { it.displayName }.ifBlank { stringResource(Res.string.unknown) },
            artists = song.artists,
            albumName = song.album?.name,
            duration = durationText,
            thumbnailUrl = song.thumbnail,
            isLiked = isLiked,
            isDisliked = SongLikeState.isDisliked(song.id),
            onDislike = { playerConnection.toggleDislike(song) },
            onDismiss = onDismiss,
            onRemoveFromQueue = onRemoveFromQueue,
            onPlayNext = onPlayNext,
            onStartRadio = onStartRadio,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = { addToPlaylist(listOf(song)) },
            onLike = onLike,
            onGoToAlbum = onGoToAlbum,
            onGoToArtist = onGoToArtist,
            onShare = onShare,
            onAltShare = onAltShare,
            onDownload = onDownload,
            onNavigateToListenTogether = onNavigateToListenTogether,
        )
    }

    override fun prepareListenTogetherQueueImport(
        title: String,
        songs: List<SongItem>,
    ) {
        val manager = listenTogetherManager ?: AndroidListenTogetherManager.initialize(activity ?: App.instance, playerConnection)
        manager.prepareQueueImport(title, songs)
    }
}
