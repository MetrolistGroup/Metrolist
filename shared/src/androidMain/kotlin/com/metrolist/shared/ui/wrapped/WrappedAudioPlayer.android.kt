package com.metrolist.shared.ui.wrapped

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import android.media.AudioAttributes as SystemAudioAttributes

internal actual fun createWrappedAudioLibrary(onInterrupted: () -> Unit): WrappedAudioLibrary {
    val context = AndroidContextProvider.applicationContext
    val directory =
        File.createTempFile("wrapped-", "", context.cacheDir).apply {
            delete()
            check(mkdir())
        }
    return try {
        AndroidWrappedAudioLibrary(context, directory, onInterrupted)
    } catch (error: Throwable) {
        directory.deleteRecursively()
        throw error
    }
}

@Suppress("DEPRECATION")
private class AndroidWrappedAudioLibrary(
    private val context: Context,
    directory: File,
    onInterrupted: () -> Unit,
) : JvmWrappedAudioLibrary(directory) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var closed = false
    private var focused = false
    private val handler = Handler(Looper.getMainLooper())
    private val listener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) handler.post { if (!closed) onInterrupted() }
        }
    private val request =
        if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    SystemAudioAttributes
                        .Builder()
                        .setUsage(SystemAudioAttributes.USAGE_MEDIA)
                        .setContentType(SystemAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener(listener, handler)
                .build()
        } else {
            null
        }
    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (!closed && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onInterrupted()
            }
        }

    init {
        // Unplugged headphones cancel the session even while the opening song downloads.
        ContextCompat.registerReceiver(
            context,
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // Focus waits until regular playback is saved and paused; taking it earlier would pause that playback unrecorded.
    override fun activate() {
        if (closed || focused) return
        val granted =
            if (Build.VERSION.SDK_INT >= 26) {
                audio.requestAudioFocus(checkNotNull(request))
            } else {
                audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        check(granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focused = true
    }

    override fun openLocalAudio(url: String): InputStream {
        val uri = Uri.parse(url)
        return if (uri.scheme == "content") checkNotNull(context.contentResolver.openInputStream(uri)) else super.openLocalAudio(url)
    }

    override fun close() {
        if (closed) return
        closed = true
        context.unregisterReceiver(noisy)
        if (focused) {
            if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(checkNotNull(request)) else audio.abandonAudioFocus(listener)
        }
        super.close()
    }
}

@UnstableApi
internal actual fun createWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    volume: Float,
): WrappedAudioPlayer = AndroidWrappedAudioPlayer(onEnded, onFailure, volume)

@UnstableApi
private class AndroidWrappedAudioPlayer(
    private val onEnded: () -> Unit,
    private val onFailure: () -> Unit,
    initialVolume: Float,
) : WrappedAudioPlayer {
    // The library owns focus once for both crossfading voices.
    private val player =
        ExoPlayer.Builder(AndroidContextProvider.applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            volume = initialVolume
        }
    private var closed = false

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override suspend fun prepare(path: String) {
        check(!closed)
        val ready = CompletableDeferred<Unit>()
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (closed) return
                    if (playbackState == Player.STATE_READY) ready.complete(Unit)
                    if (playbackState == Player.STATE_ENDED) onEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!closed && !ready.completeExceptionally(error)) onFailure()
                }
            },
        )
        player.setMediaItem(MediaItem.fromUri(File(path).toURI().toString()))
        player.prepare()
        checkNotNull(withTimeoutOrNull(15_000) { ready.await() })
    }

    override fun play() {
        check(!closed)
        player.play()
    }

    override fun close() {
        if (closed) return
        closed = true
        player.release()
    }
}
