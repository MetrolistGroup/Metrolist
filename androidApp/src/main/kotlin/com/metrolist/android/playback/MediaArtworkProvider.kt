package com.metrolist.android.playback

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.net.toUri
import coil3.Image
import coil3.disk.DiskCache
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.shared.ui.components.libraryCover
import com.metrolist.shared.utils.artworkCacheCandidates
import com.metrolist.shared.utils.canonicalArtworkUrl
import com.metrolist.shared.utils.sizedArtworkUrl
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val CURRENT_ARTWORK_TARGET_PX = 512
private const val CURRENT_ARTWORK_UPGRADE_TIMEOUT_MS = 4_000L
private const val CURRENT_ARTWORK_RETRY_INTERVAL_MS = 30_000L

internal fun mediaArtworkUri(
    context: Context,
    url: String?,
    isCurrent: Boolean = false,
): Uri? = mediaArtworkUri(context.packageName, url, isCurrent)

internal fun mediaArtworkUri(
    packageName: String,
    url: String?,
    isCurrent: Boolean = false,
): Uri? {
    if (isCurrent) MediaArtworkProvider.currentArtwork = url?.let { canonicalArtworkUrl(it) to it }
    if (url == null) return null
    val uri = url.toUri()
    if (uri.scheme == "content" || uri.scheme == "android.resource") return uri
    return artworkContentUri(packageName, canonicalArtworkUrl(url), url)
}

private fun artworkContentUri(
    packageName: String,
    key: String,
    url: String,
): Uri {
    MediaArtworkProvider.approve(key, url)
    return Uri
        .Builder()
        .scheme("content")
        .authority("$packageName.media-artwork")
        .appendPath("artwork")
        .appendQueryParameter("key", key)
        .build()
}

class MediaArtworkProvider : ContentProvider() {
    private val artworkUpgrade = CurrentArtworkUpgradeCooldown()

    companion object {
        // Cached artwork reveals listening history, so only trusted connected media controllers may read it.
        internal val allowedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

        // Queue and Auto browsing can evict approvals while the current metadata still holds its URI.
        @Volatile
        internal var currentArtwork: Pair<String, String>? = null
        private val approvedArtwork =
            object : LinkedHashMap<String, String>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 2_000
            }

        internal fun approve(
            key: String,
            url: String,
        ) {
            synchronized(approvedArtwork) { approvedArtwork[key] = url }
        }
    }

    override fun onCreate(): Boolean = true

    @OptIn(ExperimentalResourceApi::class)
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r" || uri.path != "/artwork") throw FileNotFoundException()
        val caller = callingPackage
        // A null caller is an in-process call, not a binder request from another app.
        if (caller != null && caller != context?.packageName && caller !in allowedPackages && !isSystemMediaUi()) {
            throw SecurityException()
        }
        val key = uri.getQueryParameter("key") ?: throw FileNotFoundException()
        val currentUrl = currentArtwork?.takeIf { it.first == key }?.second
        val url =
            currentUrl
                ?: synchronized(approvedArtwork) { approvedArtwork[key] }
                ?: throw FileNotFoundException()
        val appContext = context ?: throw FileNotFoundException()
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(30_000) {
                    libraryCover(url)?.let { cover ->
                        val file = File.createTempFile("collection-artwork-", ".webp", appContext.cacheDir)
                        try {
                            file.writeBytes(getDrawableResourceBytes(getSystemResourceEnvironment(), cover))
                            return@withTimeout ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        } finally {
                            file.delete()
                        }
                    }
                    val retained = DownloadedArtwork(appContext).file(url)
                    if (currentUrl != null) {
                        openCurrentMediaArtwork(appContext, url, key, retained)?.let { return@withTimeout it }
                    }
                    if (retained.length() > 0) {
                        try {
                            return@withTimeout ParcelFileDescriptor.open(retained, ParcelFileDescriptor.MODE_READ_ONLY)
                        } catch (_: FileNotFoundException) {
                        }
                    }
                    val loader = appContext.imageLoader
                    val cache = loader.diskCache
                    try {
                        openMediaArtwork(cache, url)
                    } catch (_: FileNotFoundException) {
                        if (url.toUri().scheme != "https") throw FileNotFoundException()
                        val result =
                            loader.execute(
                                ImageRequest
                                    .Builder(appContext)
                                    .data(url)
                                    .size(512)
                                    .diskCacheKey(key)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .build(),
                            )
                        try {
                            openMediaArtwork(cache, url)
                        } catch (_: FileNotFoundException) {
                            val image = (result as? SuccessResult)?.image ?: throw FileNotFoundException()
                            val file = File.createTempFile("media-artwork-", ".png", appContext.cacheDir)
                            try {
                                file.outputStream().use { output ->
                                    if (!image.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                        throw FileNotFoundException()
                                    }
                                }
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            } finally {
                                file.delete()
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            throw FileNotFoundException().apply { initCause(error) }
        }
    }

    private suspend fun openCurrentMediaArtwork(
        context: Context,
        url: String,
        key: String,
        retained: File,
    ): ParcelFileDescriptor? {
        val retainedArtwork = retained.openSizedArtwork()
        if (retainedArtwork != null && retainedArtwork.detail >= CURRENT_ARTWORK_TARGET_PX) return retainedArtwork.descriptor
        val cache =
            try {
                context.imageLoader.diskCache
            } catch (error: CancellationException) {
                retainedArtwork?.let { runCatching { it.descriptor.close() } }
                throw error
            } catch (error: Exception) {
                return retainedArtwork?.descriptor ?: throw error
            }
        val fallback = findCurrentArtwork(cache, url, initial = retainedArtwork) ?: return null
        if (fallback.detail >= CURRENT_ARTWORK_TARGET_PX) return fallback.descriptor
        return preferCurrentArtwork(
            fallback = fallback.descriptor,
            shouldUpgrade = {
                PlaybackNetworkStateStore.current.connected &&
                    url.toUri().scheme == "https" &&
                    sizedArtworkUrl(url, 1) != sizedArtworkUrl(url, CURRENT_ARTWORK_TARGET_PX) &&
                    artworkUpgrade.tryStart(key, SystemClock.elapsedRealtime())
            },
            upgrade = {
                val image =
                    withTimeoutOrNull(CURRENT_ARTWORK_UPGRADE_TIMEOUT_MS) {
                        fetchArtwork(context, url, key)
                    }
                val imageDetail = image?.let { artworkDetail(it.width, it.height) } ?: 0
                findCurrentArtwork(cache, url, minimumDetail = maxOf(fallback.detail + 1, imageDetail))?.descriptor
                    ?: image?.takeIf { imageDetail > fallback.detail }?.openArtworkBitmap(context)
            },
        )
    }

    // System UI reads session metadata artwork without connecting as a Media3 controller.
    private fun isSystemMediaUi(): Boolean =
        context?.checkCallingPermission(Manifest.permission.MEDIA_CONTENT_CONTROL) == PackageManager.PERMISSION_GRANTED

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

internal fun openMediaArtwork(
    cache: DiskCache?,
    url: String,
): ParcelFileDescriptor {
    for (key in (listOf(url) + artworkCacheCandidates(url, 1)).distinct()) {
        cache?.openSnapshot(key)?.use {
            val file = it.data.toFile()
            if (file.length() > 0) {
                // Open the descriptor before releasing the snapshot so eviction cannot race the open.
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }
    throw FileNotFoundException()
}

private suspend fun fetchArtwork(
    context: Context,
    url: String,
    key: String,
): Image? =
    (
        context.imageLoader.execute(
            ImageRequest
                .Builder(context)
                .data(url)
                .size(CURRENT_ARTWORK_TARGET_PX)
                .diskCacheKey(key)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .build(),
        ) as? SuccessResult
    )?.image

private fun Image.openArtworkBitmap(context: Context): ParcelFileDescriptor {
    val file = File.createTempFile("media-artwork-", ".png", context.cacheDir)
    try {
        file.outputStream().use { output ->
            if (!toBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) throw FileNotFoundException()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    } finally {
        file.delete()
    }
}

private data class SizedArtwork(
    val descriptor: ParcelFileDescriptor,
    val detail: Int,
)

// A long, thin bitmap does not have the same usable detail as a normal square or landscape cover.
private fun artworkDetail(
    width: Int,
    height: Int,
): Int {
    if (width <= 0 || height <= 0) return 0
    return minOf(maxOf(width, height).toLong(), 2L * minOf(width, height)).toInt()
}

private fun File.openSizedArtwork(minimumDetail: Int = 1): SizedArtwork? {
    if (length() <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, bounds)
    val detail = artworkDetail(bounds.outWidth, bounds.outHeight)
    if (detail < minimumDetail) return null
    return try {
        SizedArtwork(ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY), detail)
    } catch (_: FileNotFoundException) {
        null
    }
}

private fun findCurrentArtwork(
    cache: DiskCache?,
    url: String,
    initial: SizedArtwork? = null,
    minimumDetail: Int = 1,
): SizedArtwork? {
    var best = initial
    try {
        for (key in (listOf(url) + artworkCacheCandidates(url, 1)).distinct()) {
            val bestDetail = best?.detail ?: 0
            if (bestDetail >= maxOf(CURRENT_ARTWORK_TARGET_PX, minimumDetail)) break
            cache?.openSnapshot(key)?.use {
                // Open the descriptor before releasing the snapshot so eviction cannot race the open.
                val candidate = it.data.toFile().openSizedArtwork(maxOf(minimumDetail, bestDetail + 1))
                if (candidate != null) {
                    best?.let { runCatching { it.descriptor.close() } }
                    best = candidate
                }
            }
        }
        return best
    } catch (error: CancellationException) {
        best?.let { runCatching { it.descriptor.close() } }
        throw error
    } catch (_: IOException) {
        return best
    } catch (error: Exception) {
        return best ?: throw error
    }
}

// Both the session and System UI can ask for the same cover. Do not repeatedly refresh a small source.
private class CurrentArtworkUpgradeCooldown {
    private var lastAttempt: Pair<String, Long>? = null

    @Synchronized
    fun tryStart(
        key: String,
        nowMs: Long,
    ): Boolean {
        val previous = lastAttempt
        if (previous != null && previous.first == key && nowMs - previous.second < CURRENT_ARTWORK_RETRY_INTERVAL_MS) return false
        lastAttempt = key to nowMs
        return true
    }
}

private suspend fun preferCurrentArtwork(
    fallback: ParcelFileDescriptor,
    shouldUpgrade: () -> Boolean,
    upgrade: suspend () -> ParcelFileDescriptor?,
): ParcelFileDescriptor {
    try {
        if (!shouldUpgrade()) return fallback
        val upgraded = upgrade() ?: return fallback
        runCatching { fallback.close() }
        return upgraded
    } catch (error: CancellationException) {
        runCatching { fallback.close() }
        throw error
    } catch (_: Exception) {
        // A failed quality upgrade must not hide an already readable cover.
        return fallback
    }
}
