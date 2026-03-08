package com.metrolist.android.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber

internal class SystemAudioEffects(
    private val context: Context,
) {
    private val sessions = mutableMapOf<ExoPlayer, Session>()

    fun attach(player: ExoPlayer) {
        if (player in sessions) return
        val session = Session()
        sessions[player] = session
        player.addListener(session)
        session.onAudioSessionIdChanged(player.audioSessionId)
    }

    fun detach(player: ExoPlayer) {
        val session = sessions.remove(player) ?: return
        player.removeListener(session)
        session.onAudioSessionIdChanged(0)
    }

    private inner class Session : Player.Listener {
        var id = 0
            private set

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            val next = audioSessionId.coerceAtLeast(0)
            if (id == next) return
            val previous = id
            id = next
            if (previous > 0 && sessions.values.none { it.id == previous }) {
                broadcast(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, previous)
            }
            if (next > 0 && sessions.values.none { it !== this && it.id == next }) {
                broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, next)
            }
        }
    }

    private fun broadcast(
        action: String,
        sessionId: Int,
    ) {
        try {
            context.sendBroadcast(audioEffectIntent(action, context.packageName, sessionId))
        } catch (error: SecurityException) {
            Timber.w(error, "System audio effects session notification denied")
        }
    }
}

internal fun launchSystemEqualizer(
    context: Context,
    sessionId: Int,
    launch: (Intent) -> Unit,
): Boolean {
    if (sessionId <= 0) return false
    return try {
        launch(audioEffectIntent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, context.packageName, sessionId))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun audioEffectIntent(
    action: String,
    packageName: String,
    sessionId: Int,
): Intent =
    Intent(action)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
