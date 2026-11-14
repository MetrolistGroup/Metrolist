package com.metrolist.android.widget

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetArtworkTest {
    @Test
    fun oversizedCachedArtworkHasBoundedBitmapMemoryForBothShapes() {
        val source = Bitmap.createBitmap(3_000, 2_000, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        for (render in listOf(::getRoundedCornerBitmap, ::getCookieBitmap)) {
            val result = render(source)
            assertEquals(300, result.width)
            assertEquals(300, result.height)
            assertTrue(result.allocationByteCount <= 300 * 300 * 4)
            assertEquals(Color.RED, result.getPixel(150, 150))
            assertEquals(Color.TRANSPARENT, result.getPixel(0, 0))
            assertFalse(source.isRecycled)
            result.recycle()
        }
        source.recycle()
    }

    @Test
    fun artworkKeepsItsCenterCropWithoutUpscalingSmallImages() {
        val source = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        for (x in 150 until 250) {
            for (y in 0 until 100) source.setPixel(x, y, Color.BLUE)
        }
        for (render in listOf(::getRoundedCornerBitmap, ::getCookieBitmap)) {
            val result = render(source)
            assertEquals(100, result.width)
            assertEquals(100, result.height)
            assertEquals(Color.BLUE, result.getPixel(1, 50))
            assertEquals(Color.BLUE, result.getPixel(98, 50))
            assertFalse(source.isRecycled)
            result.recycle()
        }
        source.recycle()
    }
}
