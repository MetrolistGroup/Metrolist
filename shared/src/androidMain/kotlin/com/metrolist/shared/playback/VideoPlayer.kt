package com.metrolist.shared.playback

import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

actual typealias VideoFrame = Any

@UnstableApi
@Suppress("UNUSED_PARAMETER")
actual class VideoPlayer actual constructor(
    private val onFrame: (VideoFrame) -> Unit,
    private val onEnded: () -> Unit,
    private val onSeekLanded: ((Long) -> Unit)?,
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onRenderedFirstFrame: () -> Unit,
    private val looping: Boolean,
) {
    private val context = AndroidContextProvider.applicationContext
    private var exoPlayer: ExoPlayer? = null
    private var positionProvider: () -> Long = { 0L }
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var syncJob: Job? = null
    private var surface: Any? = null
    private var seekableSource: SeekableMediaSource? = null
    private var syncSuppressedUntilNanos = 0L

    actual val hasOwnAudio: Boolean = false

    actual var audioPtsProvider: (() -> Long?)? = null

    actual fun getStats(): VideoPlayerStats {
        val player = exoPlayer
        val format = player?.videoFormat
        return VideoPlayerStats(
            videoCodec = format?.sampleMimeType ?: "",
            videoResolution = if (format != null) "${format.width}x${format.height}" else "",
            fps = format?.frameRate?.toDouble() ?: 0.0,
            targetFps = format?.frameRate?.toDouble() ?: 0.0,
            videoBitrateKbps = (format?.bitrate?.toLong() ?: 0L) / 1000L,
            videoPtsMs = player?.currentPosition ?: 0L,
            audioClockMs = positionProvider(),
            volume = player?.volume ?: 0f,
            isPaused = player?.isPlaying == false,
        )
    }

    actual fun load(
        url: String,
        headers: Map<String, String>,
        positionProvider: () -> Long,
    ): Boolean =
        prepare(
            dataSourceFactory =
                DefaultHttpDataSource
                    .Factory()
                    .setDefaultRequestProperties(headers)
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true),
            mediaItem = MediaItem.fromUri(url),
            source = null,
            positionProvider = positionProvider,
        )

    actual fun load(
        source: SeekableMediaSource,
        positionProvider: () -> Long,
    ): Boolean =
        prepare(
            dataSourceFactory = DataSource.Factory { SeekableMediaDataSource(source) },
            mediaItem = MediaItem.fromUri(SABR_VIDEO_URI),
            source = source,
            positionProvider = positionProvider,
        )

    private fun prepare(
        dataSourceFactory: DataSource.Factory,
        mediaItem: MediaItem,
        source: SeekableMediaSource?,
        positionProvider: () -> Long,
    ): Boolean {
        this.positionProvider = positionProvider
        syncJob?.cancel()
        val previousSource = seekableSource
        seekableSource = null
        previousSource?.close()
        exoPlayer?.release()
        exoPlayer = null
        seekableSource = source

        return try {
            val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
            val renderersFactory =
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
            val trackSelector =
                DefaultTrackSelector(context).apply {
                    parameters =
                        buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                            .build()
                }
            val loadControl =
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(
                        20_000,
                        60_000,
                        2_000,
                        5_000,
                    ).setPrioritizeTimeOverSizeThresholds(true)
                    .setBackBuffer(
                        5_000,
                        true,
                    ).build()

            exoPlayer =
                ExoPlayer
                    .Builder(context, renderersFactory)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl)
                    .build()
                    .apply {
                        val initialPos = positionProvider().coerceAtLeast(0L)
                        setMediaItem(mediaItem, initialPos)
                        volume = 0f
                        repeatMode = if (looping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

                        addListener(
                            object : Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == Player.STATE_ENDED) onEnded()
                                    if (state == Player.STATE_READY) {
                                        syncSuppressedUntilNanos = System.nanoTime() + 200_000_000L
                                    }
                                }

                                override fun onPositionDiscontinuity(
                                    oldPosition: Player.PositionInfo,
                                    newPosition: Player.PositionInfo,
                                    reason: Int,
                                ) {
                                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                                        onSeekLanded?.invoke(exoPlayer?.currentPosition ?: 0L)
                                    }
                                }

                                override fun onVideoSizeChanged(videoSize: VideoSize) {
                                    this@VideoPlayer.onVideoSizeChanged(videoSize.width, videoSize.height)
                                }

                                override fun onRenderedFirstFrame() {
                                    val master = positionProvider().coerceAtLeast(0L)
                                    val current = exoPlayer?.currentPosition ?: 0L
                                    val drift = master - current
                                    if (abs(drift) > 400L) {
                                        exoPlayer?.seekTo(master)
                                        syncSuppressedUntilNanos = System.nanoTime() + 400_000_000L
                                    }
                                    this@VideoPlayer.onRenderedFirstFrame()
                                }
                            },
                        )

                        applySurface(this, this@VideoPlayer.surface)
                        prepare()
                    }
            true
        } catch (e: Exception) {
            source?.close()
            if (seekableSource === source) seekableSource = null
            false
        }
    }

    actual fun play(startPaused: Boolean) {
        exoPlayer?.let {
            it.playWhenReady = !startPaused
            if (startPaused) syncJob?.cancel() else startSync()
        }
    }

    private var isPausedByUser = false

    actual fun pause() {
        isPausedByUser = true
        exoPlayer?.pause()
        syncJob?.cancel()
    }

    actual fun resume() {
        isPausedByUser = false
        exoPlayer?.play()
        startSync()
    }

    actual fun seekTo(positionMs: Long) {
        seekableSource?.updatePlaybackPosition(positionMs)
        syncSuppressedUntilNanos = System.nanoTime() + 400_000_000L
        exoPlayer?.let {
            it.setPlaybackSpeed(1.0f)
            it.seekTo(positionMs)
        }
    }

    actual fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    actual fun setSurface(surface: Any?) {
        this.surface = surface
        exoPlayer?.let { applySurface(it, surface) }
    }

    private fun applySurface(
        player: Player,
        surface: Any?,
    ) {
        when (surface) {
            is Surface -> player.setVideoSurface(surface)
            is SurfaceHolder -> player.setVideoSurfaceHolder(surface)
            is SurfaceView -> player.setVideoSurfaceView(surface)
            is TextureView -> player.setVideoTextureView(surface)
            else -> player.setVideoSurface(null)
        }
    }

    actual fun close() {
        syncJob?.cancel()
        val activeSource = seekableSource
        seekableSource = null
        activeSource?.close()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun startSync() {
        syncJob?.cancel()
        syncJob =
            scope.launch {
                var currentAppliedSpeed = 1.0f
                while (isActive) {
                    val player = exoPlayer ?: break
                    if (!player.playWhenReady || player.playbackState == Player.STATE_IDLE ||
                        player.playbackState == Player.STATE_ENDED
                    ) {
                        break
                    }
                    val masterClock = positionProvider().coerceAtLeast(0L)
                    seekableSource?.updatePlaybackPosition(masterClock)
                    val nowNanos = System.nanoTime()

                    if (
                        player.playbackState != Player.STATE_READY ||
                        !player.isPlaying ||
                        nowNanos < syncSuppressedUntilNanos
                    ) {
                        if (!player.isPlaying && currentAppliedSpeed != 1.0f) {
                            currentAppliedSpeed = 1.0f
                            player.setPlaybackSpeed(1.0f)
                        }
                        delay(50.milliseconds)
                        continue
                    }

                    val videoClock = player.currentPosition.coerceAtLeast(0L)
                    val diff = masterClock - videoClock
                    val absDiff = abs(diff)

                    val targetSpeed =
                        when {
                            absDiff <= 15L -> {
                                1.0f
                            }

                            absDiff <= 80L -> {
                                val delta = (diff.toFloat() / 500f).coerceIn(-0.08f, 0.08f)
                                (1.0f + delta).let { (it * 100).roundToInt() / 100f }
                            }

                            absDiff <= 300L -> {
                                val delta = (diff.toFloat() / 600f).coerceIn(-0.25f, 0.25f)
                                (1.0f + delta).let { (it * 100).roundToInt() / 100f }
                            }

                            absDiff <= 1000L -> {
                                val delta = (diff.toFloat() / 800f).coerceIn(-0.50f, 0.50f)
                                (1.0f + delta).let { (it * 100).roundToInt() / 100f }
                            }

                            else -> {
                                player.seekTo(masterClock)
                                syncSuppressedUntilNanos = nowNanos + 500_000_000L
                                1.0f
                            }
                        }

                    if (abs(currentAppliedSpeed - targetSpeed) >= 0.02f) {
                        currentAppliedSpeed = targetSpeed
                        player.setPlaybackSpeed(targetSpeed)
                    }
                    delay(50.milliseconds)
                }
            }
    }

    private class SeekableMediaDataSource(
        private val source: SeekableMediaSource,
    ) : BaseDataSource(false) {
        private var opened = false
        private var uri = Uri.EMPTY
        private var readPosition = 0L
        private var bytesRemaining = C.LENGTH_UNSET.toLong()

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            source.seekToMediaPosition(dataSpec.position)
            readPosition = dataSpec.position
            val availableBytes = source.mediaSize?.minus(dataSpec.position)?.coerceAtLeast(0L)
            bytesRemaining =
                when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> {
                        availableBytes?.let { minOf(dataSpec.length, it) } ?: dataSpec.length
                    }

                    availableBytes != null -> {
                        availableBytes
                    }

                    else -> {
                        C.LENGTH_UNSET.toLong()
                    }
                }
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val requestedLength =
                if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                    length
                } else {
                    minOf(length.toLong(), bytesRemaining).toInt()
                }
            val count = source.readAtMediaPosition(readPosition, buffer, offset, requestedLength)
            if (count <= 0) return C.RESULT_END_OF_INPUT
            readPosition += count
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri = uri

        override fun close() {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        val SABR_VIDEO_URI: Uri = "sabr-video://stream".toUri()
    }
}
