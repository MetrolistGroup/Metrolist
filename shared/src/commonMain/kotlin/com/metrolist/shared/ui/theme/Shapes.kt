/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.settings.Settings

val MetrolistShapes =
    Shapes(
        extraSmall = RoundedCornerShape(16.dp),
        small = RoundedCornerShape(20.dp),
        medium = RoundedCornerShape(24.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

object ComponentShapes {
    val CardShape = RoundedCornerShape(20.dp)
    val BottomSheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val SmallThumbnailShape get() = RoundedCornerShape(uiEnhanced(4.dp, 8.dp))
    val ThumbnailShape get() = RoundedCornerShape(uiEnhanced(6.dp, 12.dp))
    val AlbumArtShape get() = RoundedCornerShape(uiEnhanced(16.dp, 32.dp))
    val MenuExteriorShape = RoundedCornerShape(28.dp)
    val MenuGroupShape = RoundedCornerShape(20.dp)
    val MenuItemShape = RoundedCornerShape(8.dp)
    val SearchExteriorCornerRadius = 24.dp
    val SearchItemCornerRadius = 6.dp
}

fun <T> uiEnhanced(
    legacy: T,
    enhanced: T,
): T = if (Settings.shared.uiEnhancements) enhanced else legacy

fun gridCellShape(): RoundedCornerShape = RoundedCornerShape(uiEnhanced(ThumbnailCornerRadius, 12.dp))

fun gridCellInset(): Dp = uiEnhanced(1.dp, 4.dp)
