package com.metrolist.shared.ui.utils

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.skiaImageFilter
import androidx.compose.ui.platform.clearSkikoComposeImplementation
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.node.RenderNode
import org.jetbrains.skiko.node.RenderNodeContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

@OptIn(InternalComposeUiApi::class)
class ArtworkBlurTest {
    @BeforeTest
    fun registerGraphics() = registerSkikoComposeImplementation()

    @AfterTest
    fun clearGraphics() = clearSkikoComposeImplementation()

    @Test
    fun cachedPixelsMatchThePreviousBlurOverItsBackground() {
        for ((width, height) in listOf(216 to 144, 144 to 216)) {
            for (density in listOf(1f, 1.5f)) {
                for (unbounded in listOf(false, true)) {
                    for ((background, scrim) in listOf(
                        0 to 0,
                        0xff000000.toInt() to 0,
                        0xff402010.toInt() to 0,
                        0xff000000.toInt() to 0x80000000.toInt(),
                        0xff000000.toInt() to 0x73000000,
                    )) {
                        compareWithRenderNode(width, height, 12f * density, unbounded, background, scrim)
                    }
                }
            }
        }
    }

    private fun compareWithRenderNode(
        width: Int,
        height: Int,
        radius: Float,
        unbounded: Boolean,
        background: Int,
        scrim: Int,
    ) {
        val tileMode = if (unbounded) TileMode.Decal else TileMode.Clamp
        RenderNodeContext().use { context ->
            RenderNode(context).use { scaled ->
                RenderNode(context).use { blurred ->
                    BlurEffect(radius, radius, tileMode).skiaImageFilter.use { filter ->
                        Paint().use { paint ->
                            paint.imageFilter = filter
                            blurred.bounds = Rect.makeWH(width.toFloat(), height.toFloat())
                            blurred.layerPaint = paint
                            blurred.clip = !unbounded
                            if (!unbounded) blurred.setClipRect(blurred.bounds)
                            drawArtwork(blurred.beginRecording(), width, height)
                            blurred.endRecording()
                            scaled.bounds = blurred.bounds
                            scaled.setNodePivot(width / 2f, height / 2f)
                            scaled.scaleX = 1.6f
                            scaled.scaleY = 1.6f
                            blurred.drawInto(scaled.beginRecording())
                            scaled.endRecording()
                            Surface.makeRasterN32Premul(width, height).use { expected ->
                                expected.canvas.clear(background)
                                scaled.drawInto(expected.canvas)
                                Paint().use { tint ->
                                    tint.color = scrim
                                    expected.canvas.drawRect(blurred.bounds, tint)
                                }
                                rasterizeArtworkBlur(width.toFloat(), height.toFloat(), radius, 1.6f, unbounded, background, scrim) {
                                    drawArtwork(it, width, height)
                                }.use { cached ->
                                    expected.makeImageSnapshot().use { reference ->
                                        assertContentEquals(
                                            reference.encodeToData()!!.use { it.bytes },
                                            cached.encodeToData()!!.use { it.bytes },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun drawArtwork(
        canvas: Canvas,
        width: Int,
        height: Int,
    ) {
        Paint().use { paint ->
            paint.color = 0xff204060.toInt()
            canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint)
            paint.color = 0x80ff2000.toInt()
            canvas.drawRect(Rect.makeXYWH(width * 0.3f, 0f, width * 0.6f, height * 0.8f), paint)
            paint.color = 0xff00a080.toInt()
            canvas.drawCircle(width * 0.25f, height * 0.75f, width * 0.2f, paint)
        }
    }
}
