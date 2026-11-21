package com.metrolist.desktop.state.playback

import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.cast.dlnaVolumePercent

internal class DesktopSleepTimerCastVolume {
    private var deviceId: String? = null
    private var requested: Float? = null
    private var gain = 1f

    fun requestedVolume(state: CastState): Float {
        val original = requested?.takeIf { deviceId == state.device?.id } ?: return state.volume
        if (gain == 1f) {
            val expectedVolume =
                when (state.device?.protocol) {
                    CastProtocol.DLNA, CastProtocol.SONOS -> dlnaVolumePercent(original) / 100f
                    else -> original
                }
            if (state.volume == expectedVolume) {
                requested = null
                deviceId = null
                return state.volume
            }
        }
        return original
    }

    fun setRequestedVolume(volume: Float): Float {
        if (requested != null) requested = volume
        return volume * gain
    }

    fun setGain(
        state: CastState,
        value: Float,
    ): Float? {
        if (!state.isConnected) {
            deviceId = null
            requested = null
            gain = 1f
            return null
        }
        if (deviceId != state.device?.id) {
            requested = null
            deviceId = state.device?.id
        }
        if (value == 1f && requested == null) return null
        if (requested == null) requested = state.volume
        gain = value
        return requested!! * gain
    }
}
