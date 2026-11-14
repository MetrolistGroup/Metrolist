package com.metrolist.desktop.utils

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.File
import java.io.FilenameFilter

internal const val MAX_PLAYLIST_COVER_BYTES = 16 * 1024 * 1024
private const val MAX_PLAYLIST_COVER_PIXELS = 16_777_216L

private val playlistCoverExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "ico", "wbmp", "avif", "heif", "heic", "dng")

internal val playlistCoverFilenameFilter =
    FilenameFilter { directory, name ->
        File(directory, name).isDirectory ||
            name.substringAfterLast('.', "").lowercase() in playlistCoverExtensions
    }

internal fun readPlaylistCover(file: File): ByteArray {
    val bytes = file.inputStream().use { it.readNBytes(MAX_PLAYLIST_COVER_BYTES + 1) }
    require(bytes.size <= MAX_PLAYLIST_COVER_BYTES)
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            val info = codec.imageInfo
            require(info.width > 0 && info.height > 0 && info.width.toLong() * info.height <= MAX_PLAYLIST_COVER_PIXELS)
        }
    }
    return bytes
}
