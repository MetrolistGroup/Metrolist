/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.core.graphics.createBitmap
import androidx.core.os.ConfigurationCompat
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import java.text.NumberFormat
import java.util.Locale

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
private const val MAX_WIDGET_ARTWORK_SIZE = 240
private const val SHAPE_MASK_SIZE = 96
private const val PILL_ARTWORK_WIDTH = 280
private const val PILL_ARTWORK_HEIGHT = 152
private const val WIDE_CARD_ARTWORK_WIDTH = 280
private const val WIDE_CARD_ARTWORK_HEIGHT = 160

private val cookie12Mask: Bitmap by lazy { createMaterialShapeMask(cookie12Polygon()) }
private val cookie9Mask: Bitmap by lazy { createMaterialShapeMask(cookie9Polygon()) }
private val pillMask: Bitmap by lazy { createPillMask() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun cookie12Polygon(): RoundedPolygon = MaterialShapes.Cookie12Sided

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun cookie9Polygon(): RoundedPolygon = MaterialShapes.Cookie9Sided

internal fun cookie12MaskBitmap(): Bitmap = cookie12Mask

internal fun cookie9MaskBitmap(): Bitmap = cookie9Mask

internal fun pillMaskBitmap(): Bitmap = pillMask

internal fun artworkCornerRadius(size: Int): Float = size * ARTWORK_CORNER_FRACTION

internal fun getRoundedCornerBitmap(bitmap: Bitmap): Bitmap {
    val size = minOf(bitmap.width, bitmap.height, MAX_WIDGET_ARTWORK_SIZE)
    val cornerRadius = artworkCornerRadius(size)
    val output = createBitmap(size, size)
    val canvas = Canvas(output)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = widgetArtworkShader(bitmap, size, size)
        }
    canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), cornerRadius, cornerRadius, paint)
    return output
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun getCookieBitmap(bitmap: Bitmap): Bitmap = getPolygonBitmap(bitmap, MaterialShapes.Cookie12Sided)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun getCookie9Bitmap(bitmap: Bitmap): Bitmap = getPolygonBitmap(bitmap, MaterialShapes.Cookie9Sided)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun getPolygonBitmap(
    bitmap: Bitmap,
    polygon: RoundedPolygon,
): Bitmap {
    val size = minOf(bitmap.width, bitmap.height, MAX_WIDGET_ARTWORK_SIZE)
    val output = createBitmap(size, size)
    val path = polygon.toFittedAndroidPath(size.toFloat(), size.toFloat())
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = widgetArtworkShader(bitmap, size, size)
        }
    Canvas(output).drawPath(path, paint)
    return output
}

internal fun getPillArtworkBitmap(
    bitmap: Bitmap,
    addPlayScrim: Boolean = true,
): Bitmap {
    val output = createBitmap(PILL_ARTWORK_WIDTH, PILL_ARTWORK_HEIGHT)
    val canvas = Canvas(output)
    val rect = RectF(0f, 0f, PILL_ARTWORK_WIDTH.toFloat(), PILL_ARTWORK_HEIGHT.toFloat())
    val radius = PILL_ARTWORK_HEIGHT / 2f
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = widgetArtworkShader(bitmap, PILL_ARTWORK_WIDTH, PILL_ARTWORK_HEIGHT)
        }
    canvas.drawRoundRect(rect, radius, radius, paint)
    if (addPlayScrim) {
        val scrim =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(128, 0, 0, 0)
            }
        canvas.drawRoundRect(rect, radius, radius, scrim)
    }
    return output
}

internal fun getWideCardArtworkBitmap(bitmap: Bitmap): Bitmap {
    val output = createBitmap(WIDE_CARD_ARTWORK_WIDTH, WIDE_CARD_ARTWORK_HEIGHT)
    val canvas = Canvas(output)
    val rect = RectF(0f, 0f, WIDE_CARD_ARTWORK_WIDTH.toFloat(), WIDE_CARD_ARTWORK_HEIGHT.toFloat())
    val radius = WIDE_CARD_ARTWORK_HEIGHT * 0.24f
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = widgetArtworkShader(bitmap, WIDE_CARD_ARTWORK_WIDTH, WIDE_CARD_ARTWORK_HEIGHT)
        }
    canvas.drawRoundRect(rect, radius, radius, paint)
    return output
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun createMaterialShapeMask(polygon: RoundedPolygon): Bitmap {
    val output = createBitmap(SHAPE_MASK_SIZE, SHAPE_MASK_SIZE)
    val path = polygon.toFittedAndroidPath(SHAPE_MASK_SIZE.toFloat(), SHAPE_MASK_SIZE.toFloat())
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
    Canvas(output).drawPath(path, paint)
    return output
}

private fun createPillMask(): Bitmap {
    val output = createBitmap(PILL_ARTWORK_WIDTH, PILL_ARTWORK_HEIGHT)
    val rect = RectF(0f, 0f, PILL_ARTWORK_WIDTH.toFloat(), PILL_ARTWORK_HEIGHT.toFloat())
    val radius = PILL_ARTWORK_HEIGHT / 2f
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
    Canvas(output).drawRoundRect(rect, radius, radius, paint)
    return output
}

private fun RoundedPolygon.toFittedAndroidPath(
    width: Float,
    height: Float,
): Path {
    val path = toPath(Path())
    val bounds = RectF()
    path.computeBounds(bounds, true)
    if (bounds.width() > 0f && bounds.height() > 0f && width > 0f && height > 0f) {
        val inset = 1f
        val scale = minOf((width - inset * 2f) / bounds.width(), (height - inset * 2f) / bounds.height())
        val matrix =
            Matrix().apply {
                postTranslate(-bounds.centerX(), -bounds.centerY())
                postScale(scale, scale)
                postTranslate(width / 2f, height / 2f)
            }
        path.transform(matrix)
    }
    return path
}

private fun widgetArtworkShader(
    bitmap: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
): BitmapShader {
    val scale = maxOf(targetWidth.toFloat() / bitmap.width, targetHeight.toFloat() / bitmap.height)
    return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    (targetWidth - bitmap.width * scale) / 2f,
                    (targetHeight - bitmap.height * scale) / 2f,
                )
            },
        )
    }
}

private const val WIDGET_PROGRESS_MAX = 1000
private const val WIDGET_PROGRESS_LEVEL_MAX = 10000

internal data class WidgetProgressIds(
    val bar: Int,
    val track: Int,
    val fill: Int,
    val stop: Int,
)

internal val musicWidgetProgressIds =
    WidgetProgressIds(
        bar = R.id.widget_progress,
        track = R.id.widget_progress_track,
        fill = R.id.widget_progress_fill,
        stop = R.id.widget_progress_stop,
    )

internal val playlistWidgetProgressIds =
    WidgetProgressIds(
        bar = R.id.widget_playlist_progress,
        track = R.id.widget_playlist_progress_track,
        fill = R.id.widget_playlist_progress_fill,
        stop = R.id.widget_playlist_progress_stop,
    )

internal fun RemoteViews.setWidgetProgress(
    ids: WidgetProgressIds,
    duration: Long,
    currentPosition: Long,
) {
    val fraction = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setViewVisibility(ids.bar, View.VISIBLE)
        setViewVisibility(ids.track, View.GONE)
        setViewVisibility(ids.fill, View.GONE)
        setViewVisibility(ids.stop, View.GONE)
        setProgressBar(ids.bar, WIDGET_PROGRESS_MAX, (fraction * WIDGET_PROGRESS_MAX).toInt(), false)
    } else {
        // RemoteViews cannot tint ProgressBar below API 31; clip ImageViews keep setColorFilter.
        setViewVisibility(ids.bar, View.GONE)
        setViewVisibility(ids.track, View.VISIBLE)
        setViewVisibility(ids.fill, View.VISIBLE)
        setViewVisibility(ids.stop, View.VISIBLE)
        setInt(ids.fill, "setImageLevel", (fraction * WIDGET_PROGRESS_LEVEL_MAX).toInt())
    }
}

internal fun formatWidgetDuration(
    context: Context,
    milliseconds: Long,
): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
    val plain = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
    val padded =
        NumberFormat.getIntegerInstance(locale).apply {
            isGroupingUsed = false
            minimumIntegerDigits = 2
        }
    return if (hours > 0) {
        context.getString(R.string.widget_duration_hours, plain.format(hours), padded.format(minutes), padded.format(seconds))
    } else {
        context.getString(R.string.widget_duration_minutes, plain.format(minutes), padded.format(seconds))
    }
}

internal fun formatWidgetTimePair(
    context: Context,
    currentPosition: Long,
    duration: Long,
): String =
    context.getString(
        R.string.widget_time_pair,
        formatWidgetDuration(context, currentPosition),
        formatWidgetDuration(context, duration),
    )

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
