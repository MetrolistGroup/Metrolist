package com.metrolist.shared.utils

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.sanitizeIosDiagnosticMessage
import platform.Foundation.NSRecursiveLock

actual object Logger {
    private val enabled = BuildConfig.DEBUG

    actual fun d(
        tag: String,
        message: String,
    ) {
        if (enabled) println("D/$tag: $message")
        record("DEBUG", tag, message, null)
    }

    actual fun i(
        tag: String,
        message: String,
    ) {
        if (enabled) println("I/$tag: $message")
        record("INFO", tag, message, null)
    }

    actual fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        if (enabled) {
            println("W/$tag: $message")
            throwable?.printStackTrace()
        }
        record("WARN", tag, message, throwable)
    }

    actual fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        if (enabled) {
            println("E/$tag: $message")
            throwable?.printStackTrace()
        }
        record("ERROR", tag, message, throwable)
    }

    private fun record(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        if (enabled || Settings.shared.enableLogging) IosApplicationLog.append(level, tag, message, throwable)
    }
}

internal object IosApplicationLog {
    private const val MAX_LINES = 500
    private val lock = NSRecursiveLock()
    private val lines = ArrayDeque<String>()

    fun append(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) = withLock {
        val detail = throwable?.let { " (${it::class.simpleName ?: "error"})" }.orEmpty()
        lines.addLast("$level/${tag.take(80)}: ${sanitizeIosDiagnosticMessage(message).take(2_000)}$detail")
        if (lines.size > MAX_LINES) lines.removeFirst()
    }

    fun exportText(): String = withLock { lines.joinToString("\n").takeLast(MAX_EXPORT_CHARS) }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private const val MAX_EXPORT_CHARS = 128 * 1024
}
