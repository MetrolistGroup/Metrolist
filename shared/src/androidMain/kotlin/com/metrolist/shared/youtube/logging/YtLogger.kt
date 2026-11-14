package com.metrolist.shared.youtube.logging

import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import timber.log.Timber

actual object YtLogger {
    actual fun d(
        tag: String,
        message: String,
    ) {
        PlaybackEventLog.captureYtLogger(PlaybackEventLevel.DEBUG, tag, message)
        Timber.tag(tag).d(message)
    }

    actual fun w(
        tag: String,
        message: String,
    ) {
        PlaybackEventLog.captureYtLogger(PlaybackEventLevel.WARN, tag, message)
        Timber.tag(tag).w(message)
    }

    actual fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        PlaybackEventLog.captureYtLogger(PlaybackEventLevel.ERROR, tag, message, throwable)
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }
}
