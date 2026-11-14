package com.metrolist.android.playback

import androidx.media3.exoplayer.DefaultLoadControl

internal fun createArtworkVideoLoadControl(): DefaultLoadControl =
    DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
        // Artwork shares the heap with the audio player and image cache.
        .setTargetBufferBytes(8 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
