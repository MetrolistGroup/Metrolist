package com.metrolist.android.ui.glass

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Material-only mask: a temporary liquid neck opens into the original shared capsule. */
internal fun glassActionJoinShape(base: Shape, cellWidth: Float, progress: Float, fixedTrailingCell: Boolean = false, vertical: Boolean = false): Shape {
    val phase = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    if (phase <= 0f || phase >= 1f) return base
    return glassImageShapeIntersection(base, GlassActionNeckShape(cellWidth, phase, fixedTrailingCell, vertical))
}

internal fun glassAxisTranspose() = Matrix().apply {
    this[0, 0] = 0f; this[0, 1] = 1f
    this[1, 0] = 1f; this[1, 1] = 0f
}

private data class GlassActionNeckShape(val cellWidth: Float, val progress: Float, val fixedTrailingCell: Boolean, val vertical: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val size = if (vertical) Size(size.height, size.width) else size
        // The tail grows from the existing cell. Its neck remains at their boundary,
        // rather than scaling or filtering either icon or its touch target.
        val leadingCell = cellWidth.coerceIn(0f, size.width)
        val seam = if ((!vertical && layoutDirection == LayoutDirection.Rtl) != fixedTrailingCell) size.width - leadingCell else leadingCell
        val span = min(seam, size.width - seam).coerceAtLeast(0f) * .9f
        val depth = glassActionJoinInset(size.height, progress)
        val left = seam - span
        val right = seam + span
        return Outline.Generic(Path().apply {
            moveTo(0f, 0f)
            lineTo(left, 0f)
            cubicTo(left + span * .5f, 0f, seam - span * .5f, depth, seam, depth)
            cubicTo(seam + span * .5f, depth, right - span * .5f, 0f, right, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(right, size.height)
            cubicTo(right - span * .5f, size.height, seam + span * .5f, size.height - depth, seam, size.height - depth)
            cubicTo(seam - span * .5f, size.height - depth, left + span * .5f, size.height, left, size.height)
            lineTo(0f, size.height)
            close()
            if (vertical) transform(glassAxisTranspose())
        })
    }
}

internal fun glassActionJoinInset(height: Float, progress: Float): Float {
    if (!progress.isFinite() || progress <= 0f || progress >= 1f) return 0f
    return height.coerceAtLeast(0f) * .34f * sin(PI * progress).toFloat()
}
