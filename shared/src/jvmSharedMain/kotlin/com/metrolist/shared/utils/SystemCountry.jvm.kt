package com.metrolist.shared.utils

import java.util.Locale

object SystemLocale {
    @Volatile
    var current: Locale = Locale.getDefault()
}

internal actual fun systemCountryCode(): String = SystemLocale.current.country
