package com.metrolist.shared.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaImageFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.ceil
import org.jetbrains.skia.Canvas as SkiaCanvas

data class ArtworkBlurStyle(
    val radius: Dp,
    val backgroundColor: Color,
    val scale: Float = 1f,
    val edgeTreatment: BlurredEdgeTreatment = BlurredEdgeTreatment.Rectangle,
    val scrimColor: Color = Color.Transparent,
)

actual fun Modifier.artworkBlur(
    radius: Dp,
    backgroundColor: Color,
): Modifier = cachedArtworkBlur(ArtworkBlurStyle(radius, backgroundColor), RectangleShape)

fun Modifier.cachedArtworkBlur(
    style: ArtworkBlurStyle,
    shape: Shape,
): Modifier = graphicsLayer().then(ArtworkBlurElement(style, shape))

private data class ArtworkBlurElement(
    val style: ArtworkBlurStyle,
    val shape: Shape,
) : ModifierNodeElement<ArtworkBlurNode>() {
    override fun create() = ArtworkBlurNode(this)

    override fun update(node: ArtworkBlurNode) {
        node.parameters = this
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "artworkBlur"
        properties["radius"] = style.radius
    }
}

private class ArtworkBlurNode(
    var parameters: ArtworkBlurElement,
) : Modifier.Node(),
    DrawModifierNode {
    private var snapshot: Image? = null

    override fun ContentDrawScope.draw() {
        if (size.width <= 0f || size.height <= 0f) return
        snapshot?.close()
        snapshot = null
        // The outer layer records this bitmap until artwork, theme, size or density invalidates it.
        val image =
            rasterizeArtworkBlur(
                size.width,
                size.height,
                parameters.style.radius.toPx(),
                parameters.style.scale,
                parameters.style.edgeTreatment == BlurredEdgeTreatment.Unbounded,
                parameters.style.backgroundColor.toArgb(),
                parameters.style.scrimColor.toArgb(),
            ) { canvas ->
                val previousCanvas = drawContext.canvas
                try {
                    drawContext.canvas = canvas.asComposeCanvas()
                    val path =
                        Path().apply {
                            when (val outline = parameters.shape.createOutline(size, layoutDirection, this@draw)) {
                                is Outline.Rectangle -> addRect(outline.rect)
                                is Outline.Rounded -> addRoundRect(outline.roundRect)
                                is Outline.Generic -> addPath(outline.path)
                            }
                        }
                    drawContext.canvas.save()
                    drawContext.canvas.clipPath(path)
                    drawContent()
                    drawContext.canvas.restore()
                } finally {
                    drawContext.canvas = previousCanvas
                }
            }
        snapshot = image
        drawIntoCanvas {
            it.skiaCanvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(size.width, size.height),
                SamplingMode.LINEAR,
                null,
                true,
            )
        }
    }

    override fun onDetach() {
        snapshot?.close()
        snapshot = null
    }
}

internal fun rasterizeArtworkBlur(
    width: Float,
    height: Float,
    radiusPx: Float,
    scale: Float,
    unbounded: Boolean,
    backgroundColor: Int,
    scrimColor: Int = 0,
    drawArtwork: (SkiaCanvas) -> Unit,
): Image {
    val downscale = artworkBlurDownscale(radiusPx)
    val rasterWidth = ceil(width / downscale).toInt().coerceAtLeast(1)
    val rasterHeight = ceil(height / downscale).toInt().coerceAtLeast(1)
    return Surface.makeRasterN32Premul(rasterWidth, rasterHeight).use { surface ->
        val canvas = surface.canvas
        // Blurring over transparency adds a rounding step when the cached image is composited.
        canvas.clear(backgroundColor)
        // Map the bounds onto whole raster pixels, so no partly covered edge column reaches the clamped blur.
        // Skia scales the blur sigma with the canvas, so the result matches a full-size raster.
        canvas.scale(rasterWidth / width, rasterHeight / height)
        val bounds = Rect.makeWH(width, height)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-width / 2f, -height / 2f)
        if (!unbounded) canvas.clipRect(bounds)
        BlurEffect(radiusPx, radiusPx, if (unbounded) TileMode.Decal else TileMode.Clamp).skiaImageFilter.use { filter ->
            Paint().use { paint ->
                paint.imageFilter = filter
                canvas.saveLayer(bounds, paint)
                try {
                    drawArtwork(canvas)
                } finally {
                    canvas.restore()
                }
            }
        }
        canvas.restore()
        if (scrimColor != 0) {
            Paint().use { paint ->
                paint.color = scrimColor
                canvas.drawRect(bounds, paint)
            }
        }
        surface.makeImageSnapshot()
    }
}

/**
 * A large blur removes all detail finer than its radius, so the cached raster can hold one pixel per
 * several screen pixels. Capped at 4, which cuts a 4K snapshot's pixel memory to one sixteenth.
 */
internal fun artworkBlurDownscale(radiusPx: Float): Float = (radiusPx / 16f).coerceIn(1f, 4f)
