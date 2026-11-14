/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import kotlinx.serialization.Serializable

object NavigationId {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val HISTORY = "history"
    const val STATISTICS = "stats"
    const val LISTEN_TOGETHER = "together"
}

@Serializable
data class NavItem(
    val id: String,
    val visible: Boolean = true,
)
