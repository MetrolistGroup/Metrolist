package com.metrolist.desktop.crash

import com.metrolist.shared.crash.CrashReport
import com.metrolist.shared.crash.FileCrashStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCrashPromptTest {
    @Test
    fun reloadAcknowledgesBeforeNormalRelaunchAndRetainsReport() {
        verifyHandoff(DesktopCrashChoice.RELOAD) { DesktopCrashChoice.RELOAD }
    }

    @Test
    fun letItCrashKeepsPendingReportAndLaunchesRecovery() {
        verifyHandoff(DesktopCrashChoice.LET_IT_CRASH) { DesktopCrashChoice.LET_IT_CRASH }
    }

    @Test
    fun promptFailureStillLaunchesRecoveryWithSavedReport() {
        verifyHandoff(DesktopCrashChoice.LET_IT_CRASH) { throw IllegalStateException("prompt failed") }
    }

    private fun verifyHandoff(
        expected: DesktopCrashChoice,
        choice: () -> DesktopCrashChoice,
    ) {
        val directory = createTempDirectory("desktop-crash-prompt").toFile()
        try {
            val report = CrashReport(1, "14", "test", "stack trace", "1-1")
            val store = FileCrashStore(directory)
            val restart = listOf("/opt/metrolist", "--profile", "test")
            val commands = mutableListOf<List<String>>()
            var savedBeforePrompt = false
            handoffDesktopCrash(
                report,
                store,
                choiceProvider = {
                    savedBeforePrompt = FileCrashStore(directory).list().single() == report &&
                        FileCrashStore(directory).pendingReportId() == report.id
                    choice()
                },
                restartCommand = { restart },
            ) { command ->
                assertEquals(if (expected == DesktopCrashChoice.RELOAD) null else report.id, store.pendingReportId())
                commands.add(command)
            }
            assertTrue(savedBeforePrompt)
            val arguments = if (expected == DesktopCrashChoice.RELOAD) emptyList() else listOf(DesktopCrashRecovery.ARGUMENT, report.id)
            assertEquals(listOf(restart + arguments), commands)
            assertEquals(report, FileCrashStore(directory).list().single())
        } finally {
            directory.deleteRecursively()
        }
    }
}
