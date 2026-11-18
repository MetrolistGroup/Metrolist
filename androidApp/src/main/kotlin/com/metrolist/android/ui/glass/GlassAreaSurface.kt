package com.metrolist.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.metrolist.android.ui.glass.backdrop.Backdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.LayerBackdrop
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.glass.backdrop.drawPlainBackdrop

/** Follows the actual sheet position in both directions, including interrupted drags. */
internal fun miniPlayerGlassBodyAlpha(progress: Float): Float =
    1f - ((if (progress.isFinite()) progress else 0f) / MINI_PLAYER_GLASS_BODY_FADE_END).coerceIn(0f, 1f)

private const val MINI_PLAYER_GLASS_BODY_FADE_END = .12f

/** A transparent material must not reveal the swipe action's native fill while at rest. */
internal fun miniPlayerSwipeBackgroundColor(settings: GlassSettings, revealed: Boolean, original: Color): Color =
    if (settings.usesGlassMaterial(GlassArea.MiniPlayer) && !revealed) Color.Transparent else original

/** Apply only to the empty body layer so controls keep their own material. */
internal fun Modifier.glassMiniPlayerBodyVisibility(settings: GlassSettings, progress: () -> Float): Modifier =
    if (settings.usesGlassMaterial(GlassArea.MiniPlayer)) graphicsLayer {
        alpha = miniPlayerGlassBodyAlpha(progress())
    } else this

/** Controls sample the visible body, including progress, before applying their own role film. */
@Composable
@Suppress("UNUSED_PARAMETER") // Existing callers pass their independent mini-player settings.
internal fun defaultMiniPlayerControlBackdrop(settings: GlassSettings): Backdrop? {
    val local = LocalGlassBackdrop.current
    val material = LocalGlassMaterialSource.current
    val host = LocalGlassMenuHost.current
    return if (local is RenderedMaterialBackdrop) local
        else material ?: local ?: host?.backdrop
}

/** Mixes material-only exports; foreground artwork and controls never enter this source. */
@Composable
internal fun rememberMiniPlayerControlBackdrop(
    parent: Backdrop?,
    body: Backdrop,
    progress: () -> Float,
): Backdrop = remember(parent, body, progress) {
    val rendered = if (parent == null) body else object : Backdrop {
        override val isCoordinatesDependent = true

        override val contentVersion: Int?
            get() {
                // Read both even when one is unversioned: the body's published material
                // revision must invalidate retained control captures after progress moves.
                val parentVersion = parent.contentVersion
                val bodyVersion = body.contentVersion
                val opacityVersion = miniPlayerGlassBodyAlpha(progress()).toBits()
                return if (parentVersion == null || bodyVersion == null) null
                    else (31 * parentVersion + bodyVersion) * 31 + opacityVersion
            }

        override fun DrawScope.drawBackdrop(
            density: Density,
            coordinates: LayoutCoordinates?,
            layerBlock: (GraphicsLayerScope.() -> Unit)?,
        ) {
            with(parent) { drawBackdrop(density, coordinates, layerBlock) }
            val opacity = miniPlayerGlassBodyAlpha(progress())
            if (opacity > 0f) {
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = opacity })
                with(body) { drawBackdrop(density, coordinates, layerBlock) }
                drawContext.canvas.restore()
            }
        }
    }
    RenderedMaterialBackdrop(rendered)
}

/** Applied to the native branch only: the material branch already owns its sole contour. */
@Composable
fun Modifier.glassAreaContour(settings: GlassSettings, area: GlassArea, shape: Shape): Modifier {
    if (!settings.usesGlassContour(area)) return this
    val tint = glassTint(settings)
    return if (area == GlassArea.Menus && settings.scope != GlassScope.Toast) {
        glassMenuRim(shape, glassEdgeLightBrush(settings, tint))
    } else if (area == GlassArea.Dropdowns || area == GlassArea.Menus) {
        border(GlassRimWidth, glassEdgeLightBrush(settings, tint), shape)
    } else glassEdgeRim(settings, shape, tint)
}

/** Keeps the native mini player fill and shadow whenever only its contour is selected. */
@Composable
fun Modifier.glassMiniPlayerBackground(
    settings: GlassSettings,
    shape: Shape,
    backdrop: Backdrop?,
    originalColor: Color,
    progressColor: Color = Color.Transparent,
    progressFraction: () -> Float = { 0f },
    exportedBackdrop: LayerBackdrop? = null,
    motionOptimized: Boolean = false,
    foldInProgress: (() -> Boolean)? = null,
): Modifier {
    val fraction = { progressFraction().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f } }
    if (!settings.usesGlassMaterial(GlassArea.MiniPlayer)) {
        if (exportedBackdrop != null) {
            // Action badges can remain Glass while the body is native. Export the actual
            // native fill/progress before foreground controls, rather than an empty layer.
            val empty = rememberCanvasBackdrop {}
            return drawPlainBackdrop(empty, { shape }, {}, exportedBackdrop = exportedBackdrop,
                onDrawSurface = {
                    drawRect(originalColor)
                    drawRect(progressColor, size = Size(fraction() * size.width, size.maxDimension))
                }).glassAreaContour(settings, GlassArea.MiniPlayer, shape)
        }
        return background(originalColor).drawBehind {
            drawRect(progressColor, size = Size(fraction() * size.width, size.maxDimension))
        }.glassAreaContour(settings, GlassArea.MiniPlayer, shape)
    }
    val baseFilm = glassRoleFilmTint(settings, originalColor)
    val progressFilm = glassRoleFilmTint(settings, progressColor.compositeOver(originalColor))
    val opacity = settings.film.coerceIn(0f, 1f)
    // Both regions share one optical surface and one configured film opacity.
    // Progress changes its role colour, never the material's coverage.
    return glassMaterial(
        settings.forArea(GlassArea.MiniPlayer), shape, ownBackdrop = backdrop,
        highlightAlpha = .3f, drawFilm = false, exportedBackdrop = exportedBackdrop,
        applyEdgeEffects = !motionOptimized,
        backdropScale = if (motionOptimized) .5f else null,
        drawShadow = !motionOptimized,
        foldInProgress = foldInProgress,
        onDrawTint = {
            val split = size.width * fraction()
            clipRect(right = split) { drawRect(progressFilm.copy(alpha = opacity)) }
            clipRect(left = split) { drawRect(baseFilm.copy(alpha = opacity)) }
        },
    )
}
