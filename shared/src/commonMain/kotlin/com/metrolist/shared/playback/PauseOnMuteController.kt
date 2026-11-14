package com.metrolist.shared.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PauseOnMuteController {
    private var wasMuted: Boolean? = null
    private val _isPausedByMute = MutableStateFlow(false)
    val isPausedByMute = _isPausedByMute.asStateFlow()

    fun reset(muted: Boolean? = null) {
        wasMuted = muted
        cancelResume()
    }

    fun cancelResume() {
        _isPausedByMute.value = false
    }

    fun onVolumeChanged(
        muted: Boolean,
        enabled: Boolean,
        playing: Boolean,
        pause: () -> Unit,
        resume: () -> Unit,
    ) {
        val previouslyMuted = wasMuted
        wasMuted = muted
        if (!enabled || playing) cancelResume()
        if (!enabled || previouslyMuted == null || previouslyMuted == muted) return

        if (muted && playing) {
            pause()
            _isPausedByMute.value = true
        } else if (!muted && _isPausedByMute.value) {
            cancelResume()
            resume()
        }
    }
}
