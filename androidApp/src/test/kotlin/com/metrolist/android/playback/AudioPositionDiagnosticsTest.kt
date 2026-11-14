package com.metrolist.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPositionDiagnosticsTest {
    @Test
    fun pcmFallbackExcludesTheBufferAndRetainsPlausibleBluetoothLatency() {
        assertEquals(200_000L, pcmFallbackLatencyUs(450, 250_000L))
        assertEquals(0L, pcmFallbackLatencyUs(200, 250_000L))
        assertEquals(0L, pcmFallbackLatencyUs(10_251, 250_000L))
    }

    @Test
    fun missingLatencyAndCompressedOutputsHaveNoPcmLatencyEstimate() {
        assertNull(pcmFallbackLatencyUs(null, 250_000L))
        assertNull(pcmFallbackLatencyUs(450, null))
    }
}
