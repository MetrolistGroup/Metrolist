package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopSleepTimerCastVolume
import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSleepTimerCastVolumeTest {
    private val remote =
        CastState(
            status = CastConnectionStatus.CONNECTED,
            device = CastDevice("receiver", "Receiver", CastProtocol.FCAST, "localhost", 1),
            volume = 0.8f,
        )

    @Test
    fun feedbackDoesNotCompoundTheFadeAndCancellationRestoresUserVolume() {
        val volume = DesktopSleepTimerCastVolume()
        assertEquals(0.4f, volume.setGain(remote, 0.5f))
        assertEquals(0.8f, volume.requestedVolume(remote.copy(volume = 0.4f)))
        assertEquals(0.2f, volume.setGain(remote.copy(volume = 0.4f), 0.25f))
        assertEquals(0.1f, volume.setRequestedVolume(0.4f))
        assertEquals(0.4f, volume.setGain(remote.copy(volume = 0.1f), 1f))
        assertEquals(0.4f, volume.requestedVolume(remote.copy(volume = 0.4f)))
        assertNull(volume.setGain(remote.copy(volume = 0.4f), 1f))
    }

    @Test
    fun aDifferentReceiverUsesItsOwnVolume() {
        val volume = DesktopSleepTimerCastVolume()
        volume.setGain(remote, 0.5f)
        assertNull(volume.setGain(CastState(), 1f))
        val other = remote.copy(device = remote.device!!.copy(id = "other"), volume = 0.2f)
        assertEquals(0.1f, volume.setGain(other, 0.5f))
        assertEquals(0.2f, volume.setGain(other, 1f))
    }
}
