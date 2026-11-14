package com.metrolist.desktop.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun readWindowsLoginHelperOutput(builder: ProcessBuilder): String =
    withContext(Dispatchers.IO) {
        val process = builder.redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { process.destroyForcibly() }
                try {
                    val bytes = process.inputStream.use { it.readNBytes(MAX_HELPER_OUTPUT_BYTES + 1) }
                    check(bytes.size <= MAX_HELPER_OUTPUT_BYTES) { "Login helper output exceeded the size limit" }
                    val content = if (process.waitFor() == 0) bytes.decodeToString() else ""
                    continuation.resume(content)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

private const val MAX_HELPER_OUTPUT_BYTES = 1024 * 1024
