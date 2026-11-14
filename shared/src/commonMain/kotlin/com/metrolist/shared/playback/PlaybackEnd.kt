package com.metrolist.shared.playback

import kotlin.math.abs

internal fun correctedNativeDurationMs(
    metadataDurationMs: Long,
    nativeDurationMs: Long,
): Long =
    if (metadataDurationMs in 1L..Long.MAX_VALUE / 2 && nativeDurationMs > 0L &&
        abs(nativeDurationMs - metadataDurationMs * 2) <= minOf(2_000L, metadataDurationMs / 100)
    ) {
        metadataDurationMs
    } else {
        nativeDurationMs
    }
