package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
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
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
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
    Spacer(
        modifier =
            modifier
                .onSizeChanged { onViewportSizeChanged(it.width, it.height) }
                .drawBehind {
                    val frame = frameProvider() ?: return@drawBehind
                    drawIntoCanvas { canvas ->
                        drawVideoFrame(frame, canvas.skiaCanvas, size.width, size.height, contentScale)
                    }
                },
    )
}

internal fun drawVideoFrame(
    frame: VideoFrame,
    canvas: Canvas,
    width: Float,
    height: Float,
    contentScale: ContentScale,
): Boolean {
    if (frame.width <= 0 || frame.height <= 0 || width <= 0f || height <= 0f) return false
    return try {
        frame.drawSafely {
            val srcW = frame.width.toFloat()
            val srcH = frame.height.toFloat()
            val scale =
                if (contentScale == ContentScale.Crop) {
                    maxOf(width / srcW, height / srcH)
                } else {
                    minOf(width / srcW, height / srcH)
                }
            val dstW = (srcW * scale).roundToInt()
            val dstH = (srcH * scale).roundToInt()
            val offsetX = ((width - dstW) / 2f).roundToInt()
            val offsetY = ((height - dstH) / 2f).roundToInt()
            val csUniform =
                when (frame.colorSpace) {
                    VideoColorSpace.BT601 -> 1
                    VideoColorSpace.BT2020 -> 2
                    VideoColorSpace.BT709 -> 0
                }
            val crUniform = if (frame.colorRange == VideoColorRange.FULL) 1 else 0

            // A queued draw can outlive composition. Native drawing resources belong to this draw.
            Paint().use { paint ->
                when (frame.format) {
                    VideoPixelFormat.NV12 -> {
                        val y = requireNotNull(frame.planeY)
                        val uv = requireNotNull(frame.planeUV)
                        RuntimeShaderBuilder(nv12Effect).use { builder ->
                            frame.withVideoShader(y) { yShader ->
                                frame.withVideoShader(uv) { uvShader ->
                                    builder.child("yTex", yShader)
                                    builder.child("uvTex", uvShader)
                                    builder.uniform("colorSpace", csUniform)
                                    builder.uniform("colorRange", crUniform)
                                    builder.makeShader().use { shader ->
                                        paint.shader = shader
                                        canvas.withVideoTransform(offsetX, offsetY, scale) {
                                            drawRect(Rect.makeWH(srcW, srcH), paint)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    VideoPixelFormat.YUV420P -> {
                        val y = requireNotNull(frame.planeY)
                        val u = requireNotNull(frame.planeU)
                        val v = requireNotNull(frame.planeV)
                        RuntimeShaderBuilder(yuv420pEffect).use { builder ->
                            frame.withVideoShader(y) { yShader ->
                                frame.withVideoShader(u) { uShader ->
                                    frame.withVideoShader(v) { vShader ->
                                        builder.child("yTex", yShader)
                                        builder.child("uTex", uShader)
                                        builder.child("vTex", vShader)
                                        builder.uniform("colorSpace", csUniform)
                                        builder.uniform("colorRange", crUniform)
                                        builder.makeShader().use { shader ->
                                            paint.shader = shader
                                            canvas.withVideoTransform(offsetX, offsetY, scale) {
                                                drawRect(Rect.makeWH(srcW, srcH), paint)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    VideoPixelFormat.BGRA -> {
                        val bitmap = requireNotNull(frame.bgraBitmap)
                        canvas.drawImageRect(
                            frame.planeImage(bitmap),
                            Rect.makeWH(srcW, srcH),
                            Rect.makeXYWH(offsetX.toFloat(), offsetY.toFloat(), dstW.toFloat(), dstH.toFloat()),
                            linearSampling,
                            null,
                            true,
                        )
                    }
                }
            }
        }
    } catch (error: Throwable) {
        PlaybackEventLog.error(PlaybackEventKind.PLAYER, "VideoRenderer", "Video frame draw failed", error)
        false
    }
}

private inline fun VideoFrame.withVideoShader(
    plane: Bitmap,
    block: (Shader) -> Unit,
) {
    // Copy mutable decoder pixels while drawSafely holds the frame lock. Recorded draws retain the image.
    planeImage(plane).makeShader(filterClamp, filterClamp, linearSampling).use(block)
}

private inline fun Canvas.withVideoTransform(
    offsetX: Int,
    offsetY: Int,
    scale: Float,
    block: Canvas.() -> Unit,
) {
    val count = save()
    try {
        translate(offsetX.toFloat(), offsetY.toFloat())
        scale(scale, scale)
        block()
    } finally {
        restoreToCount(count)
    }
}
