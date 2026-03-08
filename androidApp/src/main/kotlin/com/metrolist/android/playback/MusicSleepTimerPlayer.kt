package com.metrolist.android.playback

import com.metrolist.shared.playback.SleepTimerPlayer
import kotlinx.coroutines.flow.combine

internal class MusicSleepTimerPlayer(
    private val player: AndroidPlaybackPlayer,
    private val pausePlayback: () -> Unit,
    private val cancelCrossfade: () -> Unit,
) : SleepTimerPlayer {
    override val isAvailable: Boolean
        get() = true
    override val currentPositionMs: Long
        get() = player.currentPosition.value
    override val durationMs: Long
        get() = player.duration.value
    override val playbackProgress = combine(player.currentPosition, player.duration) { position, duration -> position to duration }
    override var pauseAtEndOfMediaItems: Boolean
        get() = false
        set(value) {}

    override fun pause() = pausePlayback()

    override fun cancelCrossfadeTransition() = cancelCrossfade()

    override fun setSleepTimerGain(gain: Float) = player.setSleepTimerGain(gain)
}
