package com.metrolist.desktop.utils

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNativeCrashRecoveryTest {
    @Test
    fun detectsUncleanExitEvenWithoutAHotspotReportAndClearsNormalExit() {
        val directory = Files.createTempDirectory("native-crash").toFile()
        try {
            val store = DesktopNativeCrashStore(directory)
            assertNull(store.beginSession("first session"))
            val previous = assertNotNull(store.beginSession("second session"))
            assertEquals("first session", previous.session)
            assertTrue(previous.crashes.isEmpty())
            store.cleanExit()
            assertNull(store.beginSession("third session"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsCrashFilesAndRetriesUntilAcknowledgedIncludingReusedPids() {
        val directory = Files.createTempDirectory("native-crash").toFile()
        try {
            val file = directory.resolve("hs_err_pid123.log").apply { writeText("# EXCEPTION_ACCESS_VIOLATION\n# C [avcodec.dll+0x123]\n") }
            directory.resolve("not-hs_err.log").writeText("unrelated")
            val store = DesktopNativeCrashStore(directory)
            val first = assertNotNull(store.beginSession("first"))
            assertEquals(listOf(file), first.crashes.map { it.file })
            store.cleanExit()
            assertNotNull(store.beginSession("retry"))
            store.acknowledge(first)
            store.cleanExit()
            assertNull(store.beginSession("acknowledged"))
            assertTrue(file.isFile)
            file.appendText("# changed crash\n")
            store.cleanExit()
            assertNotNull(store.beginSession("PID reused"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun summaryRetainsFaultAndNativeStackButOmitsProcessSecrets() {
        val summary =
            summarizeHotspotCrash(
                """
                # EXCEPTION_ACCESS_VIOLATION
                # C [avcodec.dll+0x123]
                # https://cdn.example/media?sig=secret
                ---------------  S U M M A R Y ------------
                Command Line: -Dpassword=private
                Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
                C [ntdll.dll+0x123]
                C [avcodec.dll+0x456]

                Environment Variables:
                SECRET=private
                """.trimIndent(),
            )
        assertTrue(summary.contains("EXCEPTION_ACCESS_VIOLATION"))
        assertTrue(summary.contains("avcodec.dll+0x456"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("Command Line"))
    }
}
