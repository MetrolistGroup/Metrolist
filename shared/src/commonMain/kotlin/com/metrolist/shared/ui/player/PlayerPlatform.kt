package com.metrolist.shared.ui.player

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.youtube.playback.PlaybackResource

data class PlayerArtworkCrossfade(
    val nextArtworkUrl: String? = null,
    val progressProvider: () -> Float = { 0f },
) {
    val progress: Float get() = progressProvider()
}

@Stable
interface PlayerPlatform {
    val supportsPictureInPicture: Boolean
    val supportsVideoPlayback: Boolean
    val supportsCasting: Boolean

    fun canvasCacheKey(
        songId: String,
        source: CanvasContentSource,
        quality: String,
    ): String? = null

    @Composable
    fun isListenTogetherGuest(): Boolean

    @Composable
    fun isDeviceConnectActive(): Boolean

    @Composable
    fun isTouchExplorationEnabled(): Boolean

    @Composable
    fun artworkCrossfade(currentSongId: String?): PlayerArtworkCrossfade

    @Composable
    fun rememberCastDialogLauncher(onShowCastDialog: () -> Unit): () -> Unit

    @Composable
    fun PlayerWindowEffects(
        hideSystemBars: Boolean,
        lockLandscape: Boolean,
    )

    fun enterPictureInPicture(
        isPlaying: Boolean,
        hasVideo: Boolean,
    )

    @Composable
    fun MusicVideoSurface(
        modifier: Modifier,
        cornerRadius: Dp,
        fillContainer: Boolean,
        showControls: Boolean,
        useSurfaceView: Boolean,
        onFullscreenClick: (() -> Unit)?,
        onPiPClick: (() -> Unit)?,
    )

    @Composable
    fun AnimatedCoverVideo(
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
    )

    @Composable
    fun PlayerMenu(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
        onNavigateToAlbum: (String) -> Unit,
        onNavigateToArtist: (String) -> Unit,
        onNavigateToListenTogether: () -> Unit,
        onShowSongDetails: () -> Unit,
    )

    @Composable
    fun SongDetailsSheet(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
    )

    @Composable
    fun lyricsShareAction(viewModel: PlayerViewModel): ((String) -> Unit)? = null

    @Composable
    fun LyricsMenu(
        viewModel: PlayerViewModel,
        onDismiss: () -> Unit,
        onShowLyricsOffset: () -> Unit,
    )

    @Composable
    fun DeviceConnectSheet(
        onDismiss: () -> Unit,
        onNavigateToIntegrations: () -> Unit,
    )

    @Composable
    fun ModalSheet(
        onDismissRequest: () -> Unit,
        sheetMaxWidth: Dp,
        dragHandle: @Composable () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    )

    @Composable
    fun downloadedSongIds(): Set<String>

    @Composable
    fun rememberSongDownloader(): (List<SongItem>) -> Unit

    @Composable
    fun rememberPlaylistAdder(): (List<SongItem>) -> Unit

    @Composable
    fun QueueMenu(
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
        onAltShare: (() -> Unit)? = null,
        onDownload: () -> Unit,
        onNavigateToListenTogether: () -> Unit,
    )

    fun prepareListenTogetherQueueImport(
        title: String,
        songs: List<SongItem>,
    )
}

val LocalPlayerPlatform = staticCompositionLocalOf<PlayerPlatform> { error("No PlayerPlatform provided") }

@Composable
internal fun AnimatedCoverVideo(
    url: String,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    playbackResource: PlaybackResource? = null,
    cacheKey: String? = null,
    videoBytes: ByteArray? = null,
    clips: List<YouTubeCanvasClip> = emptyList(),
    playing: Boolean = true,
    onError: (() -> Unit)? = null,
    onReadyChanged: ((Boolean) -> Unit)? = null,
    onFrame: ((ImageBitmap?) -> Unit)? = null,
) {
    LocalPlayerPlatform.current.AnimatedCoverVideo(
        url = url,
        headers = headers,
        playbackResource = playbackResource,
        cacheKey = cacheKey,
        videoBytes = videoBytes,
        clips = clips,
        playing = playing && LocalPlayerContentActive.current,
        onError = onError,
        onReadyChanged = onReadyChanged,
        onFrame = onFrame,
        modifier = modifier,
    )
}
