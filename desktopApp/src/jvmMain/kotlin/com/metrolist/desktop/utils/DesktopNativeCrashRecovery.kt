package com.metrolist.desktop.utils

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.crash.redactCrashReport
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import java.io.File

internal data class DesktopNativeCrashFile(
    val file: File,
    val fingerprint: String,
    val summary: String,
)

internal data class PreviousDesktopExit(
    val session: String?,
    val crashes: List<DesktopNativeCrashFile>,
) {
    val summary: String
        get() =
            buildString {
                appendLine("Previous desktop process ended without completing shutdown. A forced exit or power loss can also cause this.")
                session?.let { appendLine(it) }
                crashes.forEach {
                    appendLine("\n${it.file.name}:")
                    appendLine(it.summary)
                }
            }.let(::redactCrashReport)
}

internal class DesktopNativeCrashStore(
    private val directory: File,
) {
    private val marker get() = File(directory, "session-running.txt")

    fun beginSession(description: String): PreviousDesktopExit? {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create desktop log directory" }
        val previous = marker.takeIf(File::isFile)?.let { readPrefix(it, 1024) }
        marker.writeText(description)
        val crashes =
            directory
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.matches(Regex("hs_err_pid[0-9]+\\.log")) }
                .sortedByDescending(File::lastModified)
                .asSequence()
                .mapNotNull { file ->
                    val fingerprint = "${file.lastModified()}:${file.length()}"
                    val receipt = File(directory, "${file.name}.reported")
                    if (receipt.isFile && runCatching { receipt.readText() }.getOrNull() == fingerprint) return@mapNotNull null
                    val summary =
                        runCatching { summarizeHotspotCrash(readPrefix(file, 64 * 1024)) }
                            .getOrElse { "Crash report could not be read: ${it::class.simpleName}" }
                    DesktopNativeCrashFile(file, fingerprint, summary)
                }.take(5)
                .toList()
        return if (previous != null || crashes.isNotEmpty()) PreviousDesktopExit(previous, crashes) else null
    }

    fun acknowledge(exit: PreviousDesktopExit) {
        exit.crashes.forEach { File(directory, "${it.file.name}.reported").writeText(it.fingerprint) }
    }

    fun cleanExit() {
        check(!marker.exists() || marker.delete()) { "Cannot remove desktop session marker" }
    }

    private fun readPrefix(
        file: File,
        limit: Int,
    ): String =
        file.reader().use { reader ->
            val buffer = CharArray(limit)
            var count = 0
            while (count < limit) {
                val read = reader.read(buffer, count, limit - count)
                if (read < 0) break
                count += read
            }
            String(buffer, 0, count)
        }
}

internal fun summarizeHotspotCrash(text: String): String {
    // Keep the fatal header and native stack, never VM arguments, environment or memory dumps.
    val lines = text.lines()
    val header = lines.takeWhile { it.isBlank() || it.startsWith("#") }
    val nativeStack = lines.dropWhile { !it.startsWith("Native frames:") }.takeWhile(String::isNotBlank)
    return redactCrashReport((header + nativeStack).joinToString("\n")).take(32 * 1024)
}

internal object DesktopNativeCrashRecovery {
    private var store: DesktopNativeCrashStore? = null
    private var pending: PreviousDesktopExit? = null

    fun start() {
        runCatching {
            store = DesktopNativeCrashStore(AppLogger.getLogDirectory())
            pending =
                store?.beginSession(
                    "version=${BuildConfig.APP_VERSION} pid=${ProcessHandle.current().pid()} started=${System.currentTimeMillis()} " +
                        "os=${System.getProperty("os.name")} ${System.getProperty("os.version")} arch=${System.getProperty("os.arch")}",
                )
            pending?.let {
                AppLogger.warning("NativeCrash", it.summary)
                PlaybackEventLog.warn(PlaybackEventKind.RECOVERY, it.summary)
                AppLogger.flush()
            }
        }.onFailure { AppLogger.warning("NativeCrash", "Could not record desktop session", it) }
    }

    fun reportPending() {
        val previous = pending ?: return
        // A missing shutdown alone also follows logoff, updates and forced exits, so only crash files are reported.
        if (!SentryManager.isEnabled || previous.crashes.isEmpty()) return
        runCatching {
            if (SentryManager.reportPreviousExit(previous)) {
                store?.acknowledge(previous)
                pending = null
            }
        }.onFailure { AppLogger.warning("NativeCrash", "Could not report previous desktop exit", it) }
    }

    fun cleanExit() {
        runCatching { store?.cleanExit() }
            .onFailure { AppLogger.warning("NativeCrash", "Could not clear desktop session marker", it) }
    }
}
