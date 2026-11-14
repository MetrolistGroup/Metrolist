package com.metrolist.shared.utils

import android.app.LocaleManager
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.AndroidContextProvider

actual fun restartApp() {
    val context = AndroidContextProvider.applicationContext
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    Settings.shared.flush()
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(launchIntent)
}

actual fun getUrlHost(url: String): String? =
    runCatching {
        url
            .toUri()
            .host
    }.getOrNull()
        ?: runCatching { java.net.URI(url).host }.getOrNull()
        ?: runCatching {
            url
                .substringAfter("://")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore(":")
                .takeIf { it.isNotBlank() }
        }.getOrNull()

actual fun getDeviceDisplayMaxVideoHeight(): Int {
    val context = runCatching { AndroidContextProvider.applicationContext }.getOrNull() ?: return DEFAULT_MAX_VIDEO_HEIGHT

    val isPowerSave =
        runCatching {
            context.getSystemService<PowerManager>()?.isPowerSaveMode == true
        }.getOrDefault(false)
    if (isPowerSave) return POWER_SAVE_MAX_VIDEO_HEIGHT

    val displayMetrics = context.resources.displayMetrics
    val maxDim = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
    val minDim = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels)

    return when {
        maxDim >= 3840 || minDim >= 2160 -> 2160
        maxDim >= 2560 || minDim >= 1440 -> 1440
        maxDim >= 1920 || minDim >= 1080 -> 1080
        maxDim >= 1280 || minDim >= 720 -> 720
        else -> 480
    }
}

private const val DEFAULT_MAX_VIDEO_HEIGHT = 1080
private const val POWER_SAVE_MAX_VIDEO_HEIGHT = 720

actual fun getAppArchitecture(): String = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).first()

actual val isPullToRefreshSupported: Boolean = true

actual val isDesktop: Boolean = false

actual fun openUrl(url: String) {
    tryOpenUrl(url) {}
}

actual fun tryOpenUrl(
    url: String,
    onResult: (Boolean) -> Unit,
) {
    val opened =
        runCatching {
            val context = AndroidContextProvider.applicationContext
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    onResult(opened)
}

actual fun getSystemLanguage(): String {
    val systemLocales =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AndroidContextProvider.applicationContext.getSystemService(LocaleManager::class.java)?.systemLocales
        } else {
            null
        }
    return (systemLocales?.takeUnless { it.isEmpty } ?: Resources.getSystem().configuration.locales)[0].language
}
