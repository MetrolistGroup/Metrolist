/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.metrolist.shared.ui.utils.markPixelsImmutable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class TintFilterCache {
    private var color = Color.Unspecified
    private var filter: ColorFilter? = null

    fun get(target: Color): ColorFilter {
        val cached = filter
        if (cached != null && target == color) return cached
        return ColorFilter.tint(target).also {
            filter = it
            color = target
        }
    }
}

internal class SolidColorCache {
    private var color = Color.Unspecified
    private var brush: SolidColor? = null

    fun get(target: Color): SolidColor {
        val cached = brush
        if (cached != null && target == color) return cached
        return SolidColor(target).also {
            brush = it
            color = target
        }
    }
}

internal class ColorAlphaCache {
    private var baseColor = Color.Unspecified
    private var alpha = -1f
    private var cachedColor = Color.Unspecified

    fun get(
        base: Color,
        a: Float,
    ): Color {
        if (base == baseColor && a == alpha) return cachedColor
        val c = base.copy(alpha = a)
        baseColor = base
        alpha = a
        cachedColor = c
        return c
    }
}

internal class GlowShadowTable(
    val blurRadius: Float,
    private val steps: Int = 100,
) {
    private val table =
        Array(steps + 1) { i ->
            Shadow(
                color = Color.White.copy(alpha = i.toFloat() / steps),
                offset = Offset.Zero,
                blurRadius = blurRadius,
            )
        }

    fun get(alpha: Float): Shadow {
        val idx = (alpha * steps).toInt().coerceIn(0, steps)
        return table[idx]
    }
}

internal class ClusterMaskCache(
    count: Int,
    val padPx: Float,
) {
    private val bitmaps = arrayOfNulls<ImageBitmap>(count)
    private val scales = FloatArray(count)
    private val rasterScope = CanvasDrawScope()
    private val white = SolidColor(Color.White)

    val layerPaint = Paint()

    private val glowBitmaps = arrayOfNulls<ImageBitmap>(count)
    private val glowScales = FloatArray(count)
    private val glowFill = SolidColor(Color.White.copy(alpha = 0f))
    val glowFilter: ColorFilter = ColorFilter.tint(Color.White)

    fun glowScaleOf(idx: Int): Float = glowScales[idx]

    fun hasGlow(idx: Int): Boolean = glowBitmaps[idx] != null

    fun peek(idx: Int): ImageBitmap? = bitmaps[idx]

    fun peekGlow(idx: Int): ImageBitmap? = glowBitmaps[idx]

    fun obtainGlow(
        idx: Int,
        layout: TextLayoutResult,
        blurRadius: Float,
        density: Density,
    ): ImageBitmap {
        val existing = glowBitmaps[idx]
        if (existing != null) return existing

        val lw = max(1f, layout.size.width + padPx * 2f)
        val lh = max(1f, layout.size.height + padPx * 2f)
        val s = min(GLOW_RASTER_SCALE, MAX_MASK_SIDE / max(lw, lh))
        val w = ceil(lw * s).toInt().coerceIn(1, MAX_MASK_SIDE.toInt())
        val h = ceil(lh * s).toInt().coerceIn(1, MAX_MASK_SIDE.toInt())

        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Alpha8)

        val fullW = ceil(lw).toInt().coerceIn(1, MAX_GLOW_FULL_SIDE)
        val fullH = ceil(lh).toInt().coerceIn(1, MAX_GLOW_FULL_SIDE)
        val full = ImageBitmap(fullW, fullH, ImageBitmapConfig.Alpha8)
        rasterScope.draw(density, LayoutDirection.Ltr, Canvas(full), Size(fullW.toFloat(), fullH.toFloat())) {
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(padPx, padPx),
                brush = glowFill,
                shadow = Shadow(color = Color.White, offset = Offset.Zero, blurRadius = blurRadius),
            )
        }
        rasterScope.draw(density, LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            withTransform({ scale(s, s, Offset.Zero) }) {
                drawImage(
                    image = full,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(fullW, fullH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(fullW, fullH),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        bmp.markPixelsImmutable()
        glowBitmaps[idx] = bmp
        glowScales[idx] = s
        return bmp
    }

    val pastTint = TintFilterCache()
    val unlitTint = TintFilterCache()
    val inactiveTint = TintFilterCache()
    val glowTint = TintFilterCache()

    private var bandLit = Color.Unspecified
    private var bandUnlit = Color.Unspecified
    private var bandDx = Float.NaN
    private var bandBrushCached: Brush? = null

    fun bandBrush(
        lit: Color,
        unlit: Color,
        dx: Float,
    ): Brush {
        val cached = bandBrushCached
        if (cached != null && lit == bandLit && unlit == bandUnlit && dx == bandDx) return cached
        return Brush
            .linearGradient(
                0.0f to lit,
                0.1f to lit,
                0.9f to unlit,
                1.0f to unlit,
                start = Offset.Zero,
                end = Offset(dx, 0f),
            ).also {
                bandBrushCached = it
                bandLit = lit
                bandUnlit = unlit
                bandDx = dx
            }
    }

    suspend fun prewarmSliced(
        count: Int,
        density: Density,
        layoutOf: (Int) -> TextLayoutResult,
        glowBlurOf: (Int) -> Float,
    ) {
        var mark = TimeSource.Monotonic.markNow()

        suspend fun yieldIfOverBudget() {
            if (mark.elapsedNow() >= PREWARM_FRAME_BUDGET) {
                withFrameNanos { }
                mark = TimeSource.Monotonic.markNow()
            }
        }
        for (idx in 0 until count) {
            obtain(idx, layoutOf(idx), density)
            yieldIfOverBudget()
            val blur = glowBlurOf(idx)
            if (blur > 0f && !hasGlow(idx)) {
                obtainGlow(idx, layoutOf(idx), blur, density)
                yieldIfOverBudget()
            }
        }
    }

    fun scaleOf(idx: Int): Float = scales[idx]

    fun obtain(
        idx: Int,
        layout: TextLayoutResult,
        density: Density,
    ): ImageBitmap {
        val existing = bitmaps[idx]
        if (existing != null) return existing

        val lw = max(1f, layout.size.width + padPx * 2f)
        val lh = max(1f, layout.size.height + padPx * 2f)
        val s = min(MASK_RASTER_SCALE, MAX_MASK_SIDE / max(lw, lh))
        val w = ceil(lw * s).toInt().coerceIn(1, MAX_MASK_SIDE.toInt())
        val h = ceil(lh * s).toInt().coerceIn(1, MAX_MASK_SIDE.toInt())

        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Alpha8)
        rasterScope.draw(density, LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            withTransform({ scale(s, s, Offset.Zero) }) {
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(padPx, padPx),
                    brush = white,
                    shadow = LyricsClearShadow,
                )
            }
        }
        bmp.markPixelsImmutable()
        bitmaps[idx] = bmp
        scales[idx] = s
        return bmp
    }

    private companion object {
        const val MAX_MASK_SIDE = 2048f
        const val MAX_GLOW_FULL_SIDE = 4096
    }
}

internal const val MASK_RASTER_SCALE = 1.25f
internal const val GLOW_RASTER_SCALE = 0.5f
internal val PREWARM_FRAME_BUDGET = 3.milliseconds

internal const val WIPE_PAST = 0
internal const val WIPE_UNLIT = 1
internal const val WIPE_BAND = 2

internal class WipeResult {
    var mode = WIPE_UNLIT
    var bandStart = 0f
    var bandEnd = 0f
}

internal fun computeWipe(
    vc: VisualCluster,
    isRtlLine: Boolean,
    lineStart: Float,
    lineWidth: Float,
    localStart: Float,
    localEnd: Float,
    activeSylIdx: Int,
    activeLineIdx: Int,
    curGX: Float,
    isActiveSylLastOfLine: Boolean,
    activeSylProgress: Float,
    gradientWidth: Float,
    out: WipeResult,
) {
    val halfGradientWidth = gradientWidth / 2f

    val lastSylIdx = if (vc.lastSyllableIndex != -1) vc.lastSyllableIndex else vc.syllableIndex
    val isPartOfActiveSyl = vc.syllableIndex != -1 && activeSylIdx in vc.syllableIndex..lastSylIdx
    val effectiveLineIdx = if (isPartOfActiveSyl) vc.lineIdx else activeLineIdx

    val rawLocalX = curGX - lineStart
    val brushShift =
        if (vc.lineIdx == effectiveLineIdx && isActiveSylLastOfLine) {
            activeSylProgress * halfGradientWidth
        } else {
            0f
        }

    val localX =
        when {
            activeSylIdx == -1 -> -gradientWidth * 2f
            isPartOfActiveSyl -> rawLocalX + brushShift
            vc.lineIdx < activeLineIdx -> lineWidth + gradientWidth * 2f
            vc.lineIdx > activeLineIdx -> -gradientWidth * 2f
            else -> rawLocalX + brushShift
        }

    if (localX >= localEnd + halfGradientWidth) {
        out.mode = WIPE_PAST
        return
    }
    if (localX <= localStart - halfGradientWidth) {
        out.mode = WIPE_UNLIT
        return
    }

    out.mode = WIPE_BAND
    val relWiperX = localX - localStart
    if (localX < halfGradientWidth) {
        val growProgress = (localX / halfGradientWidth).coerceIn(0f, 1f)
        val growWidth = max(0.1f, gradientWidth * growProgress)
        if (isRtlLine) {
            out.bandStart = vc.width + localStart
            out.bandEnd = vc.width + localStart - growWidth
        } else {
            out.bandStart = -localStart
            out.bandEnd = growWidth - localStart
        }
    } else {
        if (isRtlLine) {
            out.bandStart = vc.width - (relWiperX - halfGradientWidth)
            out.bandEnd = vc.width - (relWiperX + halfGradientWidth)
        } else {
            out.bandStart = relWiperX - halfGradientWidth
            out.bandEnd = relWiperX + halfGradientWidth
        }
    }
}

internal fun DrawScope.drawMaskTinted(
    mask: ImageBitmap,
    maskScale: Float,
    padPx: Float,
    filter: ColorFilter,
    alpha: Float = 1f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    val inv = 1f / maskScale
    val padM = padPx * maskScale
    withTransform({
        scale(inv, inv, Offset.Zero)
        translate(-padM, -padM)
    }) {
        drawImage(
            image = mask,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(mask.width, mask.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(mask.width, mask.height),
            alpha = alpha,
            colorFilter = filter,
            blendMode = blendMode,
            filterQuality = FilterQuality.Low,
        )
    }
}

internal fun DrawScope.drawMaskBand(
    mask: ImageBitmap,
    maskScale: Float,
    padPx: Float,
    wipe: WipeResult,
    brush: Brush,
    layerPaint: Paint,
) {
    val inv = 1f / maskScale
    val padM = padPx * maskScale
    val w = mask.width.toFloat()
    val h = mask.height.toFloat()
    val bandX = wipe.bandStart * maskScale + padM
    withTransform({
        scale(inv, inv, Offset.Zero)
        translate(-padM, -padM)
    }) {
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(0f, 0f, w, h), layerPaint)
        drawImage(
            image = mask,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(mask.width, mask.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(mask.width, mask.height),
            filterQuality = FilterQuality.Low,
        )
        withTransform({ translate(bandX, 0f) }) {
            drawRect(brush = brush, topLeft = Offset(-bandX, 0f), size = Size(w, h), blendMode = BlendMode.SrcIn)
        }
        canvas.restore()
    }
}
