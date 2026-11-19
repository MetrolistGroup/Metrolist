package com.metrolist.android.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.metrolist.shared.ui.components.lyrics.LyricsAnimationExport
import com.metrolist.shared.ui.components.lyrics.lyricsGlowPositions
import kotlin.math.max
import kotlin.math.min

internal class LyricsFrameCompositor(private val request: LyricsAnimationExport, private val side: Int) : AutoCloseable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val bounds = RectF()
    private val videoBounds = RectF()
    private val clip = Path()
    private val rgb = request.motionColor and 0x00ffffff
    private val glow = RadialGradient(0f,0f,1f,intArrayOf((97 shl 24) or rgb,rgb),null,Shader.TileMode.CLAMP)
    private var foreground: Bitmap? = null
    private var lastKey: Int? = null
    private var closed = false
    private var background = request.motionBackground?.takeIf { request.videoBackground == null }?.softwareCopy()
    private var videoLease = request.videoBackground?.acquireLease()

    fun draw(canvas: Canvas, timeMs: Long) {
        check(!closed)
        val key = request.frameKeyAt(timeMs)
        if (foreground == null || lastKey != key) {
            val replacement = request.renderFrame(timeMs).softwareCopy()
            foreground?.recycle(); foreground = replacement; lastKey = key
            val scale = min(side.toFloat()/replacement.width,side.toFloat()/replacement.height)
            val width = replacement.width*scale; val height = replacement.height*scale
            bounds.set((side-width)/2f,(side-height)/2f,(side+width)/2f,(side+height)/2f)
            clip.reset(); clip.addRoundRect(bounds,bounds.width()*20f/340f,bounds.width()*20f/340f,Path.Direction.CW)
        }
        canvas.drawColor(Color.BLACK)
        val save = canvas.save()
        try {
            canvas.clipPath(clip)
            val lease = videoLease
            if (lease != null) {
                lease.withFrame(timeMs) { image ->
                    val frame = image.asAndroidBitmap()
                    val scale = max(bounds.width()/frame.width,bounds.height()/frame.height)
                    val width = frame.width*scale; val height = frame.height*scale
                    videoBounds.set(bounds.centerX()-width/2,bounds.centerY()-height/2,bounds.centerX()+width/2,bounds.centerY()+height/2)
                    canvas.drawBitmap(frame,null,videoBounds,paint)
                }
            } else {
                background?.let {
                    canvas.drawBitmap(it,null,bounds,paint)
                    val p = lyricsGlowPositions(timeMs,request.durationMs)
                    drawGlow(canvas,p.firstX,p.firstY)
                    drawGlow(canvas,p.secondX,p.secondY)
                }
            }
            canvas.drawBitmap(checkNotNull(foreground),null,bounds,paint)
        } finally { canvas.restoreToCount(save) }
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float) {
        val radius = bounds.width()*.52f
        val cx = bounds.left+x*bounds.width(); val cy = bounds.top+y*bounds.height()
        matrix.setScale(radius,radius); matrix.postTranslate(cx,cy)
        glow.setLocalMatrix(matrix); glowPaint.shader = glow
        canvas.drawCircle(cx,cy,radius,glowPaint)
    }

    override fun close() {
        if (closed) return
        closed = true
        foreground?.recycle(); foreground = null
        background?.recycle(); background = null
        val lease = videoLease; videoLease = null; lease?.close()
    }
}

private fun ImageBitmap.softwareCopy(): Bitmap {
    require(width > 0 && height > 0 && width.toLong()*height <= 16_000_000)
    return checkNotNull(asAndroidBitmap().copy(Bitmap.Config.ARGB_8888,false))
}
