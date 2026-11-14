package com.metrolist.shared.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.isLowPowerModeEnabled
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages
import platform.Foundation.systemLocale
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

actual fun restartApp() {}

internal fun formatHistoryMonth(
    playedAt: Long,
    locale: NSLocale = NSLocale.currentLocale,
): String =
    NSDateFormatter()
        .apply {
            this.locale = locale
            setLocalizedDateFormatFromTemplate("yMMMM")
        }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(playedAt / 1000.0))

actual fun getUrlHost(url: String): String? =
    runCatching {
        NSURL.URLWithString(url)?.host
    }.getOrNull()
        ?: runCatching {
            url
                .substringAfter("://")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore(":")
                .takeIf { it.isNotBlank() }
        }.getOrNull()

@OptIn(ExperimentalForeignApi::class)
actual fun getDeviceDisplayMaxVideoHeight(): Int {
    val isPowerSave =
        runCatching {
            NSProcessInfo.processInfo.isLowPowerModeEnabled()
        }.getOrDefault(false)
    if (isPowerSave) return POWER_SAVE_MAX_VIDEO_HEIGHT

    return runCatching {
        val (width, height) =
            UIScreen.mainScreen.nativeBounds.useContents { size.width to size.height }
        val maxDim = maxOf(width, height)
        val minDim = minOf(width, height)
        when {
            maxDim >= 3840.0 || minDim >= 2160.0 -> 2160
            maxDim >= 2560.0 || minDim >= 1440.0 -> 1440
            maxDim >= 1920.0 || minDim >= 1080.0 -> 1080
            maxDim >= 1280.0 || minDim >= 720.0 -> 720
            maxDim > 0.0 -> 480
            else -> DEFAULT_MAX_VIDEO_HEIGHT
        }
    }.getOrDefault(DEFAULT_MAX_VIDEO_HEIGHT)
}

private const val DEFAULT_MAX_VIDEO_HEIGHT = 1080
private const val POWER_SAVE_MAX_VIDEO_HEIGHT = 720

@OptIn(ExperimentalNativeApi::class)
actual fun getAppArchitecture(): String = Platform.cpuArchitecture.name

actual val isPullToRefreshSupported: Boolean = true

actual val isDesktop: Boolean = false

actual fun openUrl(url: String) {
    tryOpenUrl(url) {}
}

actual fun tryOpenUrl(
    url: String,
    onResult: (Boolean) -> Unit,
) {
    val nsUrl = NSURL.URLWithString(url)
    if (nsUrl == null) {
        onResult(false)
        return
    }
    NSOperationQueue.mainQueue.addOperationWithBlock {
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = onResult)
    }
}

actual fun getSystemLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.takeIf { it.isNotBlank() }
        ?: NSLocale.currentLocale.localeIdentifier
            .substringBefore('_')
            .substringBefore('-')
            .takeIf { it.isNotBlank() }
        ?: NSLocale.systemLocale.localeIdentifier
            .substringBefore('_')
            .substringBefore('-')
