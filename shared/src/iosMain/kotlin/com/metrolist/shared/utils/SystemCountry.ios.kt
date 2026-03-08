package com.metrolist.shared.utils

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale

internal actual fun systemCountryCode(): String = NSLocale.currentLocale.countryCode.orEmpty()
