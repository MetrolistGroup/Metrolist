// RECONSTRUCTED FROM CONVERSATION SUMMARY, NOT RECOVERED EXACT SOURCE.
// Uncompiled checkpoint; must review and test before use.
package com.metrolist.android.utils

internal fun lyricsFrameTimeUs(index: Int, fps: Int): Long {
    require(index >= 0 && fps in 1..30)
    return index * 1_000_000L / fps
}

internal fun lyricsVideoFrameCount(durationMs: Long, fps: Int): Int {
    require(durationMs in 1..30_000 && fps in 1..30)
    return ((durationMs * fps + 999) / 1000).toInt().coerceAtLeast(2)
}
