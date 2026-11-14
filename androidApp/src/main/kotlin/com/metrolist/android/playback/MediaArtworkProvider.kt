package com.metrolist.android.playback

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import coil3.disk.DiskCache
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.shared.ui.components.libraryCover
import com.metrolist.shared.utils.artworkCacheCandidates
import com.metrolist.shared.utils.canonicalArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

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
        val url =
            currentArtwork?.takeIf { it.first == key }?.second
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
