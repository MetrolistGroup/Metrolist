package com.metrolist.shared.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopArtworkSeedColorTest {
    @Test
    fun skiaImagesAndBitmapsKeepTheirAccentChannels() {
        for (accent in listOf(Color(0xFF0088FF), Color(0xFFFFDD00))) {
            Bitmap().use { bitmap ->
                bitmap.allocN32Pixels(256, 256)
                bitmap.erase(Color.White.toArgb())
                Canvas(bitmap).use { canvas ->
                    Paint().use { paint ->
                        paint.color = Color.Black.toArgb()
                        canvas.drawRect(Rect.makeLTRB(160f, 0f, 224f, 256f), paint)
                        paint.color = accent.toArgb()
                        canvas.drawRect(Rect.makeLTRB(224f, 0f, 256f, 256f), paint)
                    }
                }
                val extractor = ThemeColorExtractor()
                assertEquals(accent, extractor.extractSeedColor(bitmap))
                Image.makeFromBitmap(bitmap).use { assertEquals(accent, extractor.extractSeedColor(it)) }
            }
        }
    }
}
