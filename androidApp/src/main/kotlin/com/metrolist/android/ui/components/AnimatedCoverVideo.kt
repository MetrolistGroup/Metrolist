package com.metrolist.android.ui.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import com.metrolist.android.playback.buildVideoDataSourceFactory
import com.metrolist.android.playback.buildVideoMediaSource
import com.metrolist.android.playback.createArtworkVideoLoadControl
import com.metrolist.android.ui.player.CanvasCache
import com.metrolist.android.ui.player.canvasCacheIdentity
import com.metrolist.android.ui.player.prepareCanvasClips
import com.metrolist.shared.api.artwork.CanvasPlaybackWatchdog
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.api.artwork.runYouTubeCanvasPlaybackClock
import com.metrolist.shared.api.artwork.youtubeCanvasSeekPrerollMs
import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.YouTubeCanvasMode
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.youtube.logging.YtLogger
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private const val TAG = "ArtworkVideo"

@OptIn(UnstableApi::class)
private fun buildCanvasMediaSource(
    context: Context,
    url: String,
    playbackResource: PlaybackResource?,
    mediaSourceFactory: DefaultMediaSourceFactory,
    openSources: MutableList<SeekableMediaSource>,
    cacheKey: String?,
    clips: List<YouTubeCanvasClip>,
): MediaSource =
    if (isPreparedCanvasUrl(url)) {
        DefaultMediaSourceFactory(context)
            .setDataSourceFactory(FileDataSource.Factory())
            .createMediaSource(MediaItem.fromUri(url))
    } else if (playbackResource != null && !CanvasCache.isCachedUrl(url)) {
        checkNotNull(
            buildVideoMediaSource(
                context,
                playbackResource,
                GlobalYouTube.client,
                onVideoSourceOpened = { openSources += it },
                sabrSeekPrerollMs = youtubeCanvasSeekPrerollMs(clips, false) ?: SabrInputStream.DEFAULT_SEEK_PREROLL_MS,
                cacheDataSource =
                    cacheKey?.let { key ->
                        { upstream ->
                            CanvasCache.get(context).factory(
                                key = key,
                                upstream = upstream,
                                identity = playbackResource.canvasCacheIdentity(),
                                mimeType = playbackResource.videoMimeType,
                                durationMs = playbackResource.durationSeconds?.times(1000),
                            )
                        }
                    },
            ),
        )
    } else {
        mediaSourceFactory.createMediaSource(MediaItem.fromUri(url))
    }

@OptIn(UnstableApi::class)
@Composable
fun AnimatedCoverVideo(
    url: String,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    playbackResource: PlaybackResource? = null,
    onError: (() -> Unit)? = null,
    onReadyChanged: ((Boolean) -> Unit)? = null,
    cacheKey: String? = null,
    playing: Boolean = true,
    clips: List<YouTubeCanvasClip> = emptyList(),
    dataSourceFactory: DataSource.Factory? = null,
    onFrame: ((ImageBitmap?) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val currentOnError = rememberUpdatedState(onError)
    val currentOnReadyChanged = rememberUpdatedState(onReadyChanged)
    val currentOnFrame = rememberUpdatedState(onFrame)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val session = remember(url, headers, playbackResource, cacheKey, dataSourceFactory, clips, active) { ArtworkVideoSession() }
    val frameSampler = remember(session) { CanvasFrameSampler() }
    var hasRenderedFrame by remember(session) { mutableStateOf(false) }
    var videoAspectRatio by remember(session) { mutableFloatStateOf(1f) }

    LaunchedEffect(session) {
        if (!active) return@LaunchedEffect
        val prepareShortClips =
            playbackResource != null && Settings.shared.youtubeCanvasMode == YouTubeCanvasMode.SHORT_CLIPS &&
                !CanvasCache.isCachedUrl(url)
        session.prepare(
            context,
            url,
            headers,
            playbackResource,
            cacheKey,
            clips,
            dataSourceFactory,
            preparingClips = prepareShortClips,
            onReady = {
                hasRenderedFrame = true
                currentOnReadyChanged.value?.invoke(true)
            },
            onAspectRatio = { videoAspectRatio = it },
            onError = {
                hasRenderedFrame = false
                currentOnFrame.value?.invoke(null)
                currentOnReadyChanged.value?.invoke(false)
                currentOnError.value?.invoke()
            },
        )
        var temporaryFile: File? = null
        try {
            if (prepareShortClips) {
                try {
                    withContext(Dispatchers.IO) {
                        val sources = CopyOnWriteArrayList<SeekableMediaSource>()
                        try {
                            val factory =
                                checkNotNull(
                                    buildVideoDataSourceFactory(
                                        context,
                                        playbackResource,
                                        GlobalYouTube.client,
                                        onVideoSourceOpened = sources::add,
                                        sabrSeekPrerollMs = checkNotNull(youtubeCanvasSeekPrerollMs(clips, true)),
                                        deferStart = true,
                                    ),
                                )
                            temporaryFile =
                                prepareCanvasClips(
                                    factory = factory,
                                    uri =
                                        if (playbackResource.hasSabrVideo) {
                                            "metrolist://sabr-video/${playbackResource.videoId}"
                                        } else {
                                            checkNotNull(
                                                playbackResource.videoUrl,
                                            )
                                        },
                                    clips = clips,
                                    directory = context.cacheDir,
                                    onSeek = { position -> sources.forEach { it.updatePlaybackPosition(position) } },
                                ).temporaryFile
                        } finally {
                            sources.forEach(SeekableMediaSource::close)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    YtLogger.e(TAG, "Canvas clip preparation failed", error)
                }
            }
            if (prepareShortClips) {
                session.usePreparedClips(temporaryFile) {
                    hasRenderedFrame = false
                    frameSampler.reset()
                    currentOnFrame.value?.invoke(null)
                    currentOnReadyChanged.value?.invoke(false)
                }
                temporaryFile = null
            }
        } finally {
            temporaryFile?.delete()
        }
    }
    DisposableEffect(session) {
        onDispose {
            hasRenderedFrame = false
            currentOnReadyChanged.value?.invoke(false)
            currentOnFrame.value?.invoke(null)
            session.release()
        }
    }
    LaunchedEffect(session, playing) { session.setPlaying(active && playing) }
    key(session) {
        AndroidView(
            factory = { viewContext ->
                AspectRatioFrameLayout(viewContext).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    addView(
                        TextureView(viewContext).apply {
                            isOpaque = false
                            var currentZoom = 1f
                            surfaceTextureListener =
                                object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        currentZoom = 1f
                                        session.attachSurface(Surface(texture))
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        texture: SurfaceTexture,
                                        width: Int,
                                        height: Int,
                                    ) = Unit

                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                        session.onFrameRendered()
                                        val callback = currentOnFrame.value ?: return
                                        if (!active || !hasRenderedFrame) return
                                        val (sampleWidth, sampleHeight) =
                                            frameSampler.sampleSize(SystemClock.elapsedRealtime(), width, height) ?: return
                                        val bitmap = runCatching { getBitmap(sampleWidth, sampleHeight) }.getOrNull() ?: return
                                        val targetZoom = frameSampler.detectLetterboxZoom(bitmap)
                                        if (abs(targetZoom - currentZoom) > 0.03f) {
                                            currentZoom = targetZoom
                                            val matrix = Matrix()
                                            matrix.setScale(currentZoom, currentZoom, width / 2f, height / 2f)
                                            setTransform(matrix)
                                        }
                                        callback(bitmap.asImageBitmap())
                                    }

                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        currentZoom = 1f
                                        frameSampler.reset()
                                        currentOnFrame.value?.invoke(null)
                                        // Keep the texture alive until the decoder has detached on its own looper.
                                        session.detachSurface(texture)
                                        return false
                                    }
                                }
                        },
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        ),
                    )
                }
            },
            update = { it.setAspectRatio(videoAspectRatio) },
            modifier = modifier.clipToBounds().graphicsLayer { alpha = if (hasRenderedFrame) 1f else 0f },
        )
    }
}

private object ArtworkVideoThread {
    val handler = Handler(HandlerThread("ArtworkVideo").apply { start() }.looper)
    val dispatcher = handler.asCoroutineDispatcher()
}

@OptIn(UnstableApi::class)
private class ArtworkVideoSession {
    private val handler = ArtworkVideoThread.handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + ArtworkVideoThread.dispatcher)
    private val openSources = CopyOnWriteArrayList<SeekableMediaSource>()
    private val renderedFrames = AtomicLong()
    private val watchdog = CanvasPlaybackWatchdog()
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private val surfaceAttached = MutableStateFlow(false)
    private var playing = false
    private var released = false
    private var temporaryFile: File? = null
    private var recovery: CanvasPlaybackRecovery? = null

    @Volatile
    private var disposed = false

    fun prepare(
        context: Context,
        url: String,
        headers: Map<String, String>,
        playbackResource: PlaybackResource?,
        cacheKey: String?,
        clips: List<YouTubeCanvasClip>,
        dataSourceFactory: DataSource.Factory?,
        preparingClips: Boolean,
        onReady: () -> Unit,
        onAspectRatio: (Float) -> Unit,
        onError: () -> Unit,
    ) {
        fun notify(action: () -> Unit) {
            mainHandler.post { if (!disposed) action() }
        }
        handler.post {
            if (disposed || released) {
                return@post
            }
            try {
                val factory =
                    DefaultMediaSourceFactory(context).setDataSourceFactory(
                        when {
                            dataSourceFactory != null -> {
                                dataSourceFactory
                            }

                            CanvasCache.isCachedUrl(url) -> {
                                CanvasCache.get(context).factory(url, null)
                            }

                            cacheKey != null && playbackResource == null -> {
                                CanvasCache.get(context).factory(
                                    cacheKey,
                                    DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers),
                                    identity = url,
                                )
                            }

                            else -> {
                                CanvasCache.get(context).streamingFactory(
                                    DefaultHttpDataSource
                                        .Factory()
                                        .setConnectTimeoutMs(30_000)
                                        .setReadTimeoutMs(60_000)
                                        .setDefaultRequestProperties(headers),
                                )
                            }
                        },
                    )
                val selector =
                    DefaultTrackSelector(context).apply {
                        parameters =
                            buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                                .build()
                    }
                val videoPlayer =
                    ExoPlayer
                        .Builder(context)
                        .setLooper(handler.looper)
                        .setTrackSelector(selector)
                        .setLoadControl(createArtworkVideoLoadControl())
                        .setMediaSourceFactory(factory)
                        .build()
                player = videoPlayer
                val sources =
                    canvasPlaybackSources(
                        url,
                        playbackResource,
                        cacheKey,
                        clips,
                        cachedAvailable = !CanvasCache.isCachedUrl(url) || CanvasCache.get(context).find(url) != null,
                        preparingClips = preparingClips,
                    )
                val clockPlaying = MutableStateFlow(videoPlayer.playWhenReady)
                var clockJob: Job? = null
                var watchdogJob: Job? = null

                fun prepareSource(source: CanvasPlaybackSource) {
                    clockJob?.cancel()
                    watchdogJob?.cancel()
                    watchdog.reset()
                    openSources.forEach(SeekableMediaSource::close)
                    openSources.clear()
                    videoPlayer.setMediaSource(
                        buildCanvasMediaSource(context, source.url, playbackResource, factory, openSources, source.cacheKey, source.clips),
                    )
                    videoPlayer.prepare()
                    watchdogJob =
                        scope.launch {
                            watchdog.observe(
                                active = combine(clockPlaying, surfaceAttached) { requested, attached -> requested && attached },
                                timeMs = SystemClock::elapsedRealtime,
                                renderedFrames = renderedFrames::get,
                                onStalled = {
                                    YtLogger.w(TAG, "Artwork video stopped rendering frames; falling back")
                                    recovery?.onPlaybackError()
                                },
                            )
                        }
                    if (!CanvasCache.isCachedUrl(source.url) && !isPreparedCanvasUrl(source.url) &&
                        (playbackResource?.hasSabrVideo == true || source.clips.isNotEmpty())
                    ) {
                        clockJob =
                            scope.launch {
                                runYouTubeCanvasPlaybackClock(
                                    clips = source.clips,
                                    currentPositionMs = { videoPlayer.currentPosition },
                                    isPlaying = { videoPlayer.isPlaying },
                                    playWhenReady = clockPlaying,
                                    isEnded = { videoPlayer.playbackState == Player.STATE_ENDED },
                                    updatePlaybackPosition = { position -> openSources.forEach { it.updatePlaybackPosition(position) } },
                                    seekTo = videoPlayer::seekTo,
                                )
                            }
                    }
                }
                val playbackRecovery =
                    CanvasPlaybackRecovery(
                        initialSource = sources.first,
                        fallback = sources.second,
                        preparingClips = preparingClips,
                        prepareSource = { source ->
                            try {
                                prepareSource(source)
                                true
                            } catch (error: Exception) {
                                YtLogger.e(TAG, "Artwork video source preparation failed", error)
                                false
                            }
                        },
                        stopPlayback = {
                            clockJob?.cancel()
                            watchdogJob?.cancel()
                            videoPlayer.stop()
                            videoPlayer.clearMediaItems()
                            openSources.forEach(SeekableMediaSource::close)
                            openSources.clear()
                        },
                        onError = {
                            releasePlayer()
                            notify(onError)
                        },
                    )
                recovery = playbackRecovery
                videoPlayer.addListener(
                    object : Player.Listener {
                        override fun onPlayWhenReadyChanged(
                            playWhenReady: Boolean,
                            reason: Int,
                        ) {
                            clockPlaying.value = playWhenReady
                        }

                        override fun onRenderedFirstFrame() {
                            if (playbackRecovery.isPlaybackActive) notify(onReady)
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.height >
                                0
                            ) {
                                notify { onAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height) }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            YtLogger.e(TAG, "Artwork video playback failed: ${error.errorCodeName}", error)
                            playbackRecovery.onPlaybackError()
                        }
                    },
                )
                videoPlayer.volume = 0f
                videoPlayer.repeatMode = Player.REPEAT_MODE_ALL
                videoPlayer.setVideoSurface(surface)
                videoPlayer.playWhenReady = playing
                playbackRecovery.start()
            } catch (error: Exception) {
                YtLogger.e(TAG, "Artwork video preparation failed", error)
                releasePlayer()
                notify(onError)
            }
        }
    }

    fun usePreparedClips(
        file: File?,
        onPreparing: () -> Unit,
    ) {
        handler.post {
            val playbackRecovery = recovery
            if (disposed || released || playbackRecovery == null || playbackRecovery.failed) {
                file?.delete()
                return@post
            }
            temporaryFile = file
            val prepared =
                file?.let {
                    mainHandler.post { if (!disposed) onPreparing() }
                    CanvasPlaybackSource(Uri.fromFile(it).toString(), null, emptyList())
                }
            playbackRecovery.onPreparationFinished(prepared)
        }
    }

    fun onFrameRendered() {
        renderedFrames.incrementAndGet()
    }

    fun setPlaying(value: Boolean) {
        handler.post {
            if (playing != value) watchdog.reset()
            playing = value
            player?.playWhenReady = value
        }
    }

    fun attachSurface(value: Surface) {
        handler.post {
            if (disposed || released) {
                value.release()
            } else {
                watchdog.reset()
                player?.setVideoSurface(value)
                surface?.release()
                surface = value
                surfaceAttached.value = true
            }
        }
    }

    fun detachSurface(texture: SurfaceTexture) {
        handler.post {
            watchdog.reset()
            player?.clearVideoSurface()
            surface?.release()
            surface = null
            surfaceAttached.value = false
            texture.release()
        }
    }

    fun release() {
        disposed = true
        handler.post {
            released = true
            releasePlayer()
            surface?.release()
            surface = null
        }
    }

    private fun releasePlayer() {
        recovery = null
        scope.cancel()
        player?.release()
        player = null
        openSources.forEach(SeekableMediaSource::close)
        openSources.clear()
        temporaryFile?.delete()
        temporaryFile = null
    }
}
