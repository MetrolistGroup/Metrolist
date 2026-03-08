package com.metrolist.desktop.crash

import com.metrolist.shared.crash.CrashReport
import com.metrolist.shared.crash.FileCrashStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal enum class DesktopCrashChoice { RELOAD, LET_IT_CRASH }

internal fun handoffDesktopCrash(
    report: CrashReport,
    store: FileCrashStore,
    choiceProvider: () -> DesktopCrashChoice,
    restartCommand: () -> List<String>,
    relaunch: (List<String>) -> Unit,
) {
    store.save(report)
    val choice = runCatching(choiceProvider).getOrDefault(DesktopCrashChoice.LET_IT_CRASH)
    val command = restartCommand()
    if (choice == DesktopCrashChoice.RELOAD) {
        store.acknowledge(report.id)
        relaunch(command)
    } else {
        relaunch(command + listOf(DesktopCrashRecovery.ARGUMENT, report.id))
    }
}

internal fun awaitDesktopCrashChoice(
    isHeadless: Boolean,
    isDispatchThread: Boolean,
    dispatch: (Runnable) -> Unit,
    visibilityTimeoutMillis: Long = 3_000,
    show: (onVisible: () -> Boolean) -> DesktopCrashChoice,
): DesktopCrashChoice =
    runCatching {
        if (isHeadless) return@runCatching DesktopCrashChoice.LET_IT_CRASH
        if (isDispatchThread) return@runCatching show { true }
        val visible = CompletableFuture<Boolean>()
        val choice = CompletableFuture<DesktopCrashChoice>()
        dispatch {
            if (!visible.isDone) {
                choice.complete(runCatching { show { visible.complete(true) } }.getOrDefault(DesktopCrashChoice.LET_IT_CRASH))
                visible.complete(false)
            }
        }
        val shown =
            try {
                visible.get(visibilityTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                visible.complete(false)
                visible.join()
            }
        if (shown) choice.get() else DesktopCrashChoice.LET_IT_CRASH
    }.getOrDefault(DesktopCrashChoice.LET_IT_CRASH)
