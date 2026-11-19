package com.metrolist.shared.ui.components.lyrics

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class LyricsGlowPositions(val firstX: Float, val firstY: Float, val secondX: Float, val secondY: Float)

fun lyricsGlowPositions(timeMs: Long, durationMs: Long): LyricsGlowPositions {
    require(durationMs > 0)
    val phase = timeMs.mod(durationMs).toDouble() / durationMs * 2.0 * PI
    return LyricsGlowPositions(
        (0.28 + 0.16 * sin(phase)).toFloat(),
        (0.34 + 0.12 * cos(phase)).toFloat(),
        (0.72 - 0.14 * sin(phase)).toFloat(),
        (0.66 - 0.12 * cos(phase)).toFloat(),
    )
}

internal fun DrawScope.drawLyricsMotion(background: ImageBitmap, foreground: ImageBitmap, color: Color, timeMs: Long, durationMs: Long) {
    val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.width * 20f / 340f))) }
    clipPath(path) {
        val target = IntSize(size.width.toInt(), size.height.toInt())
        drawImage(background, dstSize = target)
        val p = lyricsGlowPositions(timeMs, durationMs)
        fun glow(x: Float, y: Float) {
            val center = Offset(x * size.width, y * size.height)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.38f), color.copy(alpha = 0f)), center, size.width * 0.52f), size.width * 0.52f, center)
        }
        glow(p.firstX, p.firstY)
        glow(p.secondX, p.secondY)
        drawImage(foreground, dstSize = target)
    }
}

internal fun DrawScope.drawLyricsVideo(video: LyricsVideoBackgroundLease, foreground: ImageBitmap, timeMs: Long) {
    val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(size.width * 20f / 340f))) }
    clipPath(path) {
        val target = IntSize(size.width.toInt(), size.height.toInt())
        video.withFrame(timeMs) { frame ->
            val crop = minOf(frame.width, frame.height)
            drawImage(frame, srcOffset = IntOffset((frame.width - crop) / 2, (frame.height - crop) / 2), srcSize = IntSize(crop, crop), dstSize = target)
        }
        drawImage(foreground, dstSize = target)
    }
}
