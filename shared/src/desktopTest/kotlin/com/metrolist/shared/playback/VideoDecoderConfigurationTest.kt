package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoDecoderConfigurationTest {
    @Test
    fun loopingVideoUsesSingleThreadedSoftwareDecoding() {
        assertEquals(
            VideoDecoderConfiguration(hardwareAcceleration = null, threadCount = "1"),
            videoDecoderConfiguration(looping = true, optimalThreads = "8"),
        )
    }

    @Test
    fun regularVideoKeepsHardwareAccelerationAndOptimalThreads() {
        assertEquals(
            VideoDecoderConfiguration(hardwareAcceleration = "auto", threadCount = "8"),
            videoDecoderConfiguration(looping = false, optimalThreads = "8"),
        )
    }
}
