package com.metrolist.shared.utils

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI
import java.util.Locale
import kotlin.system.exitProcess

actual fun restartApp() {
    exitProcess(0)
}

actual fun getUrlHost(url: String): String? = runCatching { URI(url).host }.getOrNull()

actual fun openUrl(url: String) {
    if (OsUtil.isWindows) {
        Desktop.getDesktop().browse(URI(url))
        return
    }
    val command =
        when {
            OsUtil.isLinux -> arrayOf("xdg-open", url)
            OsUtil.isMac -> arrayOf("open", url)
            else -> return
        }
    ProcessBuilder(*command).start()
}

actual fun tryOpenUrl(
    url: String,
    onResult: (Boolean) -> Unit,
) {
    if (runCatching {
            check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            Desktop.getDesktop().browse(URI(url))
        }.isSuccess
    ) {
        onResult(true)
        return
    }
    onResult((OsUtil.isLinux || OsUtil.isMac) && runCatching { openUrl(url) }.isSuccess)
}

/** Physical monitor sizes for windows that AWT cannot see, such as native Wayland. Empty when unknown. */
@Volatile
var headlessDisplaySizes: () -> List<Pair<Int, Int>> = { emptyList() }

actual fun getDeviceDisplayMaxVideoHeight(): Int =
    runCatching {
        if (GraphicsEnvironment.isHeadless()) {
            val largest = headlessDisplaySizes().maxByOrNull { (width, height) -> width.toLong() * height }
            return largest?.let { (width, height) -> videoHeightFor(width, height) } ?: DEFAULT_DESKTOP_MAX_VIDEO_HEIGHT
        }
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val defaultDevice = ge.defaultScreenDevice
        val mode = defaultDevice.displayMode
        val transform = defaultDevice.defaultConfiguration.defaultTransform
        val scale = maxOf(transform.scaleX, transform.scaleY, 1.0)
        videoHeightFor((mode.width * scale).toInt(), (mode.height * scale).toInt())
    }.getOrDefault(DEFAULT_DESKTOP_MAX_VIDEO_HEIGHT)

private fun videoHeightFor(
    physicalWidth: Int,
    physicalHeight: Int,
): Int {
    val maxDim = maxOf(physicalWidth, physicalHeight)
    val minDim = minOf(physicalWidth, physicalHeight)
    return when {
        maxDim >= 3840 || minDim >= 2160 -> 2160
        maxDim >= 2560 || minDim >= 1440 -> 1440
        maxDim >= 1920 || minDim >= 1080 -> 1080
        maxDim >= 1280 || minDim >= 720 -> 720
        else -> 480
    }
}

private const val DEFAULT_DESKTOP_MAX_VIDEO_HEIGHT = 2160

actual fun getAppArchitecture(): String = checkNotNull(System.getProperty("os.arch"))

actual val isPullToRefreshSupported: Boolean = false

actual val isDesktop: Boolean = true

val desktopSystemLocale: Locale = Locale.getDefault()

actual fun getSystemLanguage(): String = desktopSystemLocale.language
