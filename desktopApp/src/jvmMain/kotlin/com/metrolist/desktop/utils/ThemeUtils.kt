package com.metrolist.desktop.utils

import com.metrolist.shared.settings.Settings
import dev.nucleusframework.darkmodedetector.IDarkModeDetector
import dev.nucleusframework.darkmodedetector.getPlatformDarkModeDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import java.util.function.Consumer

object ThemeUtils {
    suspend fun observeSystemTheme(detectorFactory: () -> IDarkModeDetector = ::getPlatformDarkModeDetector) {
        val detector =
            try {
                detectorFactory()
            } catch (_: LinkageError) {
                return
            } catch (_: Exception) {
                return
            }
        try {
            Settings.shared.systemInDarkTheme = detector.isDark()
        } catch (_: LinkageError) {
        } catch (_: Exception) {
        }
        val listener = Consumer<Boolean> { isDark -> Settings.shared.systemInDarkTheme = isDark }
        try {
            detector.registerListener(listener)
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: LinkageError) {
        } catch (_: Exception) {
        } finally {
            try {
                detector.removeListener(listener)
            } catch (_: LinkageError) {
            } catch (_: Exception) {
            }
        }
    }

    fun getCurrentThemeIsDark(): Boolean = Settings.shared.getCurrentThemeIsDark()
}
