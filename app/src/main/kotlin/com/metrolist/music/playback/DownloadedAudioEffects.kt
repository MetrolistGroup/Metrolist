package com.metrolist.music.playback

import android.content.Context
import android.media.audiofx.PresetReverb
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.constants.DownloadPitchKey
import com.metrolist.music.constants.DownloadReverbKey
import com.metrolist.music.constants.DownloadSpeedKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

internal class DownloadedAudioEffects(
    context: Context,
    private val player: ExoPlayer,
    scope: CoroutineScope,
) : Player.Listener {
    private var parameters = PlaybackParameters.DEFAULT
    private var reverbLevel = 0
    private var reverb: PresetReverb? = null
    private val job = scope.launch {
        context.dataStore.data.collect { prefs ->
            val speed = (prefs[DownloadSpeedKey] ?: 1f).coerceIn(0.25f, 2f)
            parameters = PlaybackParameters(speed, if ((prefs[DownloadPitchKey] ?: 0) != 0) speed else 1f)
            reverbLevel = if ((prefs[DownloadReverbKey] ?: 0) != 0) 10 else 0
            apply()
        }
    }

    init {
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        apply()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        apply()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (reverbLevel > 0) {
            player.applicationLooper.let { looper ->
                android.os.Handler(looper).post { if (!released) applyReverb() }
            }
        }
    }

    private var released = false

    private fun apply() {
        player.playbackParameters = parameters
        applyReverb()
    }

    private fun applyReverb() {
        if (reverbLevel > 0) {
            try {
                val effect = reverb ?: PresetReverb(0, 0).also {
                    reverb = it
                    it.preset = PresetReverb.PRESET_MEDIUMROOM
                    it.enabled = true
                }
                player.setAuxEffectInfo(AuxEffectInfo(effect.id, reverbLevel / 10f))
            } catch (e: RuntimeException) {
                Timber.w(e, "Reverb is unavailable on this device")
                clearReverb()
            }
        } else {
            clearReverb()
        }
    }

    private fun clearReverb() {
        player.setAuxEffectInfo(AuxEffectInfo(0, 0f))
        reverb?.release()
        reverb = null
    }

    fun release() {
        released = true
        job.cancel()
        player.removeListener(this)
        clearReverb()
    }
}
