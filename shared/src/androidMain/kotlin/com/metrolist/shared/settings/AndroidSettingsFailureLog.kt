/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import android.util.Log
import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog

internal fun logSettingsFailure(
    message: String,
    error: Throwable,
) {
    PlaybackEventLog.captureApplicationFailure(PlaybackEventLevel.ERROR, "Settings", message, error)
    Log.e("Settings", message, error)
}
