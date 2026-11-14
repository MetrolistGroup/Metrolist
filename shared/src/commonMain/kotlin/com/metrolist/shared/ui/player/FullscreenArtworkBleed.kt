/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.metrolist.shared.ui.utils.pixelsToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas

// Blur radii in px at REFERENCE_WIDTH. The steps are fine at the low end so the first blurred rows are not a visible band.
private val BLUR_RADII = floatArrayOf(0f, 1f, 2f, 3.5f, 5.5f, 8f, 11f, 15f, 20f, 27f, 36f, 48f, 63f, 82f, 105f, 135f, 170f, 210f, 250f)
private const val REFERENCE_WIDTH = 1080f
private const val BLUR_START = 0.70f
private const val BLUR_END = 1.55f

// A steep start keeps the first blurred rows indistinguishable from the sharp art.
private const val BLUR_CURVE = 1.8f
private const val BLUR_PASSES = 3
private const val TONE_START = 0.90f
private const val TONE_SPAN = 0.95f
private const val TONE_STRENGTH = 0.90f
private const val SHADOW_LOW = 0.10f
private const val SHADOW_HIGH = 0.45f

// Small blur radii need full resolution; everything below DETAIL_END is blurred enough for a quarter-width render.
private const val DETAIL_HEIGHT = 0.40f
private const val SOFT_DIVISOR = 4

// Blur strengths (in canvas px) from which a half or quarter size copy is indistinguishable from a full size one.
private const val HALF_RES_SIGMA = 4f
private const val QUARTER_RES_SIGMA = 8f
private const val GRAIN_TILE = 64
private const val GRAIN_ALPHA = 3
private const val BLEED_CACHE_SIZE = 4

// Only the cover's bottom rows feed the area below it, so no large shape can be reflected there.
private const val BLEED_SOURCE = 0.18f
private const val CROSSFADE_ROWS = 48

// The cover reaches this share of the player height, cropping its sides, within MAX_COVER_ZOOM.
private const val COVER_HEIGHT_FRACTION = 0.6f
private const val MAX_COVER_ZOOM = 1.6f

private data class BleedKey(
    val url: String,
    val container: IntSize,
)

private class FullscreenBleed(
    val tone: Color,
    val preview: ImageBitmap?,
    val art: ImageBitmap?,
    val detail: ImageBitmap?,
    val soft: ImageBitmap?,
    val sourceWidth: Int,
)

// Only touched from the main thread, so a plain map is enough.
private val bleedCache = LinkedHashMap<BleedKey, FullscreenBleed>()

private val grainBrush: ShaderBrush by lazy {
    val random = Random(0)
    val pixels = IntArray(GRAIN_TILE * GRAIN_TILE) { (GRAIN_ALPHA shl 24) or if (random.nextBoolean()) 0xFFFFFF else 0 }
    ShaderBrush(ImageShader(pixelsToImageBitmap(pixels, GRAIN_TILE, GRAIN_TILE), TileMode.Repeated, TileMode.Repeated))
}

/**
 * Static artwork for the portrait Full screen style: the cover pinned to the top and zoomed so it fills most of the
 * player, continuing below into a stretched copy of its bottom rows under a progressive blur, so no sharp layer ghosts
 * over the blur. The bleed is built at the zoomed width and centered, which crops the cover's sides.
 */
@Composable
internal fun FullscreenArtworkBleed(
    url: String,
    artwork: ImageBitmap?,
    viewport: IntSize,
) {
    if (artwork == null) return
    val coverHeight = viewport.width * artwork.height / artwork.width.toFloat()
    val zoom = (viewport.height * COVER_HEIGHT_FRACTION / coverHeight).coerceIn(1f, MAX_COVER_ZOOM)
    val container = IntSize((viewport.width * zoom).roundToInt(), viewport.height)
    val key = BleedKey(url, container)
    val artHeight = (container.width * artwork.height / artwork.width.toFloat()).roundToInt().coerceAtLeast(1)
    // Both bitmaps start on a multiple of SOFT_DIVISOR so the quarter-width part lines up with the detail part.
    val detailTop = (BLUR_START * container.width / SOFT_DIVISOR).toInt() * SOFT_DIVISOR
    val softTop = detailTop + ceil(DETAIL_HEIGHT * container.width / SOFT_DIVISOR).toInt() * SOFT_DIVISOR
    var bleed by remember(key) { mutableStateOf(bleedCache[key]) }
    LaunchedEffect(key, artwork) {
        // The mini player's smaller image can arrive first; a bleed built from it would keep the cover blurry, so rebuild
        // once the full-size one lands.
        val current = bleed
        if (container.height <= detailTop || (current?.detail != null && current.sourceWidth >= artwork.width)) return@LaunchedEffect
        val coarse = current ?: withContext(Dispatchers.Default) { renderCoarseBleed(artwork, container, artHeight, detailTop) }
        bleed = coarse
        bleed =
            withContext(Dispatchers.Default) {
                val art = artwork.downscaled(container.width, artHeight)
                renderBleedBitmaps(art, container, artHeight, detailTop, softTop, coarse, artwork.width)
            }.also {
                bleedCache[key] = it
                if (bleedCache.size > BLEED_CACHE_SIZE) bleedCache.remove(bleedCache.keys.first())
            }
    }
    val previewAlpha by animateFloatAsState(if (bleed?.preview != null) 1f else 0f, tween(CANVAS_CROSSFADE_DURATION_MS))
    val bleedAlpha by animateFloatAsState(if (bleed?.detail != null) 1f else 0f, tween(CANVAS_CROSSFADE_DURATION_MS))

    Canvas(Modifier.fillMaxSize()) {
        bleed?.let { drawRect(it.tone) }
        translate(left = (size.width - container.width) / 2f) {
            drawBleed(artwork, container.width, artHeight, detailTop, softTop, bleed, previewAlpha, bleedAlpha)
        }
    }
}

private fun DrawScope.drawBleed(
    artwork: ImageBitmap,
    width: Int,
    artHeight: Int,
    detailTop: Int,
    softTop: Int,
    bleed: FullscreenBleed?,
    previewAlpha: Float,
    bleedAlpha: Float,
) {
    drawImage(artwork, dstSize = IntSize(width, artHeight), filterQuality = FilterQuality.High)
    bleed?.preview?.let {
        drawImage(
            it,
            dstOffset = IntOffset(0, detailTop),
            dstSize = IntSize(it.width * SOFT_DIVISOR, it.height * SOFT_DIVISOR),
            alpha = previewAlpha,
            filterQuality = FilterQuality.Low,
        )
    }
    // The bleed is generated from this exact copy, so the two match pixel for pixel where the blur starts; a separate
    // GPU-scaled copy of the cover resamples slightly differently and shows as a line.
    bleed?.art?.let { drawImage(it, alpha = bleedAlpha) }
    bleed?.detail?.let { detail ->
        drawImage(detail, dstOffset = IntOffset(0, detailTop), alpha = bleedAlpha)
        bleed.soft?.let {
            drawImage(
                it,
                dstOffset = IntOffset(0, softTop),
                dstSize = IntSize(it.width * SOFT_DIVISOR, it.height * SOFT_DIVISOR),
                alpha = bleedAlpha,
                filterQuality = FilterQuality.Low,
            )
        }
        drawRect(
            grainBrush,
            topLeft = Offset(0f, detailTop.toFloat()),
            size = Size(width.toFloat(), size.height - detailTop),
            alpha = bleedAlpha,
        )
    }
}

private suspend fun renderBleedBitmaps(
    art: IntArray,
    container: IntSize,
    artHeight: Int,
    detailTop: Int,
    softTop: Int,
    coarse: FullscreenBleed,
    sourceWidth: Int,
): FullscreenBleed {
    val tone = coarse.tone.toArgb()
    val width = container.width
    val canvas = bleedCanvas(art, width, artHeight, container.height)
    val detailBottom = min(softTop, container.height)
    val detailRows = renderBleedRows(canvas, width, container.height, width.toFloat(), detailTop, detailBottom - 1, tone)
    fadeInTopRows(detailRows, width, CROSSFADE_ROWS)
    val art = pixelsToImageBitmap(art, width, artHeight)
    val detail = pixelsToImageBitmap(detailRows, width, detailBottom - detailTop)
    if (container.height <= softTop) return FullscreenBleed(coarse.tone, coarse.preview, art, detail, null, sourceWidth)

    val softWidth = (width + SOFT_DIVISOR - 1) / SOFT_DIVISOR
    val softHeight = (container.height + SOFT_DIVISOR - 1) / SOFT_DIVISOR
    val softCanvas = canvas.downsampled(width, container.height, 0, container.height - 1, SOFT_DIVISOR)
    val soft = softBitmap(softCanvas, softWidth, softHeight, width, softTop / SOFT_DIVISOR, tone, 0)
    return FullscreenBleed(coarse.tone, coarse.preview, art, detail, soft, sourceWidth)
}

// A bleed from a quarter-size copy of the cover, ready within a few frames, so the cover's bottom edge is never shown bare
// while the detailed version is still being generated.
private suspend fun renderCoarseBleed(
    artwork: ImageBitmap,
    container: IntSize,
    artHeight: Int,
    detailTop: Int,
): FullscreenBleed {
    val width = (container.width + SOFT_DIVISOR - 1) / SOFT_DIVISOR
    val artRows = (artHeight + SOFT_DIVISOR - 1) / SOFT_DIVISOR
    val height = (container.height + SOFT_DIVISOR - 1) / SOFT_DIVISOR
    val small = artwork.downscaled(width, artRows)
    val tone = bleedTone(small)
    val canvas = bleedCanvas(small, width, artRows, height)
    val preview = softBitmap(canvas, width, height, container.width, detailTop / SOFT_DIVISOR, tone, CROSSFADE_ROWS / SOFT_DIVISOR)
    return FullscreenBleed(Color(tone), preview, null, null, null, 0)
}

// Rows [firstRow].. of a quarter-size canvas, with the top [fadeRows] fading in from transparent.
private suspend fun softBitmap(
    canvas: IntArray,
    width: Int,
    height: Int,
    containerWidth: Int,
    firstRow: Int,
    tone: Int,
    fadeRows: Int,
): ImageBitmap {
    val rows = renderBleedRows(canvas, width, height, containerWidth.toFloat() / SOFT_DIVISOR, firstRow, height - 1, tone)
    fadeInTopRows(rows, width, fadeRows)
    return pixelsToImageBitmap(rows, width, height - firstRow)
}

// The blur starts at radius 0, so processed rows begin identical to the cover; fading them in hides any resampling difference.
private fun fadeInTopRows(
    pixels: IntArray,
    width: Int,
    fadeRows: Int,
) {
    for (row in 0 until min(fadeRows, pixels.size / width)) {
        val alpha = (smootherstep((row + 0.5f) / fadeRows) * 255).roundToInt()
        for (i in row * width until (row + 1) * width) pixels[i] = (alpha shl 24) or (pixels[i] and 0xFFFFFF)
    }
}

private fun ImageBitmap.downscaled(
    width: Int,
    height: Int,
): IntArray {
    val target = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, GraphicsCanvas(target), Size(width.toFloat(), height.toFloat())) {
        drawImage(this@downscaled, dstSize = IntSize(width, height), filterQuality = FilterQuality.High)
    }
    return IntArray(width * height).also { target.readPixels(it) }
}

private fun smootherstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

private fun luminance(argb: Int): Int = 299 * ((argb shr 16) and 0xFF) + 587 * ((argb shr 8) and 0xFF) + 114 * (argb and 0xFF)

/** The cover's shadow colour, kept dark and muted so the bleed stays behind white controls on any cover. */
internal fun bleedTone(art: IntArray): Int {
    val byLuminance = art.sortedBy(::luminance)
    val from = (art.size * SHADOW_LOW).toInt()
    val to = max((art.size * SHADOW_HIGH).toInt(), from + 1)
    var red = 0L
    var green = 0L
    var blue = 0L
    for (pixel in byLuminance.subList(from, to)) {
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
    }
    val count = (to - from) * 255f
    val r = red / count
    val g = green / count
    val b = blue / count
    val high = maxOf(r, g, b)
    val low = minOf(r, g, b)
    val delta = high - low
    val lightness = (high + low) / 2f
    val saturation = if (delta == 0f) 0f else delta / (1f - abs(2f * lightness - 1f))
    val hue =
        when {
            delta == 0f -> 0f
            high == r -> 60f * (((g - b) / delta + 6f) % 6f)
            high == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
    return Color.hsl(hue, min(saturation, 0.5f), (lightness * 1.15f).coerceIn(0.07f, 0.30f)).toArgb()
}

/**
 * The cover followed by its bottom rows flipped and stretched down to [height]. The first row below the cover is the
 * cover's last row, so the seam is continuous, and stretching turns whatever is there into soft colour fields.
 */
internal fun bleedCanvas(
    art: IntArray,
    width: Int,
    artHeight: Int,
    height: Int,
): IntArray {
    val canvas = IntArray(width * height)
    art.copyInto(canvas, 0, 0, width * min(artHeight, height))
    val stripRows = (artHeight * BLEED_SOURCE).roundToInt().coerceIn(2, artHeight)
    val below = height - artHeight
    for (y in artHeight until height) {
        // Position in the flipped strip, where 0 is the cover's last row; Catmull-Rom between the four nearest strip rows.
        val at = (y - artHeight) * (stripRows - 1f) / below
        val base = at.toInt()
        val t = at - base
        val weights =
            floatArrayOf(
                ((-0.5f * t + 1f) * t - 0.5f) * t,
                (1.5f * t - 2.5f) * t * t + 1f,
                ((-1.5f * t + 2f) * t + 0.5f) * t,
                (0.5f * t - 0.5f) * t * t,
            )
        for (x in 0 until width) {
            var red = 0f
            var green = 0f
            var blue = 0f
            for (tap in 0..3) {
                val row = artHeight - 1 - (base - 1 + tap).coerceIn(0, stripRows - 1)
                val p = art[row * width + x]
                red += weights[tap] * ((p shr 16) and 0xFF)
                green += weights[tap] * ((p shr 8) and 0xFF)
                blue += weights[tap] * (p and 0xFF)
            }
            canvas[y * width + x] =
                (0xFF shl 24) or
                (red.roundToInt().coerceIn(0, 255) shl 16) or
                (green.roundToInt().coerceIn(0, 255) shl 8) or
                blue.roundToInt().coerceIn(0, 255)
        }
    }
    return canvas
}

// Box-averages rows [top]..[bottom] by [factor]; the output grid is anchored at [top], so callers keep it on a multiple of [factor].
private fun IntArray.downsampled(
    width: Int,
    height: Int,
    top: Int,
    bottom: Int,
    factor: Int,
): IntArray {
    val outWidth = (width + factor - 1) / factor
    val outHeight = (bottom - top + factor) / factor
    val out = IntArray(outWidth * outHeight)
    for (y in 0 until outHeight) {
        for (x in 0 until outWidth) {
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            for (sy in top + y * factor until min(top + (y + 1) * factor, min(bottom + 1, height))) {
                for (sx in x * factor until min((x + 1) * factor, width)) {
                    val p = this[sy * width + sx]
                    red += (p shr 16) and 0xFF
                    green += (p shr 8) and 0xFF
                    blue += p and 0xFF
                    count++
                }
            }
            out[y * outWidth + x] = (0xFF shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
        }
    }
    return out
}

private fun blurRadiusAt(row: Float): Float {
    if (row <= BLUR_START) return 0f
    return BLUR_RADII.last() * ((row - BLUR_START) / (BLUR_END - BLUR_START)).coerceAtMost(1f).pow(BLUR_CURVE)
}

/**
 * Renders rows [firstRow]..[lastRow] of [canvas]: each row is blended between the two pre-blurred levels nearest its
 * radius, then pulled toward [tone]. [unit] is how many canvas pixels one container width spans.
 */
internal suspend fun renderBleedRows(
    canvas: IntArray,
    width: Int,
    height: Int,
    unit: Float,
    firstRow: Int,
    lastRow: Int,
    tone: Int,
): IntArray {
    val rows = lastRow - firstRow + 1
    val radii = FloatArray(rows) { blurRadiusAt((firstRow + it) / unit) }
    val upper = IntArray(rows) { BLUR_RADII.indexOfFirst { level -> level >= radii[it] } }
    val lower = IntArray(rows) { max(upper[it] - 1, 0) }
    // A level only feeds the rows between its neighbours, so blur just those rows rather than the whole region.
    val usedFirst = IntArray(upper.max() + 1) { Int.MAX_VALUE }
    val usedLast = IntArray(usedFirst.size) { -1 }
    for (row in 0 until rows) {
        for (level in intArrayOf(lower[row], upper[row])) {
            usedFirst[level] = min(usedFirst[level], firstRow + row)
            usedLast[level] = max(usedLast[level], firstRow + row)
        }
    }
    val levels =
        coroutineScope {
            usedFirst.indices
                .map { level ->
                    async(Dispatchers.Default) {
                        val top = usedFirst[level]
                        if (usedLast[level] < 0) {
                            IntArray(0)
                        } else if (level == 0) {
                            canvas.copyOfRange(top * width, (usedLast[level] + 1) * width)
                        } else {
                            blurredRows(canvas, width, height, top, usedLast[level], BLUR_RADII[level] * unit / REFERENCE_WIDTH / 2f)
                        }
                    }
                }.awaitAll()
        }

    val toneRed = (tone shr 16) and 0xFF
    val toneGreen = (tone shr 8) and 0xFF
    val toneBlue = tone and 0xFF
    val out = IntArray(rows * width)
    for (row in 0 until rows) {
        val low = lower[row]
        val high = upper[row]
        val mix = if (high == low) 0f else (radii[row] - BLUR_RADII[low]) / (BLUR_RADII[high] - BLUR_RADII[low])
        val lowerLevel = levels[low]
        val upperLevel = levels[high]
        val lowerStart = (firstRow + row - usedFirst[low]) * width
        val upperStart = (firstRow + row - usedFirst[high]) * width
        val toneMix = TONE_STRENGTH * smootherstep(((firstRow + row) / unit - TONE_START) / TONE_SPAN)
        for (x in 0 until width) {
            val a = lowerLevel[lowerStart + x]
            val b = upperLevel[upperStart + x]
            out[row * width + x] =
                (0xFF shl 24) or
                (towards(blend(a shr 16, b shr 16, mix), toneRed, toneMix) shl 16) or
                (towards(blend(a shr 8, b shr 8, mix), toneGreen, toneMix) shl 8) or
                towards(blend(a, b, mix), toneBlue, toneMix)
        }
    }
    return out
}

private fun blend(
    from: Int,
    to: Int,
    amount: Float,
): Float = (from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * amount

private fun towards(
    value: Float,
    target: Int,
    amount: Float,
): Int = (value + (target - value) * amount).roundToInt().coerceIn(0, 255)

// Blurs only the rows asked for, plus the margin the blur reaches into, so low-radius levels stay cheap. A strong blur
// has no fine detail left to lose, so it runs on a reduced copy and is scaled back up, which makes it far cheaper.
private fun blurredRows(
    canvas: IntArray,
    width: Int,
    height: Int,
    firstRow: Int,
    lastRow: Int,
    sigma: Float,
): IntArray {
    val factor =
        when {
            sigma >= QUARTER_RES_SIGMA -> 4
            sigma >= HALF_RES_SIGMA -> 2
            else -> 1
        }
    // Half-width of each box pass whose combined variance over BLUR_PASSES equals sigma squared.
    val scaled = sigma / factor
    val half = (sqrt(1f + 4f * scaled * scaled) - 1f) / 2f
    val margin = (ceil(BLUR_PASSES * half).toInt() + 1) * factor
    val sliceTop = max(firstRow - margin, 0) / factor * factor
    val sliceBottom = min(lastRow + margin, height - 1)
    val sliceWidth = (width + factor - 1) / factor
    val sliceHeight = (sliceBottom - sliceTop + factor) / factor
    var slice = canvas.downsampled(width, height, sliceTop, sliceBottom, factor)
    repeat(BLUR_PASSES) {
        val horizontal = IntArray(slice.size)
        blurPass(slice, horizontal, lines = sliceHeight, length = sliceWidth, stride = 1, lineStride = sliceWidth, half = half)
        val vertical = IntArray(slice.size)
        blurPass(horizontal, vertical, lines = sliceWidth, length = sliceHeight, stride = sliceWidth, lineStride = 1, half = half)
        slice = vertical
    }
    return upsampledRows(slice, sliceWidth, sliceHeight, width, firstRow - sliceTop, lastRow - sliceTop, factor)
}

// Bilinear: rows [first]..[last] of the full-size image that [small] was reduced from by [factor].
private fun upsampledRows(
    small: IntArray,
    smallWidth: Int,
    smallHeight: Int,
    width: Int,
    first: Int,
    last: Int,
    factor: Int,
): IntArray {
    val left = IntArray(width)
    val right = IntArray(width)
    val rightWeight = FloatArray(width)
    for (x in 0 until width) {
        val at = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, smallWidth - 1f)
        left[x] = at.toInt()
        right[x] = min(left[x] + 1, smallWidth - 1)
        rightWeight[x] = at - left[x]
    }
    val out = IntArray((last - first + 1) * width)
    for (row in first..last) {
        val at = ((row + 0.5f) / factor - 0.5f).coerceIn(0f, smallHeight - 1f)
        val top = at.toInt() * smallWidth
        val bottom = min(at.toInt() + 1, smallHeight - 1) * smallWidth
        val bottomWeight = at - at.toInt()
        for (x in 0 until width) {
            val a = small[top + left[x]]
            val b = small[top + right[x]]
            val c = small[bottom + left[x]]
            val d = small[bottom + right[x]]
            val wx = rightWeight[x]
            out[(row - first) * width + x] =
                (0xFF shl 24) or
                (bilinear(a shr 16, b shr 16, c shr 16, d shr 16, wx, bottomWeight) shl 16) or
                (bilinear(a shr 8, b shr 8, c shr 8, d shr 8, wx, bottomWeight) shl 8) or
                bilinear(a, b, c, d, wx, bottomWeight)
        }
    }
    return out
}

private fun bilinear(
    a: Int,
    b: Int,
    c: Int,
    d: Int,
    x: Float,
    y: Float,
): Int {
    val top = blend(a, b, x)
    val bottom = blend(c, d, x)
    return (top + (bottom - top) * y).roundToInt().coerceIn(0, 255)
}

// Running-sum box blur along one axis with clamped edges; the fractional part of [half] weights the two outer samples
// so the radius, and with it the blur strength, changes continuously.
private fun blurPass(
    src: IntArray,
    dst: IntArray,
    lines: Int,
    length: Int,
    stride: Int,
    lineStride: Int,
    half: Float,
) {
    val whole = half.toInt()
    val edge = half - whole
    val norm = 1f / (2 * whole + 1 + 2f * edge)
    for (line in 0 until lines) {
        val start = line * lineStride
        var red = 0
        var green = 0
        var blue = 0
        for (i in -whole..whole) {
            val p = src[start + i.coerceIn(0, length - 1) * stride]
            red += (p shr 16) and 0xFF
            green += (p shr 8) and 0xFF
            blue += p and 0xFF
        }
        for (i in 0 until length) {
            val before = src[start + max(i - whole - 1, 0) * stride]
            val after = src[start + min(i + whole + 1, length - 1) * stride]
            val r = ((red + edge * (((before shr 16) and 0xFF) + ((after shr 16) and 0xFF))) * norm).roundToInt()
            val g = ((green + edge * (((before shr 8) and 0xFF) + ((after shr 8) and 0xFF))) * norm).roundToInt()
            val b = ((blue + edge * ((before and 0xFF) + (after and 0xFF))) * norm).roundToInt()
            dst[start + i * stride] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            val leaving = src[start + max(i - whole, 0) * stride]
            red += ((after shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            green += ((after shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            blue += (after and 0xFF) - (leaving and 0xFF)
        }
    }
}
