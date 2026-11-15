package com.metrolist.shared.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29, 30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyArtworkBlurTest {
    @Test
    fun downsizesAndBlursWithoutChangingTheSource() =
        runTest {
            val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(512 * 256) { if (it % 512 < 256) Color.BLACK else Color.WHITE }
            source.setPixels(pixels, 0, 512, 0, 0, 512, 256)
            val result = legacyArtworkBlur(source, 8).asAndroidBitmap()

            assertEquals(128, result.width)
            assertEquals(64, result.height)
            assertTrue(Color.red(result.getPixel(63, 32)) in 1..254)
            assertTrue(Color.red(result.getPixel(64, 32)) in 1..254)
            assertEquals(Color.BLACK, source.getPixel(255, 128))
            assertEquals(Color.WHITE, source.getPixel(256, 128))
            assertFalse(source.isRecycled)
        }

    @Test
    fun sharesConcurrentRequestsAndKeysTheCacheByArtworkAndRadius() =
        runTest {
            val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.RED)
            val first = async { legacyArtworkBlur(source, 4) }
            val second = async { legacyArtworkBlur(source, 4) }
            val result = first.await()
            assertSame(result, second.await())
            assertSame(result, legacyArtworkBlur(source, 4))
            assertNotSame(result, legacyArtworkBlur(source, 8))
            source.eraseColor(Color.BLUE)
            val changed = legacyArtworkBlur(source, 4)
            assertNotSame(result, changed)
            assertEquals(Color.BLUE, changed.asAndroidBitmap().getPixel(64, 64))
            assertEquals(Color.RED, result.asAndroidBitmap().getPixel(64, 64))
        }

    @Test
    fun keepsNarrowAndTransparentArtworkValid() =
        runTest {
            val source = Bitmap.createBitmap(1, 512, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.TRANSPARENT)
            val result = legacyArtworkBlur(source, 16).asAndroidBitmap()
            assertEquals(1, result.width)
            assertEquals(128, result.height)
            assertEquals(Color.TRANSPARENT, result.getPixel(0, 64))
        }
}
