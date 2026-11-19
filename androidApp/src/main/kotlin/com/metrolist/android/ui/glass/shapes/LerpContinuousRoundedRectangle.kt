/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule - Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/LerpContinuousRoundedRectangle.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly, and the Absolute variant was dropped (unused).
 */
package com.metrolist.android.ui.glass.shapes

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.util.lerp

@Stable
fun lerp(
    start: ContinuousRoundedRectangle,
    stop: ContinuousRoundedRectangle,
    fraction: Float
): ContinuousRoundedRectangle {
    return LerpContinuousRoundedRectangle(start, stop, fraction)
}

@Immutable
private data class LerpContinuousRoundedRectangle(
    val start: ContinuousRoundedRectangle,
    val stop: ContinuousRoundedRectangle,
    val fraction: Float
) : ContinuousRoundedRectangle(
    topStart = LerpCornerSize(start.topStart, stop.topStart, fraction),
    topEnd = LerpCornerSize(start.topEnd, stop.topEnd, fraction),
    bottomEnd = LerpCornerSize(start.bottomEnd, stop.bottomEnd, fraction),
    bottomStart = LerpCornerSize(start.bottomStart, stop.bottomStart, fraction),
    continuity = when (fraction) {
        0f -> start.continuity
        1f -> stop.continuity
        else -> start.continuity.lerp(stop.continuity, fraction.toDouble())
    }
)

@Immutable
private data class LerpCornerSize(
    private val start: CornerSize,
    private val stop: CornerSize,
    private val fraction: Float
) : CornerSize {

    override fun toPx(shapeSize: Size, density: Density): Float {
        return lerp(
            start.toPx(shapeSize, density),
            stop.toPx(shapeSize, density),
            fraction
        ).fastCoerceAtLeast(0f)
    }
}
