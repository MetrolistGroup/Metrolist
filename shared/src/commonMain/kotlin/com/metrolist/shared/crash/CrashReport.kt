package com.metrolist.shared.crash

import com.metrolist.shared.utils.redactDiagnosticText
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class CrashReport(
    val timestamp: Long,
    val version: String,
    val platform: String,
    val stackTrace: String,
    val id: String = "$timestamp-${Random.nextLong().toULong()}",
) {
    val text: String get() = "${Instant.fromEpochMilliseconds(timestamp)}\n$version\n$platform\n\n$stackTrace"

    fun redacted(): CrashReport =
        copy(
            version = redactCrashReport(version),
            platform = redactCrashReport(platform),
            stackTrace =
                redactCrashReport(stackTrace).take(256 * 1024),
        )

    companion object {
        fun create(
            version: String,
            platform: String,
            error: Throwable,
        ): CrashReport = CrashReport(Clock.System.now().toEpochMilliseconds(), version, platform, error.stackTraceToString()).redacted()
    }
}

fun redactCrashReport(value: String): String =
    redactDiagnosticText(
        value.replace(Regex("(?im)(\\b(?:authorization|cookie|set-cookie|client-token|x-csrf-token)[\\\"']?\\s*[:=]).*$"), "$1 <redacted>"),
    )

interface CrashStore {
    fun save(report: CrashReport)

    fun list(): List<CrashReport>

    fun clear()
}
