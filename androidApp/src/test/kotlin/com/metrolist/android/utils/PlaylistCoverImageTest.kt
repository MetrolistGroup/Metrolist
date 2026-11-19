package com.metrolist.android.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistCoverImageTest {
    @Test
    @Config(sdk = [27, 33])
    fun importedJpegPreservesAllExifOrientationsAfterResizing() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expectedCorners =
            listOf(
                listOf(0, 1, 2, 3),
                listOf(1, 0, 3, 2),
                listOf(3, 2, 1, 0),
                listOf(2, 3, 0, 1),
                listOf(0, 2, 1, 3),
                listOf(2, 0, 3, 1),
                listOf(3, 1, 2, 0),
                listOf(1, 3, 0, 2),
            )
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) bitmap.setPixel(x, y, colors[(if (y < 512) 0 else 2) + if (x < 1024) 0 else 1])
        }
        val source = File.createTempFile("oriented-cover", ".jpg", RuntimeEnvironment.getApplication().cacheDir)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            for (orientation in 1..8) {
                ExifInterface(source.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val output = source.inputStream().use(::readPlaylistCover)
                val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
                try {
                    assertEquals("width for orientation $orientation", if (orientation >= 5) 512 else 1024, decoded.width)
                    assertEquals("height for orientation $orientation", if (orientation >= 5) 1024 else 512, decoded.height)
                    for ((corner, expected) in expectedCorners[orientation - 1].withIndex()) {
                        val pixel =
                            decoded.getPixel(
                                decoded.width * (
                                    if (corner % 2 ==
                                        0
                                    ) {
                                        1
                                    } else {
                                        3
                                    }
                                ) / 4,
                                decoded.height * (if (corner < 2) 1 else 3) / 4,
                            )
                        val color = colors[expected]
                        assertEquals(
                            "red for orientation $orientation, corner $corner",
                            Color.red(color).toDouble(),
                            Color.red(pixel).toDouble(),
                            15.0,
                        )
                        assertEquals(
                            "green for orientation $orientation, corner $corner",
                            Color.green(color).toDouble(),
                            Color.green(pixel).toDouble(),
                            15.0,
                        )
                        assertEquals(
                            "blue for orientation $orientation, corner $corner",
                            Color.blue(color).toDouble(),
                            Color.blue(pixel).toDouble(),
                            15.0,
                        )
                    }
                } finally {
                    decoded.recycle()
                }
            }
        } finally {
            bitmap.recycle()
            source.delete()
        }
    }

    @Test
    fun unboundedProvidersAreRejectedAfterTheByteLimit() {
        var read = 0
        val input =
            object : InputStream() {
                override fun read(): Int {
                    read++
                    return 0
                }
            }
        assertThrows(IllegalArgumentException::class.java) { readPlaylistCover(input) }
        assertEquals(MAX_PLAYLIST_COVER_BYTES + 1, read)
    }

    @Test
    @Config(sdk = [27, 33])
    fun invalidImagesAreRejectedAndValidImagesAreResized() {
        assertThrows(Exception::class.java) { readPlaylistCover(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val input = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val output = readPlaylistCover(ByteArrayInputStream(input))
        val decoded = BitmapFactory.decodeByteArray(output, 0, output.size)
        assertEquals(1024, decoded.width)
        assertEquals(512, decoded.height)
        decoded.recycle()
    }
}
