package com.metrolist.android.utils

import android.app.Application
import com.metrolist.shared.settings.Settings

object SentryManager {
    fun initialize(application: Application) = Unit

    fun setEnabled(enabled: Boolean) {
        Settings.shared.sentryEnabled = enabled
    }
}
