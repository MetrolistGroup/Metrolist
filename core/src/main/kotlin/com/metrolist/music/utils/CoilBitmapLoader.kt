/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import timber.log.Timber

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun createFallbackBitmap(): Bitmap = createBitmap(64, 64)

    private fun Bitmap.createIndependentCopy(): Bitmap {
        if (isRecycled) return createFallbackBitmap()
        return try {
            val copy = createBitmap(width, height)
            val canvas = android.graphics.Canvas(copy)
            canvas.drawBitmap(this, 0f, 0f, null)
            copy
        } catch (e: Exception) {
            Timber.tag("CoilBitmapLoader").w(e, "Failed to create independent copy")
            createFallbackBitmap()
        }
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                bitmap?.createIndependentCopy() ?: createFallbackBitmap()
            } catch (e: Exception) {
                Timber.tag("CoilBitmapLoader").w(e, "Failed to decode bitmap data")
                createFallbackBitmap()
            }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(uri)
                        .allowHardware(false)
                        .build()

                when (val result = context.imageLoader.execute(request)) {
                    is ErrorResult -> {
                        createFallbackBitmap()
                    }

                    is SuccessResult -> {
                        try {
                            val bitmap = result.image.toBitmap()
                            bitmap.createIndependentCopy()
                        } catch (e: Exception) {
                            Timber.tag("CoilBitmapLoader").w(e, "Failed to convert image to bitmap")
                            createFallbackBitmap()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("CoilBitmapLoader").w(e, "Failed to load bitmap from uri")
                createFallbackBitmap()
            }
        }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        val artworkUri = metadata.artworkUri ?: metadata.extras?.getString("artwork_uri")?.toUri() ?: return null
        return loadBitmap(artworkUri)
    }
}
