@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocaleList
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.LayoutDirection
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.localeRegionQualifier
import com.metrolist.shared.settings.mapIosDeviceSettings
import com.metrolist.shared.settings.resolveAppLanguageTag
import com.metrolist.shared.ui.theme.IosFontManager
import com.metrolist.shared.utils.IosApplicationLog
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.compose.resources.ComposeEnvironment
import org.jetbrains.compose.resources.DefaultComposeEnvironment
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.LocalComposeEnvironment
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ScriptQualifier
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageDirectionRightToLeft
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.characterDirectionForLanguage
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController

private const val STATUS_BAR_VISIBILITY_NOTIFICATION = "com.metrolist.statusBarVisibilityChanged"

@OptIn(ExperimentalResourceApi::class, InternalResourceApi::class)
@Composable
actual fun IosAppearanceProvider(content: @Composable () -> Unit) {
    val settings = Settings.shared
    val baseEnvironment = DefaultComposeEnvironment.rememberEnvironment()
    val selectedLanguage = settings.appLanguage
    val systemLanguageTags = LocalLocaleList.current.map(Locale::toLanguageTag)
    val languageTag = resolveAppLanguageTag(selectedLanguage, systemLanguageTags)
    val layoutDirection =
        if (languageTag.isBlank()) {
            LocalLayoutDirection.current
        } else if (NSLocale.characterDirectionForLanguage(languageTag) == NSLocaleLanguageDirectionRightToLeft) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    val resourceEnvironment =
        remember(languageTag, baseEnvironment) {
            if (languageTag.isBlank()) {
                null
            } else {
                val locale = Locale(languageTag)
                ResourceEnvironment(
                    LanguageQualifier(locale.language),
                    ScriptQualifier(locale.script),
                    RegionQualifier(localeRegionQualifier(languageTag)),
                    baseEnvironment.theme,
                    baseEnvironment.density,
                )
            }
        }
    val composeEnvironment =
        remember(resourceEnvironment) {
            resourceEnvironment?.let(::FixedComposeEnvironment) ?: DefaultComposeEnvironment
        }

    // Compose Resources has no public locale provider; this environment controls generated resources.
    CompositionLocalProvider(
        LocalComposeEnvironment provides composeEnvironment,
        LocalLayoutDirection provides layoutDirection,
        content = content,
    )
}

@Composable
actual fun IosPlayerVisibilityEffect(
    expandedPlayerVisible: Boolean,
    lyricsVisible: Boolean,
    lyricsFullscreenVisible: Boolean,
    isPlaying: Boolean,
) {
    val settings = Settings.shared
    val deviceSettings =
        mapIosDeviceSettings(
            keepScreenOnMode = settings.keepScreenOnMode,
            expandedPlayerVisible = expandedPlayerVisible,
            lyricsVisible = lyricsVisible,
            lyricsFullscreenVisible = lyricsFullscreenVisible,
            isPlaying = isPlaying,
            immersivePlayer = settings.immersivePlayer,
            hideStatusBarInLyricsFullscreen = settings.lyricsHideStatusBarInFullscreen,
        )
    IosKeepScreenOnEffect(deviceSettings.keepScreenAwake)
    val application = UIApplication.sharedApplication
    var appIsActive by remember { mutableStateOf(application.applicationState == UIApplicationState.UIApplicationStateActive) }
    DisposableEffect(application) {
        val notifications = NSNotificationCenter.defaultCenter
        val resignObserver =
            notifications.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
                appIsActive = false
            }
        val activeObserver =
            notifications.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                appIsActive = true
            }
        onDispose {
            notifications.removeObserver(resignObserver)
            notifications.removeObserver(activeObserver)
            setStatusBarHidden(false)
        }
    }
    SideEffect { setStatusBarHidden(appIsActive && deviceSettings.hideStatusBar) }
}

private object IosScreenAwake {
    private var holders = 0
    private var original = false
    private var appIsActive = false
    private var resignObserver: Any? = null
    private var activeObserver: Any? = null

    private fun update() {
        UIApplication.sharedApplication.idleTimerDisabled =
            original || (holders > 0 && appIsActive)
    }

    fun acquire() {
        if (holders++ == 0) {
            val application = UIApplication.sharedApplication
            original = application.idleTimerDisabled
            appIsActive = application.applicationState == UIApplicationState.UIApplicationStateActive
            val notifications = NSNotificationCenter.defaultCenter
            resignObserver =
                notifications.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
                    appIsActive = false
                    update()
                }
            activeObserver =
                notifications.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                    appIsActive = true
                    update()
                }
        }
        update()
    }

    fun release() {
        if (--holders == 0) {
            val notifications = NSNotificationCenter.defaultCenter
            resignObserver?.let(notifications::removeObserver)
            activeObserver?.let(notifications::removeObserver)
            resignObserver = null
            activeObserver = null
        }
        update()
    }
}

@Composable
internal fun IosKeepScreenOnEffect(enabled: Boolean) {
    DisposableEffect(enabled) {
        if (enabled) IosScreenAwake.acquire()
        onDispose { if (enabled) IosScreenAwake.release() }
    }
}

@Composable
actual fun rememberIosFontActions(
    chooseTitle: String,
    chooseDescription: String,
    resetTitle: String,
    resetDescription: String,
    onImportResult: (String?) -> Unit,
): List<SettingsHostAction> {
    val controller = LocalUIViewController.current
    val settings = Settings.shared
    return listOf(
        SettingsHostAction(
            id = "iosFontChoose",
            title = chooseTitle,
            description = settings.iosCustomFontName.ifBlank { chooseDescription },
            onClick = { IosNativeFiles.pickFont(controller, onImportResult) },
        ),
        SettingsHostAction(
            id = "iosFontReset",
            title = resetTitle,
            description = resetDescription,
            enabled = settings.iosCustomFontFile.isNotBlank(),
            onClick = { IosFontManager.reset() },
        ),
    )
}

@Composable
actual fun rememberIosLogExportAction(
    title: String,
    description: String,
): SettingsHostAction {
    val controller = LocalUIViewController.current
    return SettingsHostAction(
        id = "iosExportLogs",
        title = title,
        description = description,
        onClick = {
            val applicationLog = IosApplicationLog.exportText()
            val playbackLog = PlaybackEventLog.exportText()
            val content = if (applicationLog.isBlank()) playbackLog else "$playbackLog\n\n$applicationLog"
            IosNativeFiles.shareTextFile(controller, "metrolist-diagnostics.txt", content)
        },
    )
}

actual fun applyIosAppLanguage(language: String) {
    Settings.shared.appLanguage = language
}

private class FixedComposeEnvironment(
    private val environment: ResourceEnvironment,
) : ComposeEnvironment {
    @Composable
    override fun rememberEnvironment(): ResourceEnvironment = environment
}

@OptIn(ExperimentalForeignApi::class)
private fun setStatusBarHidden(hidden: Boolean) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        STATUS_BAR_VISIBILITY_NOTIFICATION,
        `object` = NSNumber(bool = hidden),
    )
}
