package com.metrolist.shared.utils

import android.content.Context
import android.content.res.Resources
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32, 33])
class SystemLanguageTest {
    @Test
    fun appLanguageOverrideDoesNotChangeDeviceLanguage() {
        val originalContext = ReflectionHelpers.getStaticField<Context?>(AndroidContextProvider::class.java, "applicationContext")
        AndroidContextProvider.applicationContext = RuntimeEnvironment.getApplication()
        val original = Locale.getDefault()
        val systemLanguage =
            Resources
                .getSystem()
                .configuration.locales[0]
                .language
        try {
            Locale.setDefault(Locale.forLanguageTag(if (systemLanguage == "de") "pl" else "de"))
            assertEquals(systemLanguage, getSystemLanguage())
        } finally {
            ReflectionHelpers.setStaticField(AndroidContextProvider::class.java, "applicationContext", originalContext)
            Locale.setDefault(original)
        }
    }
}
