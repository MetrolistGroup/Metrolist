package com.metrolist.shared.utils

actual object Logger {
    actual fun d(
        tag: String,
        message: String,
    ) {
        LogBridge.sink?.invoke("DEBUG", tag, message, null)
    }

    actual fun i(
        tag: String,
        message: String,
    ) {
        LogBridge.sink?.invoke("INFO", tag, message, null)
    }

    actual fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        LogBridge.sink?.invoke("WARN", tag, message, throwable)
    }

    actual fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        LogBridge.sink?.invoke("ERROR", tag, message, throwable)
    }
}
