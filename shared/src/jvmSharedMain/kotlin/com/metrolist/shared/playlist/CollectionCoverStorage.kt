package com.metrolist.shared.playlist

import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

suspend fun storeCollectionCover(
    type: String,
    directory: File,
    readImage: (() -> ByteArray)?,
    settings: Settings = Settings.shared,
) {
    val oldPath = settings.collectionCovers[type]
    var created: File? = null
    try {
        withContext(Dispatchers.IO) {
            if (readImage != null) {
                val bytes = readImage()
                require(bytes.isNotEmpty())
                directory.mkdirs()
                created = File.createTempFile("cover_", ".jpg", directory)
                created!!.writeBytes(bytes)
            }
        }
        settings.setCollectionCover(type, created?.absolutePath)
        created = null
        withContext(Dispatchers.IO) {
            oldPath?.let(::File)?.takeIf { it.parentFile == directory }?.delete()
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
    }
}
