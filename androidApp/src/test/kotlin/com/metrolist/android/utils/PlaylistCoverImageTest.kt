package com.metrolist.android.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistCoverImageTest {
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
