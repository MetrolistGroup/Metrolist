package com.metrolist.shared.state

import platform.Foundation.ISOCountryCodes
import platform.Foundation.NSLocale

actual val contentCountryCodes: Set<String> by lazy { NSLocale.ISOCountryCodes.filterIsInstance<String>().toSet() }
