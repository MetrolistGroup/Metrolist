package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.playback.VideoColorRange
import com.metrolist.shared.playback.VideoColorSpace
import com.metrolist.shared.playback.VideoFrame
import com.metrolist.shared.playback.VideoPixelFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

private const val YUV420P_SKSL = """
uniform shader yTex;
uniform shader uTex;
uniform shader vTex;
uniform int colorSpace;
uniform int colorRange;

half4 main(vec2 coord) {
    float y = max(yTex.eval(coord).r, yTex.eval(coord).a);
    float u = max(uTex.eval(coord * 0.5).r, uTex.eval(coord * 0.5).a);
    float v = max(vTex.eval(coord * 0.5).r, vTex.eval(coord * 0.5).a);

    if (colorRange == 0) {
        y = (y - 0.062745098) * 1.164383562;
        u = (u - 0.501960784) * 1.138392857;
        v = (v - 0.501960784) * 1.138392857;
    } else {
        u = u - 0.5;
        v = v - 0.5;
    }

    vec3 rgb;
    if (colorSpace == 1) {
        rgb.r = y + 1.402 * v;
        rgb.g = y - 0.344136 * u - 0.714136 * v;
        rgb.b = y + 1.772 * u;
    } else if (colorSpace == 2) {
        rgb.r = y + 1.4746 * v;
        rgb.g = y - 0.164553 * u - 0.571353 * v;
        rgb.b = y + 1.8814 * u;
    } else {
        rgb.r = y + 1.5748 * v;
        rgb.g = y - 0.187324 * u - 0.468124 * v;
        rgb.b = y + 1.8556 * u;
    }

    return half4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

private const val NV12_SKSL = """
uniform shader yTex;
uniform shader uvTex;
uniform int colorSpace;
uniform int colorRange;

half4 main(vec2 coord) {
    float y = yTex.eval(coord).r;
    vec2 uv = uvTex.eval(coord * 0.5).rg;
    float u = uv.r;
    float v = uv.g;

    if (colorRange == 0) {
        y = (y - 0.062745098) * 1.164383562;
        u = (u - 0.501960784) * 1.138392857;
        v = (v - 0.501960784) * 1.138392857;
    } else {
        u = u - 0.5;
        v = v - 0.5;
    }

    vec3 rgb;
    if (colorSpace == 1) {
        rgb.r = y + 1.402 * v;
        rgb.g = y - 0.344136 * u - 0.714136 * v;
        rgb.b = y + 1.772 * u;
    } else if (colorSpace == 2) {
        rgb.r = y + 1.4746 * v;
        rgb.g = y - 0.164553 * u - 0.571353 * v;
        rgb.b = y + 1.8814 * u;
    } else {
        rgb.r = y + 1.5748 * v;
        rgb.g = y - 0.187324 * u - 0.468124 * v;
        rgb.b = y + 1.8556 * u;
    }

    return half4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

private val yuv420pEffect by lazy {
    RuntimeEffect.makeForShader(YUV420P_SKSL)
}

private val nv12Effect by lazy {
    RuntimeEffect.makeForShader(NV12_SKSL)
}

private val filterClamp = FilterTileMode.CLAMP
private val linearSampling = SamplingMode.LINEAR

@Composable
fun VideoRenderer(
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    frameProvider: () -> VideoFrame? = { PlayerState.videoFrame },
    onViewportSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
) {
    val renderPaint = remember { Paint() }
    val yuvBuilder = remember { RuntimeShaderBuilder(yuv420pEffect) }
    val nv12Builder = remember { RuntimeShaderBuilder(nv12Effect) }

    DisposableEffect(renderPaint, yuvBuilder, nv12Builder) {
        onDispose {
            renderPaint.close()
            yuvBuilder.close()
            nv12Builder.close()
        }
    }

    Spacer(
        modifier =
            modifier
                .onSizeChanged { onViewportSizeChanged(it.width, it.height) }
                .drawBehind {
                    val frame = frameProvider() ?: return@drawBehind
                    frame.drawSafely frameScope@{
                        val srcW = frame.width.toFloat()
                        val srcH = frame.height.toFloat()
                        if (srcW <= 0f || srcH <= 0f) return@frameScope

                        val scale =
                            if (contentScale == ContentScale.Crop) {
                                maxOf(size.width / srcW, size.height / srcH)
                            } else {
                                minOf(size.width / srcW, size.height / srcH)
                            }

                        val dstW = (srcW * scale).roundToInt()
                        val dstH = (srcH * scale).roundToInt()
                        val offsetX = ((size.width - dstW) / 2f).roundToInt()
                        val offsetY = ((size.height - dstH) / 2f).roundToInt()

                        drawIntoCanvas { canvas ->
                            val skia = canvas.skiaCanvas
                            val csUniform =
                                when (frame.colorSpace) {
                                    VideoColorSpace.BT601 -> 1
                                    VideoColorSpace.BT2020 -> 2
                                    VideoColorSpace.BT709 -> 0
                                }
                            val crUniform = if (frame.colorRange == VideoColorRange.FULL) 1 else 0

                            try {
                                when (frame.format) {
                                    VideoPixelFormat.NV12 -> {
                                        val yImage = frame.planeY ?: return@drawIntoCanvas
                                        val uvImage = frame.planeUV ?: return@drawIntoCanvas
                                        yImage.makeShader(filterClamp, filterClamp, linearSampling).use { yShader ->
                                            uvImage.makeShader(filterClamp, filterClamp, linearSampling).use { uvShader ->
                                                nv12Builder.child("yTex", yShader)
                                                nv12Builder.child("uvTex", uvShader)
                                                nv12Builder.uniform("colorSpace", csUniform)
                                                nv12Builder.uniform("colorRange", crUniform)
                                                nv12Builder.makeShader().use { shader ->
                                                    renderPaint.shader = shader
                                                    val count = skia.save()
                                                    try {
                                                        skia.translate(offsetX.toFloat(), offsetY.toFloat())
                                                        skia.scale(scale, scale)
                                                        skia.drawRect(Rect.makeWH(srcW, srcH), renderPaint)
                                                    } finally {
                                                        skia.restoreToCount(count)
                                                        renderPaint.shader = null
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    VideoPixelFormat.YUV420P -> {
                                        val yImage = frame.planeY ?: return@drawIntoCanvas
                                        val uImage = frame.planeU ?: return@drawIntoCanvas
                                        val vImage = frame.planeV ?: return@drawIntoCanvas
                                        yImage.makeShader(filterClamp, filterClamp, linearSampling).use { yShader ->
                                            uImage.makeShader(filterClamp, filterClamp, linearSampling).use { uShader ->
                                                vImage.makeShader(filterClamp, filterClamp, linearSampling).use { vShader ->
                                                    yuvBuilder.child("yTex", yShader)
                                                    yuvBuilder.child("uTex", uShader)
                                                    yuvBuilder.child("vTex", vShader)
                                                    yuvBuilder.uniform("colorSpace", csUniform)
                                                    yuvBuilder.uniform("colorRange", crUniform)
                                                    yuvBuilder.makeShader().use { shader ->
                                                        renderPaint.shader = shader
                                                        val count = skia.save()
                                                        try {
                                                            skia.translate(offsetX.toFloat(), offsetY.toFloat())
                                                            skia.scale(scale, scale)
                                                            skia.drawRect(Rect.makeWH(srcW, srcH), renderPaint)
                                                        } finally {
                                                            skia.restoreToCount(count)
                                                            renderPaint.shader = null
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    VideoPixelFormat.BGRA -> {
                                        val bitmap = frame.bgraBitmap ?: return@drawIntoCanvas
                                        Image.makeFromBitmap(bitmap).use { image ->
                                            skia.drawImageRect(
                                                image,
                                                Rect.makeWH(srcW, srcH),
                                                Rect.makeXYWH(offsetX.toFloat(), offsetY.toFloat(), dstW.toFloat(), dstH.toFloat()),
                                                SamplingMode.LINEAR,
                                                null,
                                                true,
                                            )
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                },
    )
}
