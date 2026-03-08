package com.metrolist.shared.ui.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

fun appearanceDensity(
    systemDensity: Density,
    smallestDimensionDp: Float,
    enableLandscapeScaling: Boolean,
    userDensityScale: Float,
    availableWidthDp: Float = smallestDimensionDp,
): Density {
    val landscapeScale =
        if (enableLandscapeScaling) {
            when {
                smallestDimensionDp >= 840f -> 1.15f
                smallestDimensionDp >= 720f -> 1.1f
                smallestDimensionDp >= 600f -> 1.05f
                else -> 1f
            }
        } else {
            1f
        }
    val smallScreenScale =
        if (availableWidthDp > 0f) {
            (availableWidthDp / COMPACT_BASELINE_DP).coerceIn(MIN_SMALL_SCREEN_SCALE, 1f)
        } else {
            1f
        }
    val userScale = userDensityScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    return AppearanceDensity(
        systemDensity = systemDensity,
        density = systemDensity.density * landscapeScale * smallScreenScale * userScale,
        smallScreenScale = smallScreenScale,
    )
}

private data class AppearanceDensity(
    val systemDensity: Density,
    override val density: Float,
    val smallScreenScale: Float,
) : Density {
    override val fontScale = systemDensity.fontScale / smallScreenScale

    override fun TextUnit.toDp(): Dp = with(systemDensity) { this@toDp.toDp() } / smallScreenScale

    override fun Dp.toSp(): TextUnit = with(systemDensity) { (this@toSp * smallScreenScale).toSp() }
}

private const val COMPACT_BASELINE_DP = 360f
private const val MIN_SMALL_SCREEN_SCALE = 0.85f
