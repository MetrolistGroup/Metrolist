package com.metrolist.desktop.utils

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistCoverImageTest {
    @Test
    fun coverPickerFiltersImagesAndRetainsDirectoryNavigation() {
        val root = Files.createTempDirectory("playlist-cover-filter").toFile()
        try {
            root.resolve("pictures").mkdir()
            assertTrue(playlistCoverFilenameFilter.accept(root, "pictures"))
            listOf("cover.PNG", "cover.jpeg", "cover.webp", "cover.gif", "cover.avif", "cover.heic").forEach {
                assertTrue(playlistCoverFilenameFilter.accept(root, it), it)
            }
            listOf("shell.qml", "cover.png.txt", "cover", "cover.svg").forEach {
                assertFalse(playlistCoverFilenameFilter.accept(root, it), it)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun validatesImageAndRejectsOversizedOrInvalidFiles() {
        val root = Files.createTempDirectory("playlist-cover").toFile()
        val file = root.resolve("cover.png")
        try {
            Bitmap().use { bitmap ->
                bitmap.allocN32Pixels(32, 32)
                bitmap.erase(0xFF336699.toInt())
                Image.makeFromBitmap(bitmap).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
                }
            }
            assertContentEquals(file.readBytes(), readPlaylistCover(file))
            val oversizedDimensions = file.readBytes()
            ByteBuffer.wrap(oversizedDimensions).putInt(16, 8192).putInt(20, 8192)
            val checksum = CRC32().apply { update(oversizedDimensions, 12, 17) }.value
            ByteBuffer.wrap(oversizedDimensions).putInt(29, checksum.toInt())
            file.writeBytes(oversizedDimensions)
            assertFails { readPlaylistCover(file) }
            file.writeText("not an image")
            assertFails { readPlaylistCover(file) }
            RandomAccessFile(file, "rw").use { it.setLength(MAX_PLAYLIST_COVER_BYTES + 1L) }
            assertFails { readPlaylistCover(file) }
        } finally {
            root.deleteRecursively()
        }
    }
}
