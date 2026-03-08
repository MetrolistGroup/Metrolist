package com.metrolist.shared.discord

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIDevice

actual fun getSystemLocale(): String = NSLocale.currentLocale.localeIdentifier

actual val platformOs: String = "iOS"
actual val platformBrowser: String = "Discord iOS"
actual val platformDevice: String = UIDevice.currentDevice.model
actual val platformOsVersion: String = UIDevice.currentDevice.systemVersion
actual val platformOsSdkVersion: String = platformOsVersion
actual val platformUserAgent: String = "Discord-iOS/314013;RNA"
