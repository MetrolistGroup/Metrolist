package com.metrolist.android.ui.glass

import androidx.compose.ui.unit.dp

/** Compact N-Zik sizing for the shared action capsule, based on Square's controls. */
val GlassActionCellWidth = 44.dp
val GlassActionCellHeight = 40.dp
val GlassActionIconSize = 20.dp

/** A standalone action retains the capsule's height without its horizontal spacing. */
val GlassStandaloneActionSize = GlassActionCellHeight

/** Scope action geometry without changing page toolbar defaults. */
internal val LocalGlassActionCellSize = androidx.compose.runtime.staticCompositionLocalOf {
    androidx.compose.ui.unit.DpSize(GlassActionCellWidth, GlassActionCellHeight)
}
