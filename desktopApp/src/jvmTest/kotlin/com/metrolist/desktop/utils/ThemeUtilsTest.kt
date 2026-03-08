package com.metrolist.desktop.utils

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.ThemeMode
import com.russhwolf.settings.PropertiesSettings
import dev.nucleusframework.darkmodedetector.IDarkModeDetector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Properties
import java.util.function.Consumer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeUtilsTest {
    private var previousSettings: Settings? = null

    @BeforeTest
    fun setUp() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(previousSettings)
    }

    @Test
    fun initialDetectionRunsInTheLaunchedJobAndGettersOnlyReadTheCache() =
        runTest {
            val detector = Detector()
            var created = false
            val job =
                backgroundScope.launch {
                    ThemeUtils.observeSystemTheme {
                        created = true
                        detector
                    }
                }
            assertFalse(created)
            assertFalse(ThemeUtils.getCurrentThemeIsDark())
            runCurrent()
            assertTrue(created)
            assertEquals(1, detector.reads)
            repeat(3) { assertTrue(ThemeUtils.getCurrentThemeIsDark()) }
            assertEquals(1, detector.reads)
            detector.listener?.accept(false)
            assertFalse(ThemeUtils.getCurrentThemeIsDark())
            job.cancelAndJoin()
            assertNull(detector.listener)
        }

    @Test
    fun explicitThemesKeepTheirSelectionWhileTheSystemCacheChanges() {
        for (dark in listOf(false, true)) {
            Settings.shared.systemInDarkTheme = dark
            for (mode in ThemeMode.entries) {
                Settings.shared.themeMode = mode
                val expected =
                    when (mode) {
                        ThemeMode.AUTO -> dark
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK, ThemeMode.PURE_BLACK -> true
                    }
                assertEquals(expected, ThemeUtils.getCurrentThemeIsDark())
            }
        }
    }

    @Test
    fun aFailedInitialReadKeepsTheCacheAndStillReceivesLiveUpdates() =
        runTest {
            Settings.shared.systemInDarkTheme = true
            val detector = Detector(failRead = true)
            val job = backgroundScope.launch { ThemeUtils.observeSystemTheme { detector } }
            runCurrent()
            assertTrue(ThemeUtils.getCurrentThemeIsDark())
            detector.listener?.accept(false)
            assertFalse(ThemeUtils.getCurrentThemeIsDark())
            job.cancelAndJoin()
            ThemeUtils.observeSystemTheme { throw UnsatisfiedLinkError("No native detector") }
            assertFalse(ThemeUtils.getCurrentThemeIsDark())
        }

    private class Detector(
        private val failRead: Boolean = false,
    ) : IDarkModeDetector {
        var reads = 0
        var listener: Consumer<Boolean>? = null

        override fun isDark(): Boolean {
            reads++
            check(!failRead)
            return true
        }

        override fun registerListener(listener: Consumer<Boolean>) {
            this.listener = listener
        }

        override fun removeListener(listener: Consumer<Boolean>) {
            if (this.listener === listener) this.listener = null
        }
    }
}
