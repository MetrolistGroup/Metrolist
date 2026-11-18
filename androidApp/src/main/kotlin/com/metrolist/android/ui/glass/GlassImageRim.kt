package com.metrolist.android.ui.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Captured artwork keeps its native pixels even when live image contours are enabled. */
val LocalGlassImageDecorationsEnabled = compositionLocalOf { true }

/** Adds only the Glass edge highlight, keeping the image content and clipping intact. */
@Composable
fun Modifier.glassImageRim(shape: Shape): Modifier {
    if (!LocalGlassImageDecorationsEnabled.current || LocalNativeQueueRows.current) return this
    val settings = rememberGlassSettings()
    if (!settings.usesGlassContour(GlassArea.Images)) return this
    return glassImageRim(settings, shape, glassTint(settings))
}

/** Replaces an existing image border with the shared Glass rim when enabled. */
@Composable
fun Modifier.glassImageBorder(shape: Shape, nativeBorder: BorderStroke): Modifier {
    val settings = rememberGlassSettings()
    return if (LocalGlassImageDecorationsEnabled.current && !LocalNativeQueueRows.current && settings.enabled && settings.mode(GlassArea.Images).hasContour) {
        glassImageRim(settings, shape, glassTint(settings))
    } else border(nativeBorder, shape)
}

internal fun Modifier.glassImageRim(settings: GlassSettings, shape: Shape, tint: Color): Modifier {
    if (!settings.usesGlassContour(GlassArea.Images)) return this
    return glassEdgeRim(settings, shape, tint)
}

internal fun Modifier.glassEdgeRim(settings: GlassSettings, shape: Shape, tint: Color): Modifier {
    return border(
        width = .8.dp,
        brush = glassEdgeLightBrush(settings, tint),
        shape = shape,
    )
}

/** The same light and tint feed outer contours and functional internal separators. */
internal fun glassEdgeLightColor(settings: GlassSettings, tint: Color): Color =
    blendGlassTint(Color.White, tint, settings.tintStrength)

internal fun glassEdgeLightStops(settings: GlassSettings, tint: Color): Array<Pair<Float, Color>> {
    val color = glassEdgeLightColor(settings, tint)
    val strength = settings.rim.coerceIn(0f, 1f)
    return arrayOf(
        0f to color.copy(alpha = strength),
        .45f to color.copy(alpha = strength * .24f),
        .7f to color.copy(alpha = strength * .12f),
        1f to color.copy(alpha = strength * .65f),
    )
}

internal fun glassEdgeLightBrush(settings: GlassSettings, tint: Color): Brush =
    Brush.linearGradient(*glassEdgeLightStops(settings, tint))

/** Follows the visible boundary when existing artwork has two consecutive clips. */
fun glassImageShapeIntersection(first: Shape, second: Shape): Shape = GlassImageShapeIntersection(first, second)

private data class GlassImageShapeIntersection(val first: Shape, val second: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        fun Outline.asPath() = Path().apply {
            when (val outline = this@asPath) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        return Outline.Generic(Path.combine(
            PathOperation.Intersect,
            first.createOutline(size, layoutDirection, density).asPath(),
            second.createOutline(size, layoutDirection, density).asPath(),
        ))
    }
}
