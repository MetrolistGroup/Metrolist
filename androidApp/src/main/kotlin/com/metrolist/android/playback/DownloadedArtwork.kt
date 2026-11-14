package com.metrolist.android.playback

import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.shared.utils.canonicalArtworkUrl
import com.metrolist.shared.utils.persistentArtworkUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8
import timber.log.Timber
import java.io.File

internal class DownloadedArtwork(
    private val context: Context,
) {
    private val directory = File(context.filesDir, "downloaded-artwork")

    fun file(url: String): File {
        val current = keyedFile(canonicalArtworkUrl(url))
        return current.takeIf { it.length() > 0 } ?: keyedFile(persistentArtworkUrl(url))
    }

    private fun keyedFile(url: String): File = File(directory, url.encodeUtf8().sha256().hex() + ".png")

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
        val retained = urls.mapTo(mutableSetOf()) { file(it).name }
        directory.listFiles()?.filter { it.name !in retained }?.forEach {
            if (!it.delete()) Timber.w("Could not delete unused download artwork")
        }
    }
}
