/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

internal const val PLAYLIST_WIDGET_COMPACT_MAX_WIDTH_DP = 340

internal fun playlistWidgetQuickPickCapacity(
    minWidth: Int,
    minHeight: Int,
): Int =
    when {
        minWidth < PLAYLIST_WIDGET_COMPACT_MAX_WIDTH_DP -> 1
        minHeight < 330 -> 4
        minWidth >= 360 -> 8
        else -> 4
    }

internal fun playlistWidgetDisplayCount(
    available: Int,
    capacity: Int,
): Int {
    val capped = minOf(available, capacity, 8)
    return when {
        capped >= 8 -> 8
        capped >= 4 -> 4
        else -> capped
    }
}

internal fun needsTopWidgetSong(
    precedingPicks: Int,
    capacity: Int,
): Boolean = precedingPicks < playlistWidgetDisplayCount(precedingPicks + 1, capacity)
