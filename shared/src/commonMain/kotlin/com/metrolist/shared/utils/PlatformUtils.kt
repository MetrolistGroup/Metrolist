package com.metrolist.shared.utils

expect fun restartApp()

expect fun getUrlHost(url: String): String?

expect fun getDeviceDisplayMaxVideoHeight(): Int

expect val isPullToRefreshSupported: Boolean

expect val isDesktop: Boolean

expect fun openUrl(url: String)

expect fun tryOpenUrl(
    url: String,
    onResult: (Boolean) -> Unit,
)

expect fun getSystemLanguage(): String

expect fun getAppArchitecture(): String
