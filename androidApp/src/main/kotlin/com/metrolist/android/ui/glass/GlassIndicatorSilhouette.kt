package com.metrolist.android.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.metrolist.android.ui.theme.colorPalette

/** Uses the native animated glyph as the mask for a self-contained Glass surface. */
@Composable
internal fun GlassIndicatorSilhouette(
    settings: GlassSettings,
    area: GlassArea,
    filmColor: Color,
    modifier: Modifier = Modifier,
    indicator: @Composable (Modifier) -> Unit,
) {
    val contour = settings.usesGlassContour(area)
    // Refraction cannot distinguish a film-free glyph from a uniform backdrop.
    val material = settings.usesGlassMaterial(area) &&
        (settings.film > 0f || contour && glassRenderer(settings) != GlassRenderer.Tint)
    if (!material && !contour) {
        indicator(modifier)
        return
    }

    val glyph = rememberGraphicsLayer()
    val mask = rememberGraphicsLayer()
    val innerMask = if (contour) rememberGraphicsLayer() else null
    val edge = if (contour) glassEdgeLightBrush(settings, glassTint(settings)) else null
    val clipGlyph = Modifier.drawWithContent {
        glyph.record { this@drawWithContent.drawContent() }
        mask.blendMode = BlendMode.DstIn
        mask.record { drawLayer(glyph) }
        if (material) drawLayer(mask) else drawLayer(glyph)

        if (innerMask != null && edge != null) {
            val scale = (1f - 4f * GlassRimWidth.toPx() / size.minDimension).coerceIn(0f, 1f)
            innerMask.blendMode = BlendMode.DstOut
            innerMask.record {
                scale(scale) { drawLayer(glyph) }
            }
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
            drawRect(edge)
            drawLayer(mask)
            drawLayer(innerMask)
            drawContext.canvas.restore()
        }
    }
    if (material) {
        val palette = colorPalette()
        val backdrop = rememberInlineGlassBackdrop(palette.background0)
        Box(
            modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            propagateMinConstraints = true,
        ) {
            Box(Modifier.matchParentSize().glassMaterial(
                settings.forArea(area).copy(rim = 0f),
                CircleShape,
                ownBackdrop = backdrop,
                filmColor = filmColor,
                drawShadow = false,
            ))
            indicator(clipGlyph)
        }
    } else {
        indicator(modifier.then(clipGlyph))
    }
}
