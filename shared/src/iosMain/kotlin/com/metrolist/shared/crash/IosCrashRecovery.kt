@file:OptIn(ExperimentalNativeApi::class)

package com.metrolist.shared.crash

import com.metrolist.shared.BuildConfig
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

object IosCrashRecovery : CrashStore {
    private val lock = NSRecursiveLock()
    private val fs = FileSystem.SYSTEM
    private val directory get() =
        (
            requireNotNull(
                NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String,
            ) +
                "/metrolist/crashes"
        ).toPath()
    private val pending get() = directory / "pending"
    private val json = Json { ignoreUnknownKeys = true }
    private var handling = false

    val hasPendingReport: Boolean get() = fs.exists(pending)

    fun install() {
        handling = hasPendingReport
        setUnhandledExceptionHook { error ->
            runCatching { record(error) }
            terminateWithUnhandledException(error)
        }
    }

    fun recordNative(message: String) {
        record(IllegalStateException(message))
    }

    private fun record(error: Throwable) =
        locked {
            if (!handling) {
                handling = true
                val report =
                    CrashReport.create(
                        BuildConfig.APP_VERSION,
                        "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion} ${UIDevice.currentDevice.model}",
                        error,
                    )
                save(report)
                fs.write(pending) { writeUtf8(report.id) }
            }
        }

    fun acknowledge() {
        handling = false
        fs.delete(pending)
    }

    override fun save(report: CrashReport) =
        locked {
            fs.createDirectories(directory)
            val temporary = directory / "${report.id}.tmp"
            fs.write(temporary) { writeUtf8(json.encodeToString(report.redacted())) }
            fs.atomicMove(temporary, directory / "${report.id}.json")
            list().drop(50).forEach { fs.delete(directory / "${it.id}.json") }
        }

    override fun list(): List<CrashReport> =
        locked {
            fs
                .listOrNull(directory)
                .orEmpty()
                .filter { it.name.endsWith(".json") }
                .mapNotNull {
                    runCatching { json.decodeFromString<CrashReport>(fs.read(it) { readUtf8() }).redacted() }.getOrNull()
                }.sortedByDescending { it.timestamp }
        }

    override fun clear() =
        locked {
            fs
                .listOrNull(directory)
                .orEmpty()
                .filter { it.name.endsWith(".json") }
                .forEach { fs.delete(it) }
        }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
