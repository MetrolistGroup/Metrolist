package com.metrolist.android.ui.glass

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.lerp

/** Resolves bounded app corners at each endpoint, retaining the actual source and menu shapes. */
internal fun glassMenuShape(
    source: Shape,
    target: Shape,
    sourceSize: Size,
    targetSize: Size,
    progress: Float,
    density: Density,
): Shape {
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction == 0f) return source
    if (fraction == 1f) return target
    if (source !is RoundedCornerShape || target !is RoundedCornerShape) {
        return if (fraction < .5f) source else target
    }
    fun corner(from: CornerSize, to: CornerSize): CornerSize = CornerSize(
        lerp(from.toPx(sourceSize, density), to.toPx(targetSize, density), fraction).coerceAtLeast(0f),
    )
    return RoundedCornerShape(
        topStart = corner(source.topStart, target.topStart),
        topEnd = corner(source.topEnd, target.topEnd),
        bottomEnd = corner(source.bottomEnd, target.bottomEnd),
        bottomStart = corner(source.bottomStart, target.bottomStart),
    )
}
