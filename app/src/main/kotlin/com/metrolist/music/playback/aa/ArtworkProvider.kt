package com.metrolist.music.playback.aa

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Base64
import coil3.imageLoader
import timber.log.Timber
import java.io.FileNotFoundException

class ArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    companion object {
        private val ALLOWED_PACKAGES = setOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.gms",                 // Google Play Services
            "com.google.android.automotive",          // Android Automotive OS
        )

        private fun authority(context: Context): String =
            "${context.packageName}.aa.artwork"

        fun uriFor(context: Context, url: String, validationToken: String): Uri =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority(context))
                .appendPath(encodeUrl(url))
                .appendQueryParameter("v", validationToken)
                .build()

        private fun encodeUrl(url: String): String =
            Base64.encodeToString(
                url.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )

        private fun decodeUrl(encoded: String): String =
            try {
                String(
                    Base64.decode(
                        encoded,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                    ),
                    Charsets.UTF_8,
                )
            } catch (_: IllegalArgumentException) {
                throw FileNotFoundException("Invalid artwork key encoding")
            }
    }

    private fun enforceAllowedCaller() {
        val ctx = context ?: throw SecurityException("No context")
        val callingUid = Binder.getCallingUid()

        if (callingUid == android.os.Process.myUid()) return

        val pm = ctx.packageManager
        val callingPackages = pm.getPackagesForUid(callingUid) ?: emptyArray()

        val isAllowed = callingPackages.any { pkg ->
            pkg in ALLOWED_PACKAGES || pkg == ctx.packageName
        }

        if (!isAllowed) {
            throw SecurityException(
                "Caller ${callingPackages.joinToString()} is not allowed to access ArtworkProvider"
            )
        }
    }

    private fun <T> withArtworkFile(uri: Uri, block: (java.io.File) -> T): T {
        val ctx = context ?: throw FileNotFoundException("No context")
        val encoded = uri.lastPathSegment ?: throw FileNotFoundException("Missing artwork key")
        val url = decodeUrl(encoded)

        val diskCache = ctx.imageLoader.diskCache
            ?: throw FileNotFoundException("Coil disk cache unavailable")

        val snapshot = diskCache.openSnapshot(url)
            ?: throw FileNotFoundException("Artwork not found in Coil cache")

        try {
            val file = snapshot.data.toFile()
            if (!file.exists() || file.length() == 0L) {
                throw FileNotFoundException("Artwork file is empty")
            }

            val expected = uri.getQueryParameter("v")
                ?: throw FileNotFoundException("Missing validation token")
            if (expected != "${file.length()}:${file.lastModified()}") {
                throw FileNotFoundException("Artwork replaced after validation")
            }

            return block(file)
        } finally {
            snapshot.close()
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        Timber.tag("ArtworkProvider")
            .d("openFile called: uri=$uri, mode=$mode, callingUid=${Binder.getCallingUid()}")

        enforceAllowedCaller()
        if (mode != "r") {
            throw FileNotFoundException("Only read mode is supported")
        }

        return withArtworkFile(uri) { file ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        Timber.tag("ArtworkProvider")
            .d("openAssetFile called: uri=$uri, mode=$mode, callingUid=${Binder.getCallingUid()}")

        enforceAllowedCaller()
        if (mode != "r") {
            throw FileNotFoundException("Only read mode is supported")
        }

        return withArtworkFile(uri) { file ->
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(pfd, 0, file.length())
        }
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

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