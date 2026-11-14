package com.metrolist.shared.settings

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults
import com.russhwolf.settings.Settings as KmpSettings

internal actual fun defaultKmpSettings(): KmpSettings {
    val defaults = NSUserDefaults.standardUserDefaults
    return IosSecureSettings(NSUserDefaultsSettings(defaults))
}
