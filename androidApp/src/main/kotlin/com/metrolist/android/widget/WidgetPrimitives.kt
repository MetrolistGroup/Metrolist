package com.metrolist.android.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class WidgetState(
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val duration: Long,
    val currentPosition: Long,
)

private const val ARTWORK_CORNER_FRACTION = 0.28f
private const val MAX_WIDGET_ARTWORK_SIZE = 300

internal fun artworkCornerRadius(size: Int): Float = size * ARTWORK_CORNER_FRACTION

internal fun getRoundedCornerBitmap(bitmap: Bitmap): Bitmap {
    val size = minOf(bitmap.width, bitmap.height, MAX_WIDGET_ARTWORK_SIZE)
    val cornerRadius = artworkCornerRadius(size)
    val output = createBitmap(size, size)
    val canvas = Canvas(output)
    val paint =
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = widgetArtworkShader(bitmap, size)
        }
    canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), cornerRadius, cornerRadius, paint)
    return output
}

internal fun getCookieBitmap(bitmap: Bitmap): Bitmap {
    val size = minOf(bitmap.width, bitmap.height, MAX_WIDGET_ARTWORK_SIZE)
    val output = createBitmap(size, size)
    val center = size / 2f
    val path =
        Path().apply {
            val steps = 360
            for (step in 0..steps) {
                val angle = 2 * PI * step / steps
                val radius = center * (1f - 0.08f * (1f - cos(12 * angle).toFloat()) / 2f)
                val x = center + radius * cos(angle).toFloat()
                val y = center + radius * sin(angle).toFloat()
                if (step == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    val paint =
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            shader = widgetArtworkShader(bitmap, size)
        }
    Canvas(output).drawPath(path, paint)
    return output
}

private fun widgetArtworkShader(
    bitmap: Bitmap,
    size: Int,
): BitmapShader {
    val scale = size.toFloat() / minOf(bitmap.width, bitmap.height)
    return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - bitmap.width * scale) / 2f, (size - bitmap.height * scale) / 2f)
            },
        )
    }
}

internal fun playButtonBackground(
    isPlaying: Boolean,
    inGroup: Boolean,
): Int =
    when {
        inGroup && isPlaying -> R.drawable.widget_group_middle_selected_bg
        inGroup -> R.drawable.widget_group_middle_bg
        isPlaying -> R.drawable.widget_play_pill_selected_bg
        else -> R.drawable.widget_play_pill_bg
    }

internal fun likeButtonBackground(isLiked: Boolean): Int =
    if (isLiked) R.drawable.widget_like_button_selected_bg else R.drawable.widget_like_button_bg

internal fun progressLevel(
    duration: Long,
    currentPosition: Long,
): Int = if (duration > 0) ((currentPosition.toDouble() / duration.toDouble()) * 10000).toInt().coerceIn(0, 10000) else 0

internal fun openAppPendingIntent(
    context: Context,
    requestCode: Int,
): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal fun widgetActionPendingIntent(
    context: Context,
    action: String,
    requestCode: Int,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, WidgetActionReceiver::class.java).apply { this.action = action },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
