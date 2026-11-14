package com.metrolist.shared.settings

internal val LyricsTextSizeRange = 10f..40f

internal fun zoomedLyricsTextSize(
    size: Float,
    zoom: Float,
): Float = if (zoom.isFinite() && zoom > 0f) (size * zoom).coerceIn(LyricsTextSizeRange) else size
