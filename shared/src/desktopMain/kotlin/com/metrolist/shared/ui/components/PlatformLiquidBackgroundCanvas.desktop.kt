package com.metrolist.shared.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil3.toBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.ColorFilter as SkiaColorFilter
import org.jetbrains.skia.ColorMatrix as SkiaColorMatrix

private const val SCRIM_COLOR_1 = 0x80000000.toInt()
private const val SCRIM_COLOR_2 = 0x0DFFFFFF

private class DesktopArtImageSlot : AutoCloseable {
    private var art: LayerArt? = null
    private var image: Image? = null

    fun resolve(target: LayerArt?): Image? {
        if (target === art) return image
        image?.close()
        art = target
        image =
            target?.let { layer ->
                layer.image?.let { img ->
                    runCatching {
                        val skiaBitmap = (img as? coil3.BitmapImage)?.bitmap ?: img.toBitmap()
                        if (!skiaBitmap.isImmutable) {
                            skiaBitmap.setImmutable()
                        }
                        Image.makeFromBitmap(skiaBitmap)
                    }.getOrNull()
                } ?: runCatching {
                    val size = 128
                    val bitmap = ImageBitmap(size, size)
                    val canvas = ComposeCanvas(bitmap)
                    CanvasDrawScope().draw(
                        density = Density(1f),
                        layoutDirection = LayoutDirection.Ltr,
                        canvas = canvas,
                        size = Size(size.toFloat(), size.toFloat()),
                    ) {
                        with(layer.painter) {
                            draw(size = Size(size.toFloat(), size.toFloat()))
                        }
                    }
                    Image.makeFromBitmap(bitmap.asSkiaBitmap())
                }.getOrNull()
            }
        return image
    }

    override fun close() {
        image?.close()
        image = null
        art = null
    }
}

private class DesktopLiquidBlurCache(
    val width: Int,
    val height: Int,
    val blurRadius: Float,
) : AutoCloseable {
    private val surface: Surface = Surface.makeRasterN32Premul(width, height)
    private val blurFilter: ImageFilter = ImageFilter.makeBlur(blurRadius, blurRadius, FilterTileMode.CLAMP)
    private val layerPaint: Paint = Paint()
    private val blurPaint: Paint = Paint().apply { imageFilter = blurFilter }
    private val scrimPaint: Paint = Paint().apply { mode = PaintMode.FILL }

    val srcRect: Rect = Rect.makeWH(width.toFloat(), height.toFloat())

    private val w = width.toFloat()
    private val h = height.toFloat()
    private val maxDim = (max(w, h) * 1.3f).roundToInt().toFloat()
    private val pivot = maxDim / 2f
    private val offsetX = -(maxDim - w) / 2f
    private val offsetY = -(maxDim - h) / 2f

    private var cachedSaturation: Float = -1f
    private var lastImage: Image? = null
    private var lastActive: Image? = null
    private var lastPrevious: Image? = null
    private var lastAlpha: Float = -1f
    private var lastRotation: Long = Long.MIN_VALUE

    fun render(
        activeImage: Image?,
        previousImage: Image?,
        crossfadeAlpha: Float,
        rotationTimeMs: Long,
        saturation: Float,
    ): Image {
        val cached = lastImage
        if (cached != null &&
            activeImage === lastActive &&
            previousImage === lastPrevious &&
            crossfadeAlpha == lastAlpha &&
            rotationTimeMs == lastRotation &&
            saturation == cachedSaturation
        ) {
            return cached
        }
        lastActive = activeImage
        lastPrevious = previousImage
        lastAlpha = crossfadeAlpha
        lastRotation = rotationTimeMs

        if (cachedSaturation != saturation) {
            cachedSaturation = saturation
            val matrix = ComposeColorMatrix().apply { setToSaturation(saturation) }
            layerPaint.colorFilter?.close()
            layerPaint.colorFilter = SkiaColorFilter.makeMatrix(SkiaColorMatrix(matrix.values))
        }

        val canvas = surface.canvas
        canvas.clear(0)

        val curRotM = -360f * (rotationTimeMs % 130_000L) / 130_000f
        val curRotN = 360f * (rotationTimeMs % 90_000L) / 90_000f
        val curRotO = 360f * (rotationTimeMs % 70_000L) / 70_000f

        val layerCount = canvas.saveLayer(null, blurPaint)

        if (previousImage != null && crossfadeAlpha < 1f) {
            drawLayers(canvas, previousImage, 1f - crossfadeAlpha, curRotM, curRotN, curRotO)
        }
        if (activeImage != null) {
            drawLayers(canvas, activeImage, crossfadeAlpha, curRotM, curRotN, curRotO)
        }

        scrimPaint.color = SCRIM_COLOR_1
        canvas.drawPaint(scrimPaint)
        scrimPaint.color = SCRIM_COLOR_2
        canvas.drawPaint(scrimPaint)

        canvas.restoreToCount(layerCount)

        lastImage?.close()
        val snapshot = surface.makeImageSnapshot()
        lastImage = snapshot
        return snapshot
    }

    private fun drawLayers(
        canvas: SkiaCanvas,
        srcImage: Image,
        alpha: Float,
        curRotM: Float,
        curRotN: Float,
        curRotO: Float,
    ) {
        if (alpha <= 0f) return
        val srcH = srcImage.height.toFloat()
        if (srcImage.width <= 0 || srcH <= 0f) return

        layerPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

        val scale = maxDim / srcH

        val count1 = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.rotate(curRotM, pivot, pivot)
        canvas.scale(scale, scale)
        canvas.drawImage(srcImage, 0f, 0f, layerPaint)
        canvas.restoreToCount(count1)

        val count2 = canvas.save()
        canvas.translate(offsetX - 0.95f * w, offsetY - 0.7f * h)
        canvas.rotate(curRotN, pivot, pivot)
        canvas.scale(scale, scale)
        canvas.drawImage(srcImage, 0f, 0f, layerPaint)
        canvas.restoreToCount(count2)

        val count3 = canvas.save()
        canvas.rotate(curRotO, w / 2f, h / 2f)
        canvas.translate(offsetX - 0.5f * w, offsetY + 0.7f * h)
        canvas.rotate(curRotO, pivot, pivot)
        canvas.scale(scale, scale)
        canvas.drawImage(srcImage, 0f, 0f, layerPaint)
        canvas.restoreToCount(count3)
    }

    override fun close() {
        lastImage?.close()
        lastImage = null
        surface.close()
        blurFilter.close()
        blurPaint.close()
        layerPaint.colorFilter?.close()
        layerPaint.close()
        scrimPaint.close()
    }
}

private class DesktopLiquidRenderer : RememberObserver {
    private var cache: DesktopLiquidBlurCache? = null
    private val activeSlot = DesktopArtImageSlot()
    private val previousSlot = DesktopArtImageSlot()
    private val shader = ArtworkShaderRenderer()

    fun draw(
        scope: DrawScope,
        activeArt: LayerArt,
        previousArt: LayerArt?,
        crossfadeAlpha: Float,
        rotationTimeMs: Long,
    ) {
        val width = scope.size.width
        val height = scope.size.height
        if (width <= 0f || height <= 0f) return

        if (activeSlot
                .resolve(
                    activeArt,
                )?.let { shader.draw(scope, it, previousSlot.resolve(previousArt), crossfadeAlpha, rotationTimeMs) } ==
            true
        ) {
            return
        }

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

        val blurRadius = 14f
        val saturation = 2.5f

        var current = cache
        if (current == null || current.width != scaledW || current.height != scaledH || current.blurRadius != blurRadius) {
            current?.close()
            current = DesktopLiquidBlurCache(scaledW, scaledH, blurRadius)
            cache = current
        }

        val renderedImage =
            current.render(
                activeImage = activeSlot.resolve(activeArt),
                previousImage = previousSlot.resolve(previousArt),
                crossfadeAlpha = crossfadeAlpha,
                rotationTimeMs = rotationTimeMs,
                saturation = saturation,
            )

        val bw = (width * 1.3f).roundToInt()
        val bh = (height * 1.3f).roundToInt()
        val tx = -(bw - width.roundToInt()) / 2
        val ty = -(bh - height.roundToInt()) / 2

        scope.drawIntoCanvas { canvas ->
            canvas.skiaCanvas.drawImageRect(
                renderedImage,
                current.srcRect,
                Rect.makeXYWH(tx.toFloat(), ty.toFloat(), bw.toFloat(), bh.toFloat()),
                SamplingMode.LINEAR,
                null,
                true,
            )
        }
    }

    private fun release() {
        cache?.close()
        cache = null
        shader.onForgotten()
        activeSlot.close()
        previousSlot.close()
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = release()

    override fun onAbandoned() = release()
}

@Composable
internal actual fun PlatformLiquidBackgroundCanvas(
    modifier: Modifier,
    activeArt: LayerArt,
    previousArt: LayerArt?,
    crossfadeAlpha: () -> Float,
    rotationTimeMs: () -> Long,
) {
    val renderer = remember { DesktopLiquidRenderer() }
    Canvas(modifier = modifier.fillMaxSize()) {
        renderer.draw(
            scope = this,
            activeArt = activeArt,
            previousArt = previousArt,
            crossfadeAlpha = crossfadeAlpha(),
            rotationTimeMs = rotationTimeMs(),
        )
    }
}

private data class TextureKey(
    val image: Image,
    val radius: Int,
    val liquid: Boolean,
)

private class BlurredTexture(
    val image: Image,
) : AutoCloseable {
    val shader = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)

    override fun close() {
        shader.close()
        image.close()
    }
}

private class ArtworkShaderRenderer : RememberObserver {
    private val effect = runCatching { RuntimeEffect.makeForShader(artworkBackgroundShader) }.getOrNull()
    private val builder = effect?.let { RuntimeShaderBuilder(it) }
    private val paint = Paint()
    private val textures = mutableListOf<Pair<TextureKey, BlurredTexture>>()
    var available by mutableStateOf(builder != null)
        private set

    private fun texture(
        image: Image,
        radius: Int,
        liquid: Boolean,
    ): BlurredTexture {
        val key = TextureKey(image, radius, liquid)
        // Native Image hashes change on close; compare source identity without hash keys.
        val index =
            textures.indexOfFirst { (cached, _) ->
                cached.image === image && cached.radius == radius && cached.liquid == liquid
            }
        if (index >= 0) {
            val cached = textures.removeAt(index)
            textures.add(cached)
            return cached.second
        }
        val pad = ARTWORK_TEXTURE_PADDING.toFloat()
        val size = ARTWORK_TEXTURE_SIZE.toFloat()
        val side = ARTWORK_TEXTURE_SIZE + ARTWORK_TEXTURE_PADDING * 2
        val result =
            Surface.makeRasterN32Premul(side, side).use { surface ->
                surface.canvas.clear(0)
                Paint().use { layer ->
                    if (liquid) {
                        val matrix = ComposeColorMatrix().apply { setToSaturation(2.5f) }
                        layer.colorFilter = SkiaColorFilter.makeMatrix(SkiaColorMatrix(matrix.values))
                    }
                    if (!liquid) {
                        image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR).use { shader ->
                            layer.shader = shader
                            surface.canvas.save()
                            surface.canvas.translate(pad, pad)
                            surface.canvas.scale(size / image.width, size / image.height)
                            surface.canvas.drawPaint(layer)
                            surface.canvas.restore()
                            layer.shader = null
                        }
                    } else {
                        surface.canvas.drawImageRect(
                            image,
                            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                            Rect.makeXYWH(pad, pad, size, size),
                            SamplingMode.LINEAR,
                            layer,
                            true,
                        )
                    }
                    layer.colorFilter?.close()
                    layer.colorFilter = null
                }
                surface.makeImageSnapshot().use { source ->
                    surface.canvas.clear(0)
                    ImageFilter
                        .makeBlur(
                            radius.toFloat() * image.height / image.width,
                            radius.toFloat(),
                            FilterTileMode.CLAMP,
                        ).use { filter ->
                            Paint().use { blur ->
                                blur.imageFilter = filter
                                surface.canvas.drawImage(source, 0f, 0f, blur)
                            }
                        }
                    BlurredTexture(surface.makeImageSnapshot())
                }
            }
        if (textures.size >= 4) textures.removeAt(0).second.close()
        textures.add(key to result)
        return result
    }

    fun draw(
        scope: DrawScope,
        active: Image,
        previous: Image?,
        alpha: Float,
        time: Long,
        style: ArtworkBackdropStyle? = null,
    ): Boolean {
        val builder = builder ?: return false
        if (!available || scope.size.width <= 0f || scope.size.height <= 0f) return false
        return try {
            val w = scope.size.width
            val h = scope.size.height
            val ratio = active.width.toFloat() / active.height
            val crop = artworkCrop(w, h, ratio, style ?: ArtworkBackdropStyle(0f))
            val radius = if (style == null) 11 else artworkBlurBucket(style.blurRadiusPx, crop[3])
            val activeTexture = texture(active, radius, style == null)
            val previousTexture =
                previous?.let { texture(it, radius, style == null) } ?: activeTexture
            builder.child("artwork", activeTexture.shader)
            builder.child("previous", previousTexture.shader)
            builder.uniform("viewport", w, h)
            builder.uniform("ratios", ratio, previous?.let { it.width.toFloat() / it.height } ?: ratio)
            val angles = liquidRotationAngles(time)
            builder.uniform("cosines", cos(angles[0]), cos(angles[1]), cos(angles[2]))
            builder.uniform("sines", sin(angles[0]), sin(angles[1]), sin(angles[2]))
            builder.uniform("crossfade", if (previous == null) 1f else alpha)
            builder.uniform("liquid", if (style == null) 1f else 0f)
            builder.uniform("crop", crop)
            builder.uniform("treatment", style?.scrimAlpha ?: 0f, if (style?.lyricsGradient == true) 1f else 0f, 0f, 0f)
            builder.uniform("mirror", (style?.mirrorSeam ?: 0f) * h, (style?.mirrorFadeStart ?: 0f) * h)
            builder.makeShader().use { shader ->
                paint.shader = shader
                scope.drawIntoCanvas { it.skiaCanvas.drawRect(Rect.makeWH(w, h), paint) }
                paint.shader = null
            }
            true
        } catch (_: Exception) {
            available = false
            false
        }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = close()

    override fun onAbandoned() = close()

    private fun close() {
        textures.forEach { it.second.close() }
        textures.clear()
        paint.close()
        builder?.close()
        effect?.close()
    }
}

@Composable
internal actual fun PlatformArtworkBackgroundCanvas(
    artwork: ImageBitmap,
    modifier: Modifier,
    style: ArtworkBackdropStyle,
) {
    val renderer = remember { ArtworkShaderRenderer() }
    val image = remember(artwork) { artwork.asSkiaBitmap().let { Image.makeFromBitmap(it) } }
    DisposableEffect(image) { onDispose { image.close() } }
    if (renderer.available) {
        Canvas(modifier.fillMaxSize()) { renderer.draw(this, image, null, 1f, 0L, style) }
    } else {
        LegacyArtworkBackground(artwork, modifier, style)
    }
}
