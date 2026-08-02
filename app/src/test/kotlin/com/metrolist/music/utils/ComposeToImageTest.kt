package com.metrolist.music.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.ui.component.LyricsBackgroundStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeToImageTest {
    @Test
    fun createLyricsImage_clipsOutputToRoundedCorners() =
        runBlocking {
            val context = TestContext(ApplicationProvider.getApplicationContext())
            val bitmap =
                ComposeToImage.createLyricsImage(
                    context = context,
                    coverArtUrl = null,
                    songTitle = "",
                    artistName = "",
                    lyrics = "",
                    width = 2160,
                    height = 2160,
                    backgroundColor = Color.BLACK,
                    backgroundStyle = LyricsBackgroundStyle.SOLID,
                )

            val cornerPixels =
                listOf(
                    bitmap.getPixel(0, 0),
                    bitmap.getPixel(bitmap.width - 1, 0),
                    bitmap.getPixel(0, bitmap.height - 1),
                    bitmap.getPixel(bitmap.width - 1, bitmap.height - 1),
                )

            cornerPixels.forEach { pixel ->
                assertEquals(0, Color.alpha(pixel))
            }
            assertEquals(255, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
        }

    private class TestContext(base: Context) : ContextWrapper(base) {
        private val testResources = TestResources(base.resources)

        override fun getResources(): Resources = testResources
    }

    @Suppress("DEPRECATION")
    private class TestResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getDrawable(
            id: Int,
            theme: Theme?,
        ): Drawable = BitmapDrawable(this, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

        override fun getString(id: Int): String = "Metrolist"
    }
}
