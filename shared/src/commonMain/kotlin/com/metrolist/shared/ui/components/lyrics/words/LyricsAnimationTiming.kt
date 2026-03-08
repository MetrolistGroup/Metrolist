package com.metrolist.shared.ui.components.lyrics.words

internal const val MIN_WORD_ANIMATION_DURATION_MS = 100L

internal fun lyricsWordAnimationDurationMs(durationMs: Long): Long = durationMs.coerceAtLeast(MIN_WORD_ANIMATION_DURATION_MS)

internal fun lyricsWordAnimationProgress(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
): Float {
    val durationMs = lyricsWordAnimationDurationMs(endMs - startMs)
    return ((positionMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
}
