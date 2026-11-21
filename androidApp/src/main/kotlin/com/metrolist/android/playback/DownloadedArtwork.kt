package com.metrolist.android.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.shared.utils.retainedArtworkUrl
import com.metrolist.shared.utils.retainedArtworkUrlCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8
import timber.log.Timber
import java.io.File
import java.io.IOException

internal class DownloadedArtwork(
    private val context: Context,
) {
    private val directory = File(context.filesDir, "downloaded-artwork")

    fun file(url: String): File {
        val stable = keyedFile(retainedArtworkUrl(url))
        retainedArtworkUrlCandidates(url).forEach { candidate ->
            val existing = keyedFile(candidate)
            if (existing.length() == 0L) return@forEach
            if (existing == stable) return stable
            // Promote a pre-stability file so later URL variants find it under the stable key.
            try {
                val temporary = File.createTempFile("artwork-promote-", ".tmp", directory)
                try {
                    existing.inputStream().use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (temporary.renameTo(stable)) return stable
                } finally {
                    temporary.delete()
                }
            } catch (_: IOException) {
                Timber.w("Could not promote retained artwork; using the existing cover")
            }
            return existing
        }
        return stable
    }

    private fun keyedFile(url: String): File = File(directory, url.encodeUtf8().sha256().hex() + ".png")

    suspend fun exportArtwork(url: String?): AudioExportArtwork? =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext null
            retain(url)
            try {
                val source = file(url).takeIf { it.length() > 0 } ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                AudioExportArtwork(source.readBytes(), bounds.outWidth, bounds.outHeight)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Could not read download artwork for export")
                null
            }
        }

    suspend fun retain(url: String?) =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext
            val destination = file(url)
            if (destination.length() > 0) return@withContext
            try {
                val retained =
                    withTimeoutOrNull(30_000) {
                        val result =
                            context.imageLoader.execute(
                                ImageRequest
                                    .Builder(context)
                                    .data(url)
                                    .size(544)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .build(),
                            )
                        val image = (result as? SuccessResult)?.image ?: return@withTimeoutOrNull false
                        check(directory.mkdirs() || directory.isDirectory) { "Could not create download artwork directory" }
                        val temporary = File.createTempFile("artwork-", ".tmp", directory)
                        try {
                            temporary.outputStream().use { output ->
                                check(image.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                    "Could not encode download artwork"
                                }
                            }
                            check(temporary.renameTo(destination)) { "Could not publish download artwork" }
                            true
                        } finally {
                            temporary.delete()
                        }
                    }
                if (retained != true) Timber.w("Download artwork is missing; retrying when connectivity returns")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Could not retain download artwork")
            }
        }

    // Call while audio storage is locked so an in-flight download cannot lose its cover.
    fun prune(urls: List<String>) {
        val retained =
            urls.flatMapTo(mutableSetOf()) { url ->
                retainedArtworkUrlCandidates(url).map { keyedFile(it).name }
            }
        directory.listFiles()?.filter { it.name !in retained }?.forEach {
            if (!it.delete()) Timber.w("Could not delete unused download artwork")
        }
    }
}
