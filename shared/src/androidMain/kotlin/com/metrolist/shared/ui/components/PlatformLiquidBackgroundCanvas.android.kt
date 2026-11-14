package com.metrolist.shared.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import coil3.BitmapImage
import com.metrolist.shared.ui.utils.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

private const val SCRIM_COLOR_1 = 0x80000000.toInt()
private const val SCRIM_COLOR_2 = 0x0DFFFFFF

internal class FastStackBlurBuffers(
    width: Int,
    height: Int,
    radius: Int,
) {
    val wh = width * height
    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    val div = radius + radius + 1
    val divsum = ((div + 1) shr 1) * ((div + 1) shr 1)
    val temp = IntArray(256 * divsum) { it / divsum }
    val stack = IntArray(div * 3)
}

internal fun fastStackBlurInPlace(
    bitmap: Bitmap,
    radius: Int,
    pix: IntArray,
    buffers: FastStackBlurBuffers,
) {
    if (radius < 1) return
    val w = bitmap.width
    val h = bitmap.height
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val div = radius + radius + 1

    val r = buffers.r
    val g = buffers.g
    val b = buffers.b
    val temp = buffers.temp
    val stack = buffers.stack

    var yi = 0

    for (y in 0 until h) {
        val rowStart = y * w
        var rinsum = 0
        var ginsum = 0
        var binsum = 0
        var routsum = 0
        var goutsum = 0
        var boutsum = 0
        var rsum = 0
        var gsum = 0
        var bsum = 0

        for (i in -radius..radius) {
            val p = pix[rowStart + minOf(wm, maxOf(i, 0))]
            val si = (i + radius) * 3
            val pr = (p shr 16) and 0xff
            val pg = (p shr 8) and 0xff
            val pb = p and 0xff
            stack[si] = pr
            stack[si + 1] = pg
            stack[si + 2] = pb
            val rbs = radius + 1 - if (i < 0) -i else i
            rsum += pr * rbs
            gsum += pg * rbs
            bsum += pb * rbs
            if (i > 0) {
                rinsum += pr
                ginsum += pg
                binsum += pb
            } else {
                routsum += pr
                goutsum += pg
                boutsum += pb
            }
        }
        var stackpointer = radius

        for (x in 0 until w) {
            r[yi] = temp[rsum]
            g[yi] = temp[gsum]
            b[yi] = temp[bsum]

            rsum -= routsum
            gsum -= goutsum
            bsum -= boutsum

            var stackstart = stackpointer - radius + div
            if (stackstart >= div) stackstart -= div
            val so = stackstart * 3

            routsum -= stack[so]
            goutsum -= stack[so + 1]
            boutsum -= stack[so + 2]

            val p = pix[rowStart + minOf(x + radius + 1, wm)]
            val pr = (p shr 16) and 0xff
            val pg = (p shr 8) and 0xff
            val pb = p and 0xff
            stack[so] = pr
            stack[so + 1] = pg
            stack[so + 2] = pb

            rinsum += pr
            ginsum += pg
            binsum += pb

            rsum += rinsum
            gsum += ginsum
            bsum += binsum

            stackpointer = if (stackpointer + 1 == div) 0 else stackpointer + 1
            val sp = stackpointer * 3

            routsum += stack[sp]
            goutsum += stack[sp + 1]
            boutsum += stack[sp + 2]

            rinsum -= stack[sp]
            ginsum -= stack[sp + 1]
            binsum -= stack[sp + 2]

            yi++
        }
    }

    for (x in 0 until w) {
        var rinsum = 0
        var ginsum = 0
        var binsum = 0
        var routsum = 0
        var goutsum = 0
        var boutsum = 0
        var rsum = 0
        var gsum = 0
        var bsum = 0

        for (i in -radius..radius) {
            val idx = x + maxOf(0, i) * w
            val si = (i + radius) * 3
            val pr = r[idx]
            val pg = g[idx]
            val pb = b[idx]
            stack[si] = pr
            stack[si + 1] = pg
            stack[si + 2] = pb
            val rbs = radius + 1 - if (i < 0) -i else i
            rsum += pr * rbs
            gsum += pg * rbs
            bsum += pb * rbs
            if (i > 0) {
                rinsum += pr
                ginsum += pg
                binsum += pb
            } else {
                routsum += pr
                goutsum += pg
                boutsum += pb
            }
        }

        yi = x
        var stackpointer = radius
        for (y in 0 until h) {
            pix[yi] = (0xFF shl 24) or (temp[rsum] shl 16) or (temp[gsum] shl 8) or temp[bsum]

            rsum -= routsum
            gsum -= goutsum
            bsum -= boutsum

            var stackstart = stackpointer - radius + div
            if (stackstart >= div) stackstart -= div
            val so = stackstart * 3

            routsum -= stack[so]
            goutsum -= stack[so + 1]
            boutsum -= stack[so + 2]

            val p = x + minOf(y + radius + 1, hm) * w
            val pr = r[p]
            val pg = g[p]
            val pb = b[p]
            stack[so] = pr
            stack[so + 1] = pg
            stack[so + 2] = pb

            rinsum += pr
            ginsum += pg
            binsum += pb

            rsum += rinsum
            gsum += ginsum
            bsum += binsum

            stackpointer = if (stackpointer + 1 == div) 0 else stackpointer + 1
            val sp = stackpointer * 3

            routsum += stack[sp]
            goutsum += stack[sp + 1]
            boutsum += stack[sp + 2]

            rinsum -= stack[sp]
            ginsum -= stack[sp + 1]
            binsum -= stack[sp + 2]

            yi += w
        }
    }

    bitmap.setPixels(pix, 0, w, 0, 0, w, h)
}

private class AndroidLiquidBlurCache(
    val width: Int,
    val height: Int,
    val blurRadius: Int,
) {
    private val bitmapA: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val bitmapB: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val imageA: ImageBitmap = bitmapA.asImageBitmap()
    private val imageB: ImageBitmap = bitmapB.asImageBitmap()
    private var useA = true

    private val canvasA: AndroidCanvas = AndroidCanvas(bitmapA)
    private val canvasB: AndroidCanvas = AndroidCanvas(bitmapB)

    private val pixels = IntArray(width * height)
    private val blurBuffers = FastStackBlurBuffers(width, height, blurRadius)

    private val workMatrix = Matrix()

    private val layerPaint =
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG or AndroidPaint.DITHER_FLAG)
    private val scrimPaint =
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG or AndroidPaint.DITHER_FLAG).apply {
            style = AndroidPaint.Style.FILL
        }
    private val colorMatrix = ColorMatrix()
    private var cachedSaturation: Float = -1f

    private val w = width.toFloat()
    private val h = height.toFloat()
    private val maxDim = (max(w, h) * 1.3f).roundToInt().toFloat()
    private val pivot = maxDim / 2f
    private val offsetX = -(maxDim - w) / 2f
    private val offsetY = -(maxDim - h) / 2f

    private var lastResult: ImageBitmap? = null
    private var lastActive: Bitmap? = null
    private var lastPrevious: Bitmap? = null
    private var lastAlpha: Float = -1f
    private var lastRotation: Long = Long.MIN_VALUE

    fun render(
        activeBitmap: Bitmap?,
        previousBitmap: Bitmap?,
        crossfadeAlpha: Float,
        rotationTimeMs: Long,
        saturation: Float,
    ): ImageBitmap {
        val cached = lastResult
        if (cached != null &&
            activeBitmap === lastActive &&
            previousBitmap === lastPrevious &&
            crossfadeAlpha == lastAlpha &&
            rotationTimeMs == lastRotation &&
            saturation == cachedSaturation
        ) {
            return cached
        }
        lastActive = activeBitmap
        lastPrevious = previousBitmap
        lastAlpha = crossfadeAlpha
        lastRotation = rotationTimeMs

        useA = !useA
        val targetBmp = if (useA) bitmapA else bitmapB
        val targetCanvas = if (useA) canvasA else canvasB

        targetBmp.eraseColor(0)

        if (cachedSaturation != saturation) {
            cachedSaturation = saturation
            colorMatrix.setSaturation(saturation)
            layerPaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        val curRotM = -360f * (rotationTimeMs % 130_000L) / 130_000f
        val curRotN = 360f * (rotationTimeMs % 90_000L) / 90_000f
        val curRotO = 360f * (rotationTimeMs % 70_000L) / 70_000f

        if (previousBitmap != null && crossfadeAlpha < 1f) {
            drawLayers(targetCanvas, previousBitmap, 1f - crossfadeAlpha, curRotM, curRotN, curRotO)
        }
        drawLayers(targetCanvas, activeBitmap, crossfadeAlpha, curRotM, curRotN, curRotO)

        scrimPaint.color = SCRIM_COLOR_1
        targetCanvas.drawPaint(scrimPaint)
        scrimPaint.color = SCRIM_COLOR_2
        targetCanvas.drawPaint(scrimPaint)

        fastStackBlurInPlace(targetBmp, blurRadius, pixels, blurBuffers)

        val result = if (useA) imageA else imageB
        lastResult = result
        return result
    }

    private fun drawLayers(
        targetCanvas: AndroidCanvas,
        srcBitmap: Bitmap?,
        alpha: Float,
        curRotM: Float,
        curRotN: Float,
        curRotO: Float,
    ) {
        if (alpha <= 0f || srcBitmap == null || srcBitmap.isRecycled) return
        val srcH = srcBitmap.height.toFloat()
        if (srcBitmap.width <= 0 || srcH <= 0f) return

        layerPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

        val scale = maxDim / srcH

        workMatrix.setScale(scale, scale)
        workMatrix.postRotate(curRotM, pivot, pivot)
        workMatrix.postTranslate(offsetX, offsetY)
        targetCanvas.drawBitmap(srcBitmap, workMatrix, layerPaint)

        workMatrix.setScale(scale, scale)
        workMatrix.postRotate(curRotN, pivot, pivot)
        workMatrix.postTranslate(offsetX - 0.95f * w, offsetY - 0.7f * h)
        targetCanvas.drawBitmap(srcBitmap, workMatrix, layerPaint)

        workMatrix.setScale(scale, scale)
        workMatrix.postRotate(curRotO, pivot, pivot)
        workMatrix.postTranslate(offsetX - 0.5f * w, offsetY + 0.7f * h)
        workMatrix.postRotate(curRotO, w / 2f, h / 2f)
        targetCanvas.drawBitmap(srcBitmap, workMatrix, layerPaint)
    }
}

private class AndroidLiquidRenderer {
    private var cache: AndroidLiquidBlurCache? = null
    private val shader = if (Build.VERSION.SDK_INT >= 33) ArtworkShaderRenderer() else null

    fun draw(
        scope: DrawScope,
        activeBitmap: Bitmap,
        previousBitmap: Bitmap?,
        crossfadeAlpha: Float,
        rotationTimeMs: Long,
    ) {
        val width = scope.size.width
        val height = scope.size.height
        if (width <= 0f || height <= 0f) return

        if (Build.VERSION.SDK_INT >= 33 && shader?.draw(scope, activeBitmap, previousBitmap, crossfadeAlpha, rotationTimeMs) == true) return

        val maxTargetDim = 128f
        val aspect = width / height
        val scaledW: Int
        val scaledH: Int
        if (aspect >= 1f) {
            scaledW = maxTargetDim.roundToInt()
            scaledH = (maxTargetDim / aspect).roundToInt().coerceAtLeast(1)
        } else {
            scaledW = (maxTargetDim * aspect).roundToInt().coerceAtLeast(1)
            scaledH = maxTargetDim.roundToInt()
        }

        val blurRadius = 20
        val saturation = 2.5f

        var current = cache
        if (current == null || current.width != scaledW || current.height != scaledH || current.blurRadius != blurRadius) {
            current = AndroidLiquidBlurCache(scaledW, scaledH, blurRadius)
            cache = current
        }

        val renderedBitmap =
            current.render(
                activeBitmap = activeBitmap,
                previousBitmap = previousBitmap,
                crossfadeAlpha = crossfadeAlpha,
                rotationTimeMs = rotationTimeMs,
                saturation = saturation,
            )

        val bw = (width * 1.3f).roundToInt()
        val bh = (height * 1.3f).roundToInt()
        val tx = -(bw - width.roundToInt()) / 2
        val ty = -(bh - height.roundToInt()) / 2

        scope.drawImage(
            image = renderedBitmap,
            dstOffset = IntOffset(tx, ty),
            dstSize = IntSize(bw, bh),
            filterQuality = FilterQuality.Low,
        )
    }
}

private fun layerArtToBitmap(
    art: LayerArt,
    size: Int = 128,
): Bitmap {
    val rawBmp: Bitmap? =
        (art.image as? BitmapImage)?.bitmap
            ?: runCatching { art.image?.toImageBitmap()?.asAndroidBitmap() }.getOrNull()
    if (rawBmp != null && !rawBmp.isRecycled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && rawBmp.config == Bitmap.Config.HARDWARE) {
            val copy = runCatching { rawBmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (copy != null && !copy.isRecycled) return copy
        } else {
            return rawBmp
        }
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = ComposeCanvas(AndroidCanvas(bitmap))
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(size.toFloat(), size.toFloat()),
    ) {
        with(art.painter) {
            draw(size = Size(size.toFloat(), size.toFloat()))
        }
    }
    return bitmap
}

@Composable
internal actual fun PlatformLiquidBackgroundCanvas(
    modifier: Modifier,
    activeArt: LayerArt,
    previousArt: LayerArt?,
    crossfadeAlpha: () -> Float,
    rotationTimeMs: () -> Long,
) {
    val renderer = remember { AndroidLiquidRenderer() }
    val activeBitmap = remember(activeArt) { layerArtToBitmap(activeArt) }
    val previousBitmap = remember(previousArt) { previousArt?.let { layerArtToBitmap(it) } }

    Canvas(modifier = modifier.fillMaxSize()) {
        renderer.draw(
            scope = this,
            activeBitmap = activeBitmap,
            previousBitmap = previousBitmap,
            crossfadeAlpha = crossfadeAlpha(),
            rotationTimeMs = rotationTimeMs(),
        )
    }
}

private data class TextureKey(
    val image: Bitmap,
    val radius: Int,
    val liquid: Boolean,
)

@RequiresApi(33)
private class ArtworkShaderRenderer {
    private val shader = runCatching { RuntimeShader(artworkBackgroundShader) }.getOrNull()
    var available by mutableStateOf(shader != null)
        private set
    private val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    private val textures = linkedMapOf<TextureKey, BitmapShader>()

    private fun texture(
        image: Bitmap,
        radius: Int,
        liquid: Boolean,
    ): BitmapShader {
        val key = TextureKey(image, radius, liquid)
        textures.remove(key)?.let {
            textures[key] = it
            return it
        }
        val pad = ARTWORK_TEXTURE_PADDING.toFloat()
        val size = ARTWORK_TEXTURE_SIZE.toFloat()
        if (image.width <= 64 && image.height <= 64) {
            val pad = ARTWORK_TEXTURE_PADDING.toFloat()
            val size = ARTWORK_TEXTURE_SIZE.toFloat()
            val w = image.width
            val h = image.height
            val blurredImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            blurArtworkPixels(pixels, w, h, 6, 6)
            blurredImage.setPixels(pixels, 0, w, 0, 0, w, h)
            val fastShader =
                BitmapShader(blurredImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(
                        Matrix().apply {
                            setScale(size / w, size / h)
                            postTranslate(pad, pad)
                        },
                    )
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                }
            if (textures.size >= 4) textures.remove(textures.keys.first())
            textures[key] = fastShader
            return fastShader
        }
        val side = ARTWORK_TEXTURE_SIZE + ARTWORK_TEXTURE_PADDING * 2
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val layer = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG)
        if (liquid) layer.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2.5f) })
        if (liquid) {
            canvas.drawBitmap(image, null, RectF(pad, pad, pad + size, pad + size), layer)
        } else {
            layer.shader =
                BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(
                        Matrix().apply {
                            setScale(size / image.width, size / image.height)
                            postTranslate(pad, pad)
                        },
                    )
                }
            canvas.drawPaint(layer)
        }
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
        blurArtworkPixels(pixels, side, side, radius, (radius.toFloat() * image.height / image.width).roundToInt().coerceAtLeast(1))
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
        val result =
            BitmapShader(
                bitmap,
                Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP,
            ).apply { setFilterMode(BitmapShader.FILTER_MODE_LINEAR) }
        if (textures.size >= 4) textures.remove(textures.keys.first())
        textures[key] = result
        return result
    }

    fun draw(
        scope: DrawScope,
        active: Bitmap,
        previous: Bitmap?,
        alpha: Float,
        time: Long,
        style: ArtworkBackdropStyle? = null,
    ): Boolean {
        val shader = shader ?: return false
        if (!available || scope.size.width <= 0f || scope.size.height <= 0f) return false
        return try {
            val w = scope.size.width
            val h = scope.size.height
            val ratio = active.width.toFloat() / active.height
            val crop = artworkCrop(w, h, ratio, style ?: ArtworkBackdropStyle(0f))
            val radius = if (style == null) 7 else artworkBlurBucket(style.blurRadiusPx, crop[3])
            val activeTexture = texture(active, radius, style == null)
            val previousTexture =
                previous?.let { texture(it, radius, style == null) } ?: activeTexture
            shader.setInputShader("artwork", activeTexture)
            shader.setInputShader("previous", previousTexture)
            shader.setFloatUniform("viewport", w, h)
            shader.setFloatUniform("ratios", ratio, previous?.let { it.width.toFloat() / it.height } ?: ratio)
            val angles = liquidRotationAngles(time)
            shader.setFloatUniform("cosines", cos(angles[0]), cos(angles[1]), cos(angles[2]))
            shader.setFloatUniform("sines", sin(angles[0]), sin(angles[1]), sin(angles[2]))
            shader.setFloatUniform("crossfade", if (previous == null) 1f else alpha)
            shader.setFloatUniform("liquid", if (style == null) 1f else 0f)
            shader.setFloatUniform("crop", crop)
            shader.setFloatUniform("treatment", style?.scrimAlpha ?: 0f, if (style?.lyricsGradient == true) 1f else 0f, 0f, 0f)
            shader.setFloatUniform("mirror", (style?.mirrorSeam ?: 0f) * h, (style?.mirrorFadeStart ?: 0f) * h)
            paint.shader = shader
            scope.drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, w, h, paint) }
            true
        } catch (_: Exception) {
            available = false
            false
        }
    }
}

@Composable
internal actual fun PlatformArtworkBackgroundCanvas(
    artwork: ImageBitmap,
    modifier: Modifier,
    style: ArtworkBackdropStyle,
) {
    val renderer = remember { if (Build.VERSION.SDK_INT >= 33) ArtworkShaderRenderer() else null }
    val bitmap =
        remember(artwork) {
            artwork.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        }
    if (Build.VERSION.SDK_INT >= 33 && renderer?.available == true) {
        Canvas(modifier.fillMaxSize()) { renderer.draw(this, bitmap, null, 1f, 0L, style) }
    } else if (Build.VERSION.SDK_INT < 31 && style.blurRadiusPx > 0f) {
        BoxWithConstraints(modifier) {
            val density = LocalDensity.current
            val cropHeight =
                artworkCrop(
                    with(density) { maxWidth.toPx() },
                    with(density) { maxHeight.toPx() },
                    bitmap.width.toFloat() / bitmap.height,
                    style,
                )[3]
            val imageSide = max(bitmap.width, bitmap.height)
            val scaledHeight = (ARTWORK_TEXTURE_SIZE.toFloat() * bitmap.height / imageSide).roundToInt().coerceAtLeast(1)
            val radius = artworkLegacyBlurRadius(style.blurRadiusPx, cropHeight, scaledHeight)
            var blurred by remember(artwork) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(artwork, radius) {
                blurred =
                    withContext(Dispatchers.Default) {
                        val scale = ARTWORK_TEXTURE_SIZE.toFloat() / imageSide
                        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true).copy(Bitmap.Config.ARGB_8888, true)
                        val pixels = IntArray(width * height)
                        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                        blurArtworkPixels(pixels, width, height, radius)
                        scaled.setPixels(pixels, 0, width, 0, 0, width, height)
                        scaled.asImageBitmap()
                    }
            }
            LegacyArtworkBackground(
                blurred ?: artwork,
                Modifier.fillMaxSize(),
                if (blurred == null) style.copy(scrimAlpha = style.pendingScrimAlpha) else style,
            )
        }
    } else {
        LegacyArtworkBackground(artwork, modifier, style)
    }
}
