/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.layouts

import androidx.compose.ui.unit.dp

object LayoutConstants {
    val MinimumPrimaryPaneWidth = 560.dp
    val MinimumSupportingPaneWidth = 240.dp

    object PlaylistTypes {
        const val TOP100 = "top100"
        const val DOWNLOADED = "downloaded"
        const val CACHED = "cached"
        const val UPLOADED = "uploaded"
        const val LIKED = "liked"
    }
}
