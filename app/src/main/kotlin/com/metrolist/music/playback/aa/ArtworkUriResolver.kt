package com.metrolist.music.playback.aa

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.media3.common.util.SystemClock
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.metrolist.music.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtworkUriResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 10
        const val MAX_PENDING_PREFETCHES = 100
        const val PREFETCH_DECODE_SIZE = 100
        const val MAX_FAILED_URLS = 500
        const val FAILED_RETRY_DELAY_MS = 5 * 60 * 1000L

        const val MAX_ARTWORK_DIMENSION = 4096
        const val VALIDATION_DECODE_SIZE = 32
        const val MIN_ARTWORK_FILE_SIZE = 100L
        const val MAX_VALIDATED_FILES_SIZE = 2000
    }

    private val prefetchedUrls = ConcurrentHashMap.newKeySet<String>()
    private val failedUrls = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private var onArtworkReadyListener: ((String) -> Unit)? = null

    private data class ValidatedFileIdentity(val size: Long, val lastModified: Long)
    private val validatedFiles: MutableMap<String, ValidatedFileIdentity> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, ValidatedFileIdentity>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, ValidatedFileIdentity>,
                ): Boolean = size > MAX_VALIDATED_FILES_SIZE
            },
        )

    fun resolve(
        url: String?,
        @DrawableRes placeholder: Int = R.drawable.music_note,
    ): Uri {
        if (url.isNullOrBlank() || isFailedRecently(url)) {
            return drawableUri(placeholder)
        }

        validatedIdentity(url)?.let {
            return ArtworkProvider.uriFor(context, url, it)
        }

        schedulePrefetch(url)
        return drawableUri(placeholder)
    }

    private fun validatedIdentity(url: String): String? {
        val diskCache = context.imageLoader.diskCache ?: return null

        val snapshot = try {
            diskCache.openSnapshot(url)
        } catch (_: Throwable) {
            null
        } ?: return null

        val file = snapshot.data.toFile()
        val identity = try {
            if (file.exists() && file.length() > 0L && isValidImageFile(file)) {
                "${file.length()}:${file.lastModified()}"
            } else null
        } catch (_: Throwable) {
            null
        }
        snapshot.close()
        return identity
    }

    internal fun isValidImageFile(file: File): Boolean {
        if (!file.exists()) return false
        val fileSize = file.length()
        if (fileSize < MIN_ARTWORK_FILE_SIZE) return false

        val identity = ValidatedFileIdentity(fileSize, file.lastModified())
        if (validatedFiles[file.absolutePath] == identity) return true

        if (!hasKnownImageSignature(file)) return false

        // Stage 1: bounds check
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        } catch (_: Throwable) {
            return false
        }

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight

        if (width <= 0 || height <= 0) return false
        if (width > MAX_ARTWORK_DIMENSION || height > MAX_ARTWORK_DIMENSION) return false

        //Check JPEG truncation
        if (isJpeg(file) && !containsJpegEoi(file)) return false

        // Stage 2: sampled decode
        val sampleSize = calculateInSampleSize(width, height)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (_: Throwable) {
            null
        }

        val isValid = bitmap != null && bitmap.width > 0 && bitmap.height > 0
        bitmap?.recycle()

        if (isValid) {
            validatedFiles[file.absolutePath] = identity
        } else {
            Timber.tag("ArtworkUriResolver")
                .d("Artwork file $file is invalid")
            validatedFiles.remove(file.absolutePath)
        }

        return isValid
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= VALIDATION_DECODE_SIZE
                && height / (sampleSize * 2) >= VALIDATION_DECODE_SIZE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun hasKnownImageSignature(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                when {
                    // JPEG: FF D8 FF
                    read >= 3 &&
                            header[0] == 0xFF.toByte() &&
                            header[1] == 0xD8.toByte() &&
                            header[2] == 0xFF.toByte() -> true
                    // PNG: 89 50 4E 47
                    read >= 4 &&
                            header[0] == 0x89.toByte() &&
                            header[1] == 0x50.toByte() &&
                            header[2] == 0x4E.toByte() &&
                            header[3] == 0x47.toByte() -> true
                    // WebP: RIFF....WEBP
                    read >= 12 &&
                            header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                            header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                            header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                            header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> true
                    // GIF: GIF8
                    read >= 4 &&
                            header[0] == 0x47.toByte() && header[1] == 0x49.toByte() &&
                            header[2] == 0x46.toByte() && header[3] == 0x38.toByte() -> true
                    else -> false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isJpeg(file: File): Boolean =
        try {
            file.inputStream().use { it.read() == 0xFF && it.read() == 0xD8 }
        } catch (_: Throwable) {
            false
        }

    private fun containsJpegEoi(file: File): Boolean =
        try {
            file.inputStream().buffered().use { input ->
                var prev = input.read()
                while (prev != -1) {
                    val curr = input.read()
                    if (curr == -1) return false
                    if (prev == 0xFF && curr == 0xD9) return true
                    prev = curr
                }
                false
            }
        } catch (_: Throwable) {
            false
        }

    private fun schedulePrefetch(url: String) {
        if (context.imageLoader.diskCache == null) return

        val isScheduleAllowed = synchronized(prefetchedUrls) {
            prefetchedUrls.size < MAX_PENDING_PREFETCHES && prefetchedUrls.add(url)
        }
        if (!isScheduleAllowed) return

        scope.launch {
            try {
                semaphore.withPermit {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .size(PREFETCH_DECODE_SIZE)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.WRITE_ONLY)
                        .build()

                    val result = context.imageLoader.execute(request)
                    onPrefetchCompleted(url, result is ErrorResult)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                addFailedUrl(url)
            } finally {
                prefetchedUrls.remove(url)
            }
        }
    }

    fun setOnArtworkReadyListener(listener: ((String) -> Unit)?) {
        onArtworkReadyListener = listener
    }

    internal fun onPrefetchCompleted(url: String, isError: Boolean) {
        if (isError || validatedIdentity(url) == null) {
            addFailedUrl(url)
        } else {
            failedUrls.remove(url)
            onArtworkReadyListener?.invoke(url)
        }
    }

    internal fun isFailedRecently(url: String): Boolean {
        val failedAt = failedUrls[url] ?: return false
        val now = SystemClock.DEFAULT.elapsedRealtime()

        if (now - failedAt >= FAILED_RETRY_DELAY_MS) {
            failedUrls.remove(url, failedAt)
            return false
        }

        return true
    }

    private fun addFailedUrl(url: String) {
        val now = SystemClock.DEFAULT.elapsedRealtime()
        failedUrls[url] = now

        if (failedUrls.size <= MAX_FAILED_URLS) return

        while (failedUrls.size > MAX_FAILED_URLS) {
            val oldest = failedUrls.entries.minByOrNull { it.value } ?: break
            failedUrls.remove(oldest.key, oldest.value)
        }
    }

    private fun drawableUri(@DrawableRes id: Int): Uri =
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.resources.getResourcePackageName(id))
            .appendPath(context.resources.getResourceTypeName(id))
            .appendPath(context.resources.getResourceEntryName(id))
            .build()
}