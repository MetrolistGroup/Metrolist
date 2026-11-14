/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.utils.LogBridge
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds

object AppLogger {
    private const val MAX_LOG_SIZE = 10 * 1024 * 1024
    private const val MAX_LOG_FILES = 5
    private const val LOG_BUFFER_SIZE = 1000
    private val sensitiveQueryRegex =
        Regex("""(?i)([?&](?:pot|sig|signature|n|ns|spc|lsig|token|key|auth|cookie)=)[^&\s]+""")
    private val sensitiveHeaderRegex = Regex("""(?i)\b(cookie|authorization)\s*[:=]\s*[^\r\n]+""")
    private val urlCredentialsRegex = Regex("""(?i)(https?://)[^/@\s]+@""")
    private val ipAddressRegex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    private var logDir = File(XdgUtils.getXdgStateHome(), "metrolist/logs")
    private val logFile get() = File(logDir, "app.log")
    private val bufferLock = Any()
    private var logBuffer = ArrayDeque<LogEntry>(LOG_BUFFER_SIZE)
    private val isInitialized = AtomicBoolean(false)
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var flushJob: Job? = null
    private var scope: CoroutineScope? = null
    private val detailedLoggingEnabled = AtomicBoolean(false)

    private data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
        val consoleTarget: PrintStream? = null,
    )

    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARNING,
        ERROR,
    }

    fun initialize(
        coroutineScope: CoroutineScope,
        logDirectory: File = File(XdgUtils.getXdgStateHome(), "metrolist/logs"),
        writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onUncaughtException: (() -> Unit)? = null,
    ) {
        if (isInitialized.getAndSet(true)) return

        scope = coroutineScope
        logDir = logDirectory
        logDir.mkdirs()
        originalOut = System.out
        originalErr = System.err

        System.setOut(createTeePrintStream(originalOut, Level.INFO, "STDOUT"))
        System.setErr(createTeePrintStream(originalErr, Level.WARNING, "STDERR"))

        LogBridge.sink = { level, tag, message, throwable ->
            when (level) {
                "VERBOSE" -> {
                    verbose(tag, message)
                }

                "DEBUG" -> {
                    debug(tag, message)
                }

                "INFO" -> {
                    info(tag, message)
                }

                "WARN" -> {
                    if (throwable != null) {
                        warning(tag, message, throwable)
                    } else {
                        warning(tag, message)
                    }
                }

                "ERROR" -> {
                    if (throwable != null) {
                        error(tag, message, throwable)
                    } else {
                        error(tag, message)
                    }
                }
            }
            if (tag == "FFmpeg" && (level == "WARN" || level == "ERROR")) flush()
        }

        flushJob =
            scope?.launch(writerDispatcher) {
                while (true) {
                    delay(250.milliseconds)
                    flush()
                }
            }

        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                error("UncaughtException", "Uncaught exception type=${throwable::class.simpleName ?: "Throwable"}", throwable)
                if (!BuildConfig.DEBUG) originalErr?.println(sanitize(getStackTrace(throwable)))
                flush()
            } finally {
                if (onUncaughtException != null) {
                    runCatching(onUncaughtException)
                } else {
                    defaultUncaughtExceptionHandler?.uncaughtException(thread, throwable)
                }
            }
        }
        flush()
    }

    fun setEnabled(enabled: Boolean) {
        detailedLoggingEnabled.set(enabled)
    }

    fun shutdown() {
        flushJob?.cancel()
        flush()
        isInitialized.set(false)
    }

    fun verbose(
        tag: String,
        message: String,
    ) {
        log(Level.VERBOSE, tag, message)
    }

    fun debug(
        tag: String,
        message: String,
    ) {
        log(Level.DEBUG, tag, message)
    }

    fun info(
        tag: String,
        message: String,
    ) {
        log(Level.INFO, tag, message)
    }

    fun warning(
        tag: String,
        message: String,
    ) {
        log(Level.WARNING, tag, message)
    }

    fun warning(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        log(Level.WARNING, tag, message, throwable)
    }

    fun error(
        tag: String,
        message: String,
    ) {
        log(Level.ERROR, tag, message)
    }

    fun error(
        tag: String,
        message: String,
        throwable: Throwable,
    ) {
        log(Level.ERROR, tag, message, throwable)
    }

    private fun log(
        level: Level,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        consoleTarget: PrintStream? = null,
    ) {
        if (!isInitialized.get()) return
        if (!detailedLoggingEnabled.get() && level < Level.WARNING) return

        val entry =
            LogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
                throwable = throwable,
                consoleTarget = consoleTarget,
            )

        synchronized(bufferLock) {
            if (logBuffer.size == LOG_BUFFER_SIZE) logBuffer.removeFirst()
            logBuffer.addLast(entry)
        }
    }

    private fun createTeePrintStream(
        target: PrintStream?,
        level: Level,
        tag: String,
    ): PrintStream =
        PrintStream(
            object : OutputStream() {
                private val buffer = StringBuilder()

                override fun write(b: Int) {
                    if (b == '\n'.code) {
                        flushBuffer()
                    } else if (b != '\r'.code) {
                        buffer.append(b.toChar())
                    }
                }

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    val text = String(b, off, len)
                    text.forEach { ch ->
                        when (ch) {
                            '\n' -> {
                                flushBuffer()
                            }

                            '\r' -> {}

                            else -> {
                                buffer.append(ch)
                            }
                        }
                    }
                }

                override fun flush() {
                    flushBuffer()
                }

                override fun close() {
                    flushBuffer()
                    target?.flush()
                }

                private fun flushBuffer() {
                    if (buffer.isEmpty()) return
                    val line = buffer.toString()
                    buffer.setLength(0)
                    if (line.isNotBlank()) {
                        if (isInitialized.get() && (detailedLoggingEnabled.get() || level >= Level.WARNING)) {
                            log(level, tag, line, consoleTarget = target)
                        } else {
                            target?.println(sanitize(line))
                        }
                    }
                }
            },
            true,
        )

    private fun formatEntry(
        entry: LogEntry,
        timestampFormat: SimpleDateFormat,
    ): String {
        val timestamp = timestampFormat.format(Date(entry.timestamp))
        val levelStr = entry.level.name.take(4)
        val throwableStr =
            if (entry.throwable != null) {
                "\n${sanitize(getStackTrace(entry.throwable))}"
            } else {
                ""
            }
        return "[$timestamp] [$levelStr] [${sanitize(entry.tag)}] ${sanitize(entry.message)}$throwableStr"
    }

    internal fun sanitize(value: String): String =
        value
            .replace(sensitiveQueryRegex, "$1<redacted>")
            .replace(sensitiveHeaderRegex, "$1: <redacted>")
            .replace(urlCredentialsRegex, "$1<redacted>@")
            .replace(ipAddressRegex, "<redacted-ip>")

    private fun getStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    @Synchronized
    fun flush() = flush { FileOutputStream(it, true) }

    @Synchronized
    internal fun flush(openOutput: (File) -> OutputStream) {
        val batch =
            synchronized(bufferLock) {
                if (logBuffer.isEmpty()) return
                logBuffer.also { logBuffer = ArrayDeque(LOG_BUFFER_SIZE) }
            }
        try {
            logDir.mkdirs()
            val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
            var size = logFile.length()
            var output = openOutput(logFile).buffered()
            var written = 0
            try {
                while (written < batch.size) {
                    val entry = batch[written]
                    val formatted = formatEntry(entry, timestampFormat)
                    entry.consoleTarget?.println(sanitize(entry.message))
                    if (BuildConfig.DEBUG) {
                        when (entry.level) {
                            Level.VERBOSE, Level.DEBUG, Level.INFO -> originalOut?.println(formatted)
                            Level.WARNING -> originalErr?.println("WARNING: $formatted")
                            Level.ERROR -> originalErr?.println("ERROR: $formatted")
                        }
                    }
                    val bytes = (formatted.take(MAX_LOG_SIZE / 4 - 1) + "\n").toByteArray(Charsets.UTF_8)
                    if (size + bytes.size > MAX_LOG_SIZE) {
                        output.close()
                        repeat(written) { batch.removeFirst() }
                        written = 0
                        rotateLogs()
                        output = openOutput(logFile).buffered()
                        size = 0L
                    }
                    output.write(bytes)
                    size += bytes.size
                    written++
                }
            } finally {
                output.close()
            }
        } catch (e: Exception) {
            synchronized(bufferLock) {
                batch.addAll(logBuffer)
                while (batch.size > LOG_BUFFER_SIZE) batch.removeFirst()
                logBuffer = batch
            }
            originalErr?.println("Failed to flush logs: ${sanitize(e.toString())}")
        }
    }

    private fun rotateLogs() {
        Files.deleteIfExists(File(logDir, "app.log.$MAX_LOG_FILES").toPath())
        for (index in MAX_LOG_FILES downTo 2) {
            val previous = File(logDir, "app.log.${index - 1}").toPath()
            if (Files.exists(previous)) {
                Files.move(previous, File(logDir, "app.log.$index").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        if (logFile.exists()) {
            Files.move(logFile.toPath(), File(logDir, "app.log.1").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun getLogFiles(): List<File> {
        val logs = mutableListOf<File>()
        if (logFile.exists()) {
            logs.add(logFile)
        }
        for (i in 1..MAX_LOG_FILES) {
            val rotated = File(logDir, "app.log.$i")
            if (rotated.exists()) {
                logs.add(rotated)
            }
        }
        return logs.sortedByDescending { it.lastModified() }
    }

    @Synchronized
    fun exportLogs(destination: File): Boolean =
        try {
            flush()
            ZipOutputStream(destination.outputStream()).use { zipFile ->
                getLogFiles().forEach { logFile ->
                    val entry = ZipEntry(logFile.name)
                    zipFile.putNextEntry(entry)
                    logFile.inputStream().use { it.copyTo(zipFile) }
                    zipFile.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(destination.toPath()) }
            error("AppLogger", "Failed to export logs", e)
            false
        }

    fun getLogDirectory(): File = logDir
}

internal fun readLogTail(
    file: File,
    maxChars: Int = 10_000,
): String =
    RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        val bytes = ByteArray(minOf(length, maxChars.toLong() * 4).toInt())
        input.seek(length - bytes.size)
        input.readFully(bytes)
        bytes.decodeToString().takeLast(maxChars)
    }
