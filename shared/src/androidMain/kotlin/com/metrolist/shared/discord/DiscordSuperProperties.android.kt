package com.metrolist.shared.discord

import android.os.Build
import java.util.Locale

actual fun getSystemLocale(): String = Locale.getDefault().toString()

actual val platformOs: String = "Android"
actual val platformBrowser: String = "Discord Android"
actual val platformDevice: String = "KMP-Device"
actual val platformOsVersion: String = Build.VERSION.RELEASE
actual val platformOsSdkVersion: String = Build.VERSION.SDK_INT.toString()
actual val platformUserAgent: String = "Discord-Android/314013;RNA"
