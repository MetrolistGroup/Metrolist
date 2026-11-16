package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoDecoderConfigurationTest {
    @Test
    fun loopingVideoUsesSingleThreadedSoftwareDecoding() {
        val options = mutableMapOf<String, String>()
        val videoOptions = mutableMapOf<String, String>()

        val imageScalingFlags = applyVideoDecoderConfiguration(true, "8", options, videoOptions)

        assertEquals(null, options["hwaccel"])
        assertEquals("1", videoOptions["threads"])
        assertEquals("low_delay", videoOptions["flags"])
        assertEquals(1, imageScalingFlags)
    }

    @Test
    fun regularVideoKeepsHardwareAccelerationAndOptimalThreads() {
        val options = mutableMapOf<String, String>()
        val videoOptions = mutableMapOf<String, String>()

        val imageScalingFlags = applyVideoDecoderConfiguration(false, "8", options, videoOptions)

        assertEquals("auto", options["hwaccel"])
        assertEquals("8", videoOptions["threads"])
        assertEquals("low_delay", videoOptions["flags"])
        assertEquals(1, imageScalingFlags)
    }
}
