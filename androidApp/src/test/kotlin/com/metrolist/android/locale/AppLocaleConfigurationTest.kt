package com.metrolist.android.locale

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.ui.unit.Density
import com.metrolist.shared.ui.theme.appearanceDensity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class AppLocaleConfigurationTest {
    private val originalLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun languageOverrideInheritsTheLaunchingWindowDisplayConfiguration() {
        val context = RuntimeEnvironment.getApplication()
        context
            .getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .edit()
            .putString("language", "de")
            .commit()
        var capturedConfiguration: Configuration? = null
        val recordingContext =
            object : ContextWrapper(context) {
                override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
                    capturedConfiguration = Configuration(overrideConfiguration)
                    return super.createConfigurationContext(overrideConfiguration)
                }
            }
        AppLocale.wrap(recordingContext)
        val localeOverride = requireNotNull(capturedConfiguration)
        assertEquals(Locale.forLanguageTag("de"), localeOverride.locales[0])

        // A background service and a visible Activity can receive different display configurations.
        for ((dpi, width, fontScale) in listOf(Triple(160, 320, 1f), Triple(480, 412, 1.5f))) {
            val windowConfiguration =
                Configuration(context.resources.configuration).apply {
                    densityDpi = dpi
                    screenWidthDp = width
                    screenHeightDp = width * 2
                    smallestScreenWidthDp = width
                    this.fontScale = fontScale
                }
            val localized = Configuration(windowConfiguration).apply { updateFrom(localeOverride) }
            assertEquals(dpi, localized.densityDpi)
            assertEquals(width, localized.screenWidthDp)
            assertEquals(width * 2, localized.screenHeightDp)
            assertEquals(width, localized.smallestScreenWidthDp)
            assertEquals(fontScale, localized.fontScale, 0f)
            assertEquals(Locale.forLanguageTag("de"), localized.locales[0])
            assertEquals(windowConfiguration.appearanceDensity(), localized.appearanceDensity())
        }
    }

    private fun Configuration.appearanceDensity(): Density =
        appearanceDensity(
            systemDensity = Density(densityDpi / 160f, fontScale),
            smallestDimensionDp = smallestScreenWidthDp.toFloat(),
            availableWidthDp = screenWidthDp.toFloat(),
            enableLandscapeScaling = true,
            userDensityScale = 1f,
        )
}
