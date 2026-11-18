package com.metrolist.shared.ui.components

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private data class LegacyArtworkBlurKey(
    val source: Bitmap,
    val generation: Int,
    val radius: Int,
)

private val legacyArtworkBlurCache = LruCache<LegacyArtworkBlurKey, ImageBitmap>(4)
private val legacyArtworkBlurMutex = Mutex()

internal suspend fun legacyArtworkBlur(
    source: Bitmap,
    radius: Int,
): ImageBitmap =
    withContext(Dispatchers.Default) {
        require(radius > 0)
        legacyArtworkBlurMutex.withLock {
            val key = LegacyArtworkBlurKey(source, source.generationId, radius)
            legacyArtworkBlurCache.get(key)?.let { return@withLock it }
            // Bitmap.Config.HARDWARE only exists from API 26; reading it earlier throws NoSuchFieldError.
            val bitmap =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    source
                }
            val scale = ARTWORK_TEXTURE_SIZE.toFloat() / max(bitmap.width, bitmap.height)
            val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true).copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            blurArtworkPixels(pixels, width, height, radius)
            scaled.setPixels(pixels, 0, width, 0, 0, width, height)
            scaled.asImageBitmap().also { legacyArtworkBlurCache.put(key, it) }
        }
    }
