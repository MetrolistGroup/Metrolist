package com.metrolist.shared.youtube.logging

import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog

actual object YtLogger {
    actual fun d(
        tag: String,
        message: String,
    ) {
        PlaybackEventLog.captureYtLogger(PlaybackEventLevel.DEBUG, tag, message)
    }

    actual fun w(
        tag: String,
        message: String,
    ) {
        PlaybackEventLog.captureYtLogger(PlaybackEventLevel.WARN, tag, message)
        println("W/$tag: $message")
    }

    actual fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        PlaybackEventLog.captureYtLogger(PlaybackEventLevel.ERROR, tag, message, throwable)
        println("E/$tag: $message")
        throwable?.printStackTrace()
    }
}
