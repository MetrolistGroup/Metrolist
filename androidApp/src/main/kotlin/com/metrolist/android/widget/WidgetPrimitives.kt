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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.core.graphics.createBitmap
import androidx.core.os.ConfigurationCompat
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal data class WidgetState(
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val duration: Long,
    val currentPosition: Long,
)

internal data class WidgetProgressLayers(
    val track: Bitmap,
    val active: Bitmap,
)

private const val ARTWORK_CORNER_FRACTION = 0.28f
private const val MAX_WIDGET_ARTWORK_SIZE = 240
private const val SHAPE_MASK_SIZE = 96
private const val PILL_ARTWORK_WIDTH = 280
private const val PILL_ARTWORK_HEIGHT = 152
private const val WIDE_CARD_ARTWORK_WIDTH = 280
private const val WIDE_CARD_ARTWORK_HEIGHT = 160
private const val SLIDER_BAR_WIDTH = 260
private const val SLIDER_BAR_HEIGHT = 28
private const val SLIDER_CONTAINER_HEIGHT_DP = 18f
private const val LINEAR_BAR_WIDTH = 160
private const val LINEAR_BAR_HEIGHT = 10

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

/**
 * Renders the Material 3 linear determinate slider (straight active track, vertical pill playhead
 * thumb, gap, remaining track, and stop indicator dot) as two white alpha-mask bitmaps tinted by
 * RemoteViews so day/night switches stay automatic.
 */
internal fun renderLinearSliderLayers(progressFraction: Float): WidgetProgressLayers {
    val width = SLIDER_BAR_WIDTH.toFloat()
    val height = SLIDER_BAR_HEIGHT.toFloat()
    val centerY = height / 2f
    val pxPerDp = height / SLIDER_CONTAINER_HEIGHT_DP
    val strokeWidth = (3.6f * pxPerDp).coerceIn(3f, 7f)
    val cap = strokeWidth / 2f
    val gapPx = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize.value * pxPerDp
    val stopSize = min(strokeWidth, ProgressIndicatorDefaults.LinearTrackStopIndicatorSize.value * pxPerDp)
    val thumbWidth = strokeWidth * 1.15f
    val thumbHeight = (height - 4f).coerceAtLeast(strokeWidth * 3f)
    val thumbHalfW = thumbWidth / 2f

    val fraction = progressFraction.coerceIn(0f, 1f)
    val playheadX = (thumbHalfW + fraction * (width - thumbWidth)).coerceIn(thumbHalfW, width - thumbHalfW)

    val trackBitmap = createBitmap(SLIDER_BAR_WIDTH, SLIDER_BAR_HEIGHT)
    val trackCanvas = Canvas(trackBitmap)
    val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
        }
    val trackStart = playheadX + thumbHalfW + gapPx + cap
    if (width - cap > trackStart) {
        trackCanvas.drawLine(trackStart, centerY, width - cap, centerY, trackPaint)
    }

    val activeBitmap = createBitmap(SLIDER_BAR_WIDTH, SLIDER_BAR_HEIGHT)
    val activeCanvas = Canvas(activeBitmap)
    val activeStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
        }
    val activeFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

    val activeEndX = playheadX - thumbHalfW - gapPx * 0.75f
    if (activeEndX > cap) {
        activeCanvas.drawLine(cap, centerY, activeEndX, centerY, activeStrokePaint)
    }

    // Vertical pill thumb at the playhead
    val thumbRect =
        RectF(
            playheadX - thumbHalfW,
            centerY - thumbHeight / 2f,
            playheadX + thumbHalfW,
            centerY + thumbHeight / 2f,
        )
    activeCanvas.drawRoundRect(thumbRect, thumbHalfW, thumbHalfW, activeFillPaint)

    // Material 3 end-of-track stop indicator dot
    val stopX = width - stopSize / 2f - cap * 0.35f
    if (stopX - stopSize / 2f > playheadX + thumbHalfW + gapPx * 0.5f) {
        activeCanvas.drawCircle(stopX, centerY, stopSize / 2f, activeFillPaint)
    }

    return WidgetProgressLayers(track = trackBitmap, active = activeBitmap)
}

/**
 * Renders the compact straight linear progress bar with rounded caps and end stop indicator dot.
 */
internal fun renderLinearProgressLayers(progressFraction: Float): WidgetProgressLayers {
    val width = LINEAR_BAR_WIDTH.toFloat()
    val height = LINEAR_BAR_HEIGHT.toFloat()
    val centerY = height / 2f
    val strokeWidth = (height * 0.56f).coerceIn(3f, 6f)
    val cap = strokeWidth / 2f
    val gap = strokeWidth * 0.9f
    val fraction = progressFraction.coerceIn(0f, 1f)
    val activeEndX = (cap + fraction * (width - strokeWidth)).coerceIn(cap, width - cap)

    val trackBitmap = createBitmap(LINEAR_BAR_WIDTH, LINEAR_BAR_HEIGHT)
    val trackCanvas = Canvas(trackBitmap)
    val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
        }
    val hasProgress = fraction > 0f
    val trackStart = if (hasProgress) max(cap, activeEndX + gap + cap) else cap
    if (width - cap > trackStart) {
        trackCanvas.drawLine(trackStart, centerY, width - cap, centerY, strokePaint)
    }

    val activeBitmap = createBitmap(LINEAR_BAR_WIDTH, LINEAR_BAR_HEIGHT)
    val activeCanvas = Canvas(activeBitmap)
    if (hasProgress) {
        activeCanvas.drawLine(cap, centerY, activeEndX, centerY, strokePaint)
    }
    val stopRadius = min(strokeWidth, ProgressIndicatorDefaults.LinearTrackStopIndicatorSize.value * 1.4f) / 2f
    val stopX = width - stopRadius - 1f
    if (stopX - stopRadius > activeEndX + cap) {
        val dotPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
        activeCanvas.drawCircle(stopX, centerY, stopRadius, dotPaint)
    }
    return WidgetProgressLayers(track = trackBitmap, active = activeBitmap)
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

internal fun progressFraction(
    duration: Long,
    currentPosition: Long,
): Float = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

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
