package com.metrolist.shared.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.animatedArtworkSource
import com.metrolist.shared.api.artwork.animatedCoverCacheQuality
import com.metrolist.shared.api.artwork.selectAnimatedCoverUrl
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.PlayerViewModel.MusicVideoCanvas
import com.metrolist.shared.viewmodels.PlayerViewModel.SongUiModel
import kotlinx.coroutines.delay

internal const val CANVAS_CROSSFADE_DURATION_MS = 300

internal data class CanvasLayer(
    val songId: String,
    val url: String,
    val cacheKey: String?,
    val video: MusicVideoCanvas?,
    val source: CanvasContentSource,
    val tall: Boolean = false,
) {
    val key get() = (if (source == CanvasContentSource.APPLE_MUSIC) source.name else songId) to url
}

internal class CanvasLayers {
    var ready by mutableStateOf(emptySet<Pair<String, String>>())
    var failed by mutableStateOf(emptySet<Pair<String, String>>())
    private var active by mutableStateOf<CanvasLayer?>(null)
    private var outgoing by mutableStateOf<CanvasLayer?>(null)

    val isVisible: Boolean
        get() = active?.key in ready || outgoing?.key in ready

    fun hasPresented(layer: CanvasLayer?): Boolean =
        layer != null && layer.key !in failed && (layer.key in ready || active?.key == layer.key)

    fun shouldRender(
        current: CanvasLayer?,
        contentState: PlayerContentState,
    ): Boolean = contentState.isSettled || (contentState.isVisible && hasPresented(current))

    fun updateCurrent(current: CanvasLayer?) {
        if (active?.key != current?.key) {
            if (current == null || current.key in ready) {
                outgoing = active?.takeIf { it.key in ready }
                active = current
            } else if (active?.key?.first != current.key.first) {
                active = null
            }
        }
    }

    fun select(
        song: SongUiModel?,
        platform: PlayerPlatform,
        failedUrls: Set<String> = emptySet(),
        preferTall: Boolean = false,
    ): CanvasLayer? {
        song ?: return null
        val owners = setOf(song.id, CanvasContentSource.APPLE_MUSIC.name)
        val allFailedUrls = failedUrls + failed.filter { it.first in owners }.map { it.second }
        val appleUrl =
            song.appleMusicTallAnimatedCoverUrl?.takeIf { preferTall && it !in allFailedUrls } ?: song.appleMusicAnimatedCoverUrl
        val url =
            selectAnimatedCoverUrl(
                canvasUrl = song.spotifyAnimatedCoverUrl,
                musicVideoUrl = song.musicVideoCanvas?.url,
                animatedCoverUrl = appleUrl,
                canvasState = song.canvasState,
                pendingSources = song.pendingCanvasSources,
                canvasEnabled = Settings.shared.isCanvasEnabled,
                animatedCoversEnabled = Settings.shared.enableAnimatedCovers,
                failedUrls = allFailedUrls,
                contentOrder = CanvasContentSource.parseOrder(Settings.shared.canvasContentOrder),
                enabledSources =
                    CanvasContentSource.parseEnabled(Settings.shared.canvasEnabledSources).let {
                        if (Settings.shared.musicVideoPlaybackEnabled) it else it - CanvasContentSource.YOUTUBE_MUSIC_VIDEO
                    },
            ) ?: return null
        val video = song.musicVideoCanvas?.takeIf { it.url == url }
        val cacheKey =
            when (url) {
                video?.url -> {
                    video.cacheKey
                }

                song.spotifyAnimatedCoverUrl -> {
                    platform.canvasCacheKey(song.id, CanvasContentSource.SPOTIFY_CANVAS, "")
                }

                else -> {
                    platform.canvasCacheKey(
                        song.albumId ?: song.id,
                        CanvasContentSource.APPLE_MUSIC,
                        animatedCoverCacheQuality(Settings.shared.animatedCoverResolution, url == song.appleMusicTallAnimatedCoverUrl),
                    )
                }
            }
        val source = animatedArtworkSource(url, song.spotifyAnimatedCoverUrl, song.musicVideoCanvas?.url)
        return CanvasLayer(song.id, url, cacheKey, video, source, tall = url == song.appleMusicTallAnimatedCoverUrl)
    }

    @Composable
    fun Render(
        current: CanvasLayer?,
        playing: Boolean,
        modifier: Modifier = Modifier,
        onFrame: ((ImageBitmap?) -> Unit)? = null,
        onError: (CanvasLayer) -> Unit = {},
    ) {
        updateCurrent(current)
        val fading = outgoing
        val retained = active?.takeUnless { it.key == current?.key || it.key == fading?.key }
        LaunchedEffect(fading?.key) {
            delay(CANVAS_CROSSFADE_DURATION_MS.toLong())
            outgoing = null
        }
        Box(modifier) {
            listOfNotNull(
                current,
                retained,
                fading,
            ).distinctBy { it.key }.forEach { layer ->
                key(layer.key) {
                    DisposableEffect(Unit) { onDispose { ready = ready - layer.key } }
                    val fade by animateFloatAsState(
                        if (layer.key in ready && (layer.key == current?.key || layer.key == retained?.key)) 1f else 0f,
                        tween(CANVAS_CROSSFADE_DURATION_MS),
                    )
                    AnimatedCoverVideo(
                        url = layer.url,
                        cacheKey = layer.cacheKey,
                        videoBytes = layer.video?.videoBytes,
                        clips = layer.video?.clips.orEmpty(),
                        playbackResource = layer.video?.resource,
                        headers =
                            layer.video
                                ?.resource
                                ?.headers
                                .orEmpty(),
                        playing = playing,
                        onFrame = if (layer.key == current?.key) onFrame else null,
                        onReadyChanged = {
                            ready = if (it) ready + layer.key else ready - layer.key
                            if (!it && layer.key == current?.key) onFrame?.invoke(null)
                        },
                        onError = {
                            failed = failed + layer.key
                            if (layer.key == current?.key) onFrame?.invoke(null)
                            onError(layer)
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade },
                    )
                }
            }
        }
    }
}
