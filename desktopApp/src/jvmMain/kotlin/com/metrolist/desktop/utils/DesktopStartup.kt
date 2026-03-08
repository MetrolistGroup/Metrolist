package com.metrolist.desktop.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.management.ManagementFactory

internal class DesktopStartup {
    val firstFrame = CompletableDeferred<Unit>()
    val windowShown = CompletableDeferred<Unit>()
    val appFrame = CompletableDeferred<Unit>()

    @Volatile
    var initialized = false
        private set

    private val traceEnabled = System.getenv("METROLIST_STARTUP_TRACE") == "1"

    suspend fun initialize(initialize: () -> Unit) {
        firstFrame.await()
        windowShown.await()
        withContext(Dispatchers.IO) {
            initialize()
            // Shutdown must clean up even if the window closes before IO returns to Main.
            initialized = true
        }
        trace("backend")
    }

    fun frameDrawn(backendReady: Boolean) {
        if (backendReady) firstFrame.complete(Unit)
        val frame = if (backendReady) appFrame else firstFrame
        if (frame.complete(Unit)) trace(if (backendReady) "app-frame" else "shell-frame")
    }

    fun trace(stage: String) {
        if (traceEnabled) {
            println(
                "[Startup] stage=$stage pid=${ProcessHandle.current().pid()} uptimeMs=${ManagementFactory.getRuntimeMXBean().uptime}",
            )
        }
    }
}
