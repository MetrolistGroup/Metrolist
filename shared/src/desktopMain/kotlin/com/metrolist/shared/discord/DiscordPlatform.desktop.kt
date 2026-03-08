package com.metrolist.shared.discord

import java.util.Locale

private val osName = checkNotNull(System.getProperty("os.name")) { "JVM operating system name is unavailable" }

actual fun getSystemLocale(): String = Locale.getDefault().toString()

actual val platformOs: String =
    when {
        osName.contains("win", ignoreCase = true) -> "Windows"
        osName.contains("mac", ignoreCase = true) -> "Mac OS X"
        else -> "Linux"
    }

actual val platformBrowser: String =
    when (platformOs) {
        "Windows" -> "Discord Windows"
        "Mac OS X" -> "Discord Mac"
        else -> "Discord Linux"
    }
actual val platformDevice: String = "Metrolist Desktop"
actual val platformOsVersion: String = checkNotNull(System.getProperty("os.version")) { "JVM operating system version is unavailable" }
actual val platformOsSdkVersion: String = platformOsVersion
actual val platformUserAgent: String =
    when (platformOs) {
        "Windows" -> "Discord-Windows/314013;RNA"
        "Mac OS X" -> "Discord-Mac/314013;RNA"
        else -> "Discord-Linux/314013;RNA"
    }
