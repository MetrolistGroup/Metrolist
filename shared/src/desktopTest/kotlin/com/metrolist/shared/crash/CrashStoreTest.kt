package com.metrolist.shared.crash

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashStoreTest {
    @Test
    fun unshownReportSurvivesRestartAndAcknowledgementCannotClearANewerCrash() {
        val directory = createTempDirectory("crash-pending").toFile()
        try {
            FileCrashStore(directory).save(CrashReport(1, "14", "test", "first", "1-1"))
            val reopened = FileCrashStore(directory)
            assertEquals("1-1", reopened.pendingReportId())
            reopened.save(CrashReport(2, "14", "test", "second", "2-1"))
            reopened.acknowledge("1-1")
            assertEquals("2-1", reopened.pendingReportId())
            reopened.acknowledge("2-1")
            assertNull(reopened.pendingReportId())
            assertEquals(2, reopened.list().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun boundsOrdersAndPersistsReportsAcrossStoreInstances() {
        val directory = createTempDirectory("crash-store").toFile()
        try {
            val store = FileCrashStore(directory)
            (0L..54L).reversed().forEach { time -> store.save(CrashReport(time, "14", "test", "trace-$time", "$time-1")) }
            val reopened = FileCrashStore(directory)
            assertEquals((54L downTo 5L).toList(), reopened.list().map { it.timestamp })
            assertEquals(50, directory.listFiles()!!.count { it.extension == "json" })
            File(directory, "broken.json").writeText("incomplete")
            assertEquals(50, reopened.list().size)
            reopened.clear()
            assertTrue(FileCrashStore(directory).list().isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun credentialsAreRemovedBeforeWritingIncludingCookieTailsAndJsonHeaders() {
        val directory = createTempDirectory("crash-secrets").toFile()
        try {
            val secrets = listOf("cookie-secret", "cookie-tail", "auth-secret", "query-secret", "token-secret", "json-secret")
            val trace =
                """
                java.lang.IllegalStateException: Cookie: SID=cookie-secret; other=cookie-tail
                Authorization: Bearer auth-secret
                https://example.org/media?key=query-secret
                access_token=token-secret
                {"cookie": "json-secret"}
                at SomeClass.run(SomeClass.kt:12)
                """.trimIndent()
            FileCrashStore(directory).save(CrashReport(1, "14", "test", trace, "1-1"))
            val disk = directory.listFiles()!!.filter { it.extension == "json" }.joinToString { it.readText() }
            secrets.forEach { assertFalse(it in disk, it) }
            assertTrue("SomeClass" in FileCrashStore(directory).list().single().stackTrace)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun simultaneousWritersKeepAllReportsAndBoundTheStore() {
        val directory = createTempDirectory("crash-concurrent").toFile()
        try {
            val threads =
                (1L..20L).map { time ->
                    Thread { FileCrashStore(directory).save(CrashReport(time, "14", "test", "trace", "$time-1")) }.apply { start() }
                }
            threads.forEach { it.join() }
            assertEquals(20, FileCrashStore(directory).list().size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
