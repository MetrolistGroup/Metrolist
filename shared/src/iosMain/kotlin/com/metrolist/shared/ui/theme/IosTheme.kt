package com.metrolist.shared.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import com.metrolist.shared.settings.Settings
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.languageCode
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

@Composable
actual fun localeAwareSystemFontFamily(languageTag: String): FontFamily {
    val customFontFile = Settings.shared.iosCustomFontFile
    val customFontRevision = Settings.shared.iosCustomFontRevision
    return remember(languageTag, customFontFile, customFontRevision) {
        IosFontManager.loadFontFamily(customFontFile) ?: resolveLocaleAwareSystemFontFamily(languageTag)
    }
}

private fun resolveLocaleAwareSystemFontFamily(languageTag: String): FontFamily {
    val locale = NSLocale(languageTag)
    val languageCode = locale.languageCode
    val sample = locale.displayNameForKey(NSLocaleLanguageCode, languageCode) ?: return FontFamily.Default
    val manager = FontMgr.default
    val base = manager.matchFamilyStyle(null, FontStyle.NORMAL) ?: return FontFamily.Default
    val missingCodePoint = sample.firstOrNull { base.getUTF32Glyph(it.code).toInt() == 0 }?.code
    if (missingCodePoint == null) {
        base.close()
        return FontFamily.Default
    }

    val matched =
        manager.matchFamilyStyleCharacter(
            base.familyName,
            FontStyle.NORMAL,
            arrayOf(languageTag),
            missingCodePoint,
        )
    base.close()
    return matched?.let { FontFamily(Typeface(it)) } ?: FontFamily.Default
}

actual class ThemeColorExtractor {
    actual fun extractSeedColor(image: Any): Color = (image as? Bitmap)?.artworkSeedColor() ?: DefaultThemeColor

    actual fun extractGradientColors(
        image: Any,
        fallback: Color,
    ): List<Color> = getGradientColors((image as? Bitmap)?.artworkSeedColor() ?: fallback)

    actual fun isArtworkBright(image: Any): Boolean = (image as? Bitmap)?.argbPixels()?.let(::isArtworkBright) ?: false
}

private fun Bitmap.artworkSeedColor(): Color = selectArtworkSeedColor(width, height, ::getColor)

private fun Bitmap.argbPixels(): List<Int> =
    List(width * height) { index ->
        getColor(index % width, index / width)
    }

@Composable
internal actual fun rememberReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val notifications = NSNotificationCenter.defaultCenter
        val observer =
            notifications.addObserverForName(
                UIAccessibilityReduceMotionStatusDidChangeNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { reduced = UIAccessibilityIsReduceMotionEnabled() }
        onDispose { notifications.removeObserver(observer) }
    }
    return reduced
}
