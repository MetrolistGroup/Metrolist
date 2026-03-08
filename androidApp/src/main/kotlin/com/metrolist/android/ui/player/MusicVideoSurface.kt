@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.player

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.metrolist.android.R
import com.metrolist.android.extensions.findActivity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.enter_fullscreen
import com.metrolist.shared.generated.resources.enter_pip
import com.metrolist.shared.ui.LocalIsInPipMode
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource
import timber.log.Timber

private const val MUSIC_VIDEO_TAG = "MusicVideo"

@Composable
fun MusicVideoSurface(
    player: Player?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    fillContainer: Boolean = false,
    useSurfaceView: Boolean = false,
    showControls: Boolean = true,
    isPip: Boolean = false,
    onFullscreenClick: (() -> Unit)? = null,
    onPiPClick: (() -> Unit)? = null,
    onPlayerError: ((PlaybackException) -> Unit)? = null,
) {
    val isInPipMode = LocalIsInPipMode.current
    val isSurfaceActive = if (isPip) isInPipMode else !isInPipMode
    val effectivePlayer = player.takeIf { isSurfaceActive }

    var hasFrame by remember(effectivePlayer) {
        mutableStateOf(false)
    }
    var videoAspectRatio by remember(effectivePlayer) { mutableStateOf(effectivePlayer?.videoSize?.aspectRatioOrNull()) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val currentOnPlayerError by rememberUpdatedState(onPlayerError)
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val outlineProvider =
        remember(cornerRadius, density, useSurfaceView) {
            if (useSurfaceView) null else RoundedCornerOutlineProvider(with(density) { cornerRadius.toPx() })
        }
    val targetResizeMode =
        if (fillContainer) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    val isVideoReady = hasFrame
    val videoAlpha by animateFloatAsState(
        targetValue = if (isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "musicVideoFade",
    )

    DisposableEffect(effectivePlayer, lifecycleOwner) {
        Timber.tag(MUSIC_VIDEO_TAG).d(
            "surface effect start player=%s mediaId=%s lifecycle=%s isPip=%s",
            effectivePlayer.logId(),
            effectivePlayer?.currentMediaItem?.mediaId,
            lifecycleOwner.lifecycle.currentState,
            isPip,
        )
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "surface first frame player=%s mediaId=%s positionMs=%d view=%s isPip=%s",
                        effectivePlayer.logId(),
                        effectivePlayer?.currentMediaItem?.mediaId,
                        effectivePlayer?.currentPosition ?: 0L,
                        playerView.logId(),
                        isPip,
                    )
                    hasFrame = true
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "surface video size player=%s mediaId=%s width=%d height=%d pixelRatio=%s isPip=%s",
                        effectivePlayer.logId(),
                        effectivePlayer?.currentMediaItem?.mediaId,
                        videoSize.width,
                        videoSize.height,
                        videoSize.pixelWidthHeightRatio,
                        isPip,
                    )
                    videoAspectRatio = videoSize.aspectRatioOrNull()
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "surface media transition player=%s mediaId=%s reason=%d isPip=%s",
                        effectivePlayer.logId(),
                        mediaItem?.mediaId,
                        reason,
                        isPip,
                    )
                    hasFrame = false
                    videoAspectRatio = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.tag(MUSIC_VIDEO_TAG).e(
                        error,
                        "surface player error player=%s mediaId=%s code=%d positionMs=%d isPip=%s",
                        effectivePlayer.logId(),
                        effectivePlayer?.currentMediaItem?.mediaId,
                        error.errorCode,
                        effectivePlayer?.currentPosition ?: 0L,
                        isPip,
                    )
                    hasFrame = false
                    currentOnPlayerError?.invoke(error)
                }
            }
        val lifecycleObserver =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        Timber.tag(MUSIC_VIDEO_TAG).d(
                            "surface lifecycle attach player=%s view=%s event=%s isPip=%s",
                            effectivePlayer.logId(),
                            playerView.logId(),
                            event,
                            isPip,
                        )
                        playerView?.player = effectivePlayer
                    }

                    Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                        Timber.tag(MUSIC_VIDEO_TAG).d(
                            "surface lifecycle detach player=%s view=%s event=%s isPip=%s",
                            effectivePlayer.logId(),
                            playerView.logId(),
                            event,
                            isPip,
                        )
                        hasFrame = false
                        playerView?.player = null
                    }

                    else -> {}
                }
            }
        effectivePlayer?.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            Timber.tag(MUSIC_VIDEO_TAG).d(
                "surface effect dispose player=%s mediaId=%s view=%s isPip=%s",
                effectivePlayer.logId(),
                effectivePlayer?.currentMediaItem?.mediaId,
                playerView.logId(),
                isPip,
            )
            effectivePlayer?.removeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            playerView?.player = null
        }
    }

    if (effectivePlayer == null) return

    val shape = if (useSurfaceView) null else RoundedCornerShape(cornerRadius)
    val targetLayoutId =
        if (useSurfaceView) {
            R.layout.music_video_fullscreen_player_view
        } else {
            R.layout.music_video_player_view
        }

    Box(
        modifier =
            modifier
                .then(
                    if (!fillContainer && !useSurfaceView) {
                        Modifier.aspectRatio(musicVideoContainerAspectRatio(videoAspectRatio))
                    } else {
                        Modifier
                    },
                ).then(if (shape != null) Modifier.clip(shape) else Modifier)
                .background(Color.Black),
    ) {
        if (videoAlpha < 1f) {
            AsyncImage(
                url = artworkUrl,
                contentDescription = null,
                contentScale = if (fillContainer) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AndroidView(
            factory = { context ->
                LayoutInflater
                    .from(context)
                    .inflate(targetLayoutId, null, false)
                    .let { it as PlayerView }
                    .also {
                        playerView = it
                        Timber.tag(MUSIC_VIDEO_TAG).d(
                            "PlayerView created view=%s player=%s layout=%s surfaceType=%s fill=%s isPip=%s",
                            it.logId(),
                            effectivePlayer.logId(),
                            if (useSurfaceView) "fullscreen" else "embedded",
                            if (useSurfaceView) "surface" else "texture",
                            fillContainer,
                            isPip,
                        )
                    }
            },
            update = { view ->
                if (view.resizeMode != targetResizeMode) {
                    view.resizeMode = targetResizeMode
                }
                if (outlineProvider != null) {
                    if (view.outlineProvider !== outlineProvider) {
                        view.outlineProvider = outlineProvider
                        view.clipToOutline = true
                        view.invalidateOutline()
                    }
                } else if (view.clipToOutline) {
                    view.outlineProvider = null
                    view.clipToOutline = false
                }
                val visiblePlayer = effectivePlayer.takeIf { lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                if (view.player !== visiblePlayer) {
                    Timber.tag(MUSIC_VIDEO_TAG).d(
                        "PlayerView player changed view=%s oldPlayer=%s newPlayer=%s lifecycle=%s isPip=%s",
                        view.logId(),
                        view.player.logId(),
                        visiblePlayer.logId(),
                        lifecycleOwner.lifecycle.currentState,
                        isPip,
                    )
                    view.player = visiblePlayer
                }
            },
            onRelease = { view ->
                Timber.tag(MUSIC_VIDEO_TAG).d(
                    "PlayerView released view=%s player=%s mediaId=%s isPip=%s",
                    view.logId(),
                    effectivePlayer.logId(),
                    effectivePlayer.currentMediaItem?.mediaId,
                    isPip,
                )
                view.player = null
                if (playerView === view) playerView = null
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (shape != null) Modifier.clip(shape) else Modifier)
                    .alpha(videoAlpha),
        )

        if (showControls && isVideoReady) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onPiPClick != null) {
                    FilledTonalIconButton(
                        onClick = onPiPClick,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White,
                            ),
                        modifier = Modifier.size(40.dp, 32.dp),
                        shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.PictureInPicture,
                            contentDescription = stringResource(Res.string.enter_pip),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                if (onFullscreenClick != null) {
                    FilledTonalIconButton(
                        onClick = onFullscreenClick,
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White,
                            ),
                        modifier = Modifier.size(40.dp, 32.dp),
                        shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(16.dp)),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Default.Fullscreen,
                            contentDescription = stringResource(Res.string.enter_fullscreen),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

internal fun musicVideoContainerAspectRatio(videoAspectRatio: Float?): Float =
    videoAspectRatio?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_VIDEO_ASPECT_RATIO

private const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

private class RoundedCornerOutlineProvider(
    private val radiusPx: Float,
) : ViewOutlineProvider() {
    override fun getOutline(
        view: View,
        outline: Outline,
    ) {
        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
    }
}

private fun VideoSize.aspectRatioOrNull(): Float? {
    if (width <= 0 || height <= 0) return null
    return ((width * pixelWidthHeightRatio) / height).takeIf { it.isFinite() && it > 0f }
}

private fun Any?.logId(): String =
    this?.let { "${it::class.java.simpleName}@${Integer.toHexString(System.identityHashCode(it))}" } ?: "none"
