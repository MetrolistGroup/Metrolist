@file:OptIn(ExperimentalSabrApi::class)

package com.metrolist.shared.ui.wrapped

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import com.metrolist.shared.youtube.playback.sabrAudioPlaybackChunks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@UnstableApi
internal actual fun createWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
): WrappedAudioPlayer = AndroidWrappedAudioPlayer(onEnded, onFailure, onInterrupted)

@UnstableApi
private class AndroidWrappedAudioPlayer(
    private val onEnded: () -> Unit,
    private val onFailure: () -> Unit,
    private val onInterrupted: () -> Unit,
) : WrappedAudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var player: ExoPlayer? = null
    private var source: SeekableMediaSource? = null
    private var clockJob: Job? = null
    private var closed = false

    override suspend fun play(song: SongItem) {
        check(!closed)
        clearPlayer()
        val resolver = checkNotNull(GlobalYouTubeRepository.streamResolver)
        val quality = runCatching { AudioQuality.valueOf(Settings.shared.audioQuality) }.getOrDefault(AudioQuality.AUTO)
        val resource = resolver.resolve(song.id, song.toContentHints(wantVideo = false), quality, song)
        check(!closed)
        val context = AndroidContextProvider.applicationContext
        val mediaSource: SeekableMediaSource? =
            resource.sabrBootstrap?.let { bootstrap ->
                SabrInputStream(
                    mediaSize = bootstrap.contentLengthBytes ?: resource.contentLengthBytes,
                    expectedDurationMs = bootstrap.durationMs,
                    tempDirectory = context.cacheDir,
                    streamFactory = { initial, position ->
                        sabrAudioPlaybackChunks(
                            GlobalYouTube.client,
                            bootstrap,
                            position,
                            initial,
                            tokenSupplier = resource.sabrTokenSupplier,
                            onAttestationPending = resource.onSabrAttestationPending,
                        )
                    },
                )
            }
                ?: resource.contentLengthBytes
                    ?.takeIf {
                        it > 0L && resource.isDirectFile && resource.url.startsWith("https://")
                    }?.let { size ->
                        HttpRangeInputStream(
                            url = resource.url,
                            requestHeaders = resource.headers,
                            mediaSize = size,
                            chunkSize = resource.rangeChunkSizeBytes.coerceIn(65_536L, 1_048_576L).toInt(),
                            targetRequestDurationMs = 2_000L,
                        )
                    }
        source = mediaSource
        val dataSource =
            mediaSource?.let { source -> DataSource.Factory { WrappedMediaDataSource(source) } }
                ?: DefaultDataSource.Factory(
                    context,
                    DefaultHttpDataSource.Factory().setDefaultRequestProperties(resource.headers),
                )
        val ready = CompletableDeferred<Unit>()
        val current =
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (player !== this@apply) return
                            if (playbackState == Player.STATE_READY) ready.complete(Unit)
                            if (playbackState == Player.STATE_ENDED) onEnded()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (player !== this@apply) return
                            if (!ready.completeExceptionally(error)) onFailure()
                        }

                        override fun onPlayWhenReadyChanged(
                            playWhenReady: Boolean,
                            reason: Int,
                        ) {
                            if (player === this@apply && !playWhenReady &&
                                (
                                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                                )
                            ) {
                                onInterrupted()
                            }
                        }

                        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                            if (player === this@apply &&
                                playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                            ) {
                                onInterrupted()
                            }
                        }
                    },
                )
            }
        player = current
        val item =
            MediaItem
                .Builder()
                .setUri(resource.url)
                .setMimeType(resource.mimeType)
                .build()
        current.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
        current.prepare()
        current.play()
        clockJob =
            mediaSource?.let { input ->
                scope.launch {
                    while (isActive && player === current) {
                        input.updatePlaybackPosition(current.currentPosition)
                        delay(250)
                    }
                }
            }
        check(
            withTimeoutOrNull(45_000) {
                ready.await()
                true
            } == true,
        )
    }

    private fun clearPlayer() {
        clockJob?.cancel()
        clockJob = null
        val previous = player
        player = null
        previous?.release()
        source?.close()
        source = null
    }

    override fun close() {
        closed = true
        clearPlayer()
        scope.cancel()
    }
}

@UnstableApi
private class WrappedMediaDataSource(
    private val source: SeekableMediaSource,
) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position
        source.seekToMediaPosition(position)
        val available = source.mediaSize?.minus(position)?.coerceAtLeast(0L)
        remaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                available?.let { minOf(it, dataSpec.length) } ?: dataSpec.length
            } else {
                available ?: C.LENGTH_UNSET.toLong()
            }
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val read = source.readAtMediaPosition(position, buffer, offset, requested)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
