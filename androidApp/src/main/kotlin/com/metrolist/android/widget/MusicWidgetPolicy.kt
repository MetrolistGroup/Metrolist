package com.metrolist.android.widget

import kotlin.math.ceil

internal enum class MusicWidgetLayout { CONTROLS, WIDE, SQUARE, PLAYER }

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
    val tallHeight = maxOf(150, ceil(104 + 35 * scale).toInt())
    val playerHeight = maxOf(150, ceil(74 + 44 * scale).toInt())
    val layout =
        when {
            height >= playerHeight && width >= 250 -> MusicWidgetLayout.PLAYER
            height < tallHeight && width < 268 + 96 * scale -> MusicWidgetLayout.CONTROLS
            height < tallHeight -> MusicWidgetLayout.WIDE
            else -> MusicWidgetLayout.SQUARE
        }
    val artworkWidth =
        when (layout) {
            MusicWidgetLayout.WIDE -> 320 + 96 * scale
            MusicWidgetLayout.SQUARE -> 98 + 96 * scale
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
