/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

import kotlin.math.ceil

private const val MIN_COMPACT_ROW_HEIGHT_DP = 56

internal enum class MusicWidgetLayout(
    val showsProgress: Boolean,
) {
    CONTROLS(showsProgress = false),
    COMPACT(showsProgress = true),
    WIDE(showsProgress = false),
    SQUARE(showsProgress = true),
    PLAYER(showsProgress = true),
}

internal data class MusicWidgetSizePolicy(
    val layout: MusicWidgetLayout,
    val showArtwork: Boolean,
)

internal fun musicWidgetSizePolicy(
    width: Int,
    height: Int,
    fontScale: Float,
): MusicWidgetSizePolicy {
    val scale = fontScale.coerceAtLeast(1f)
    val tallHeight = maxOf(155, ceil(115 + 40 * scale).toInt())
    val playerHeight = maxOf(155, ceil(110 + 45 * scale).toInt())
    val metadataRowHeight = maxOf(MIN_COMPACT_ROW_HEIGHT_DP, ceil(20 + 34 * scale).toInt())
    val layout =
        when {
            width < 154 -> MusicWidgetLayout.CONTROLS
            height >= playerHeight && width >= 250 -> MusicWidgetLayout.PLAYER
            height < tallHeight && (width < 190 || height < metadataRowHeight) -> MusicWidgetLayout.CONTROLS
            height < tallHeight && width < 290 -> MusicWidgetLayout.COMPACT
            height < tallHeight -> MusicWidgetLayout.WIDE
            else -> MusicWidgetLayout.SQUARE
        }
    val artworkWidth =
        when (layout) {
            MusicWidgetLayout.COMPACT -> 250f + 22f * (scale - 1f)
            MusicWidgetLayout.WIDE -> 270f + 28f * (scale - 1f)
            MusicWidgetLayout.SQUARE -> 100f
            else -> 0f
        }
    return MusicWidgetSizePolicy(layout, width >= artworkWidth)
}

internal data class WidgetControls(
    val canPlayPause: Boolean = true,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val canLike: Boolean = false,
    val playbackRequested: Boolean = false,
)
