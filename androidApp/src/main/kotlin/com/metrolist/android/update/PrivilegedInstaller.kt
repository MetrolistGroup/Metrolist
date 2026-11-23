package com.metrolist.android.update

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.resume

enum class UpdateInstallMethod { SYSTEM, ROOT, SHIZUKU }

enum class PrivilegedAccess { GRANTED, UNAVAILABLE, DENIED }

/** Installs updates through a root or Shizuku shell, so no installer prompt appears. */
internal object PrivilegedInstaller {
    private const val SHIZUKU_PERMISSION_REQUEST = 1401

    // Long enough for the user to answer the root manager's grant prompt.
    private const val COMMAND_TIMEOUT_MILLIS = 5 * 60 * 1_000L

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    fun shizukuGranted(): Boolean =
        shizukuRunning() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    suspend fun requestAccess(method: UpdateInstallMethod): PrivilegedAccess =
        when (method) {
            UpdateInstallMethod.SYSTEM -> {
                PrivilegedAccess.GRANTED
            }

            UpdateInstallMethod.ROOT -> {
                // Asking for `id` makes the root manager show its grant prompt.
                val output = withContext(Dispatchers.IO) { run(method, "id -u") }
                if (output?.trim() == "0") PrivilegedAccess.GRANTED else PrivilegedAccess.DENIED
            }

            UpdateInstallMethod.SHIZUKU -> {
                when {
                    !shizukuRunning() -> PrivilegedAccess.UNAVAILABLE

                    shizukuGranted() -> PrivilegedAccess.GRANTED

                    // Shizuku throws when its binder dies between these calls.
                    runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true) -> PrivilegedAccess.DENIED

                    else -> if (requestShizukuPermission()) PrivilegedAccess.GRANTED else PrivilegedAccess.DENIED
                }
            }
        }

    private suspend fun requestShizukuPermission(): Boolean =
        suspendCancellableCoroutine { continuation ->
            val listener =
                object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(
                        requestCode: Int,
                        grantResult: Int,
                    ) {
                        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (continuation.isActive) continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }.onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(false)
            }
        }

    /** Records Metrolist as the installer of its own package, which lets Android 12+ update it without a prompt. */
    fun claimInstaller(
        method: UpdateInstallMethod,
        packageName: String,
    ): Boolean = run(method, "pm set-installer $packageName $packageName") != null

    /** Streams [apk] into `pm install` and returns true when the package manager reports success. */
    fun install(
        method: UpdateInstallMethod,
        packageName: String,
        apk: File,
    ): Boolean = run(method, "pm install -r -i $packageName -S ${apk.length()}", apk)?.contains("Success") == true

    /** Returns the command's output when it exits with status 0, or null. */
    private fun run(
        method: UpdateInstallMethod,
        command: String,
        input: File? = null,
    ): String? =
        runCatching {
            val process = start(method, command)
            // Process.waitFor(long, TimeUnit) needs API 26. Destroying the process also unblocks the stream I/O below.
            val watchdog =
                thread(isDaemon = true) {
                    try {
                        Thread.sleep(COMMAND_TIMEOUT_MILLIS)
                        process.destroy()
                    } catch (_: InterruptedException) {
                    }
                }
            try {
                process.outputStream.use { stdin -> input?.inputStream()?.use { it.copyTo(stdin) } }
                val output = process.inputStream.bufferedReader().readText()
                output.takeIf { process.waitFor() == 0 }
            } finally {
                watchdog.interrupt()
                process.destroy()
            }
        }.getOrNull()

    private fun start(
        method: UpdateInstallMethod,
        command: String,
    ): Process =
        when (method) {
            UpdateInstallMethod.ROOT -> {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            }

            UpdateInstallMethod.SHIZUKU -> {
                check(shizukuGranted()) { "Shizuku is not available" }
                // shortcut: Shizuku 13 made newProcess private and plans a UserService replacement; move to that when API 14 drops it.
                val newProcess =
                    Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java,
                    )
                newProcess.isAccessible = true
                newProcess.invoke(null, arrayOf("sh", "-c", "$command 2>&1"), null, null) as Process
            }

            UpdateInstallMethod.SYSTEM -> {
                error("The system installer has no shell")
            }
        }
}
