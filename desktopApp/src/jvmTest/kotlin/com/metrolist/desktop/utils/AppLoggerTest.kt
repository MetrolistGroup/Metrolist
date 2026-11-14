package com.metrolist.desktop.utils

import com.metrolist.shared.utils.LogBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLoggerTest {
    @Test
    fun nativeDiagnosticsReachDiskBeforeThePeriodicFlushWithLoggingDisabled() =
        withLogger { directory, _ ->
            AppLogger.setEnabled(false)
            LogBridge.sink?.invoke("WARN", "FFmpeg", "decoder warning?token=secret", null)
            val log = File(directory, "app.log").readText()
            assertTrue(log.contains("decoder warning"))
            assertFalse(log.contains("token=secret"))
        }

    @Test
    fun fatalErrorsFlushSanitizedStackAndCauseBeforeTerminationWithDetailedLoggingDisabled() {
        val originalOut = System.out
        val originalErr = System.err
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        val originalSink = LogBridge.sink
        val captured = ByteArrayOutputStream()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val directory = createTempDirectory("metrolist-logger-test").toFile()
        var terminated = false
        try {
            System.setErr(PrintStream(captured))
            AppLogger.initialize(scope, logDirectory = directory, writerDispatcher = StandardTestDispatcher()) {
                val log = File(directory, "app.log").readText()
                assertTrue("IllegalStateException" in log)
                assertTrue("Caused by: java.lang.IllegalArgumentException" in log)
                assertTrue("AppLoggerTest" in log)
                assertFalse("token=secret" in log)
                terminated = true
            }
            AppLogger.setEnabled(false)
            AppLogger.info("Test", "Startup complete")
            AppLogger.debug("Test", "Hidden debug message")
            System.out.println("GET https://user:password@example.test/?token=stdout-secret")
            val error = IllegalStateException("Playback failed", IllegalArgumentException("https://example.test/?token=secret"))
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), error)
            val output = captured.toString()
            assertTrue(terminated)
            val log = File(directory, "app.log").readText()
            assertFalse("Startup complete" in log)
            assertFalse("Hidden debug message" in log)
            assertFalse("stdout-secret" in log)
            assertFalse("user:password" in log)
            assertTrue("IllegalStateException" in output)
            assertTrue("Caused by: java.lang.IllegalArgumentException" in output)
            assertTrue("AppLoggerTest" in output)
            assertFalse("token=secret" in output)
        } finally {
            AppLogger.shutdown()
            scope.cancel()
            LogBridge.sink = originalSink
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
            System.setOut(originalOut)
            System.setErr(originalErr)
            directory.deleteRecursively()
        }
    }

    @Test
    fun burstKeepsNewestEntriesInOrderAndExportFlushesPendingEntries() =
        withLogger { directory, console ->
            repeat(1_200) { AppLogger.info("Burst", "entry-$it?token=secret") }
            assertEquals(0, console.size())
            assertFalse(File(directory, "app.log").exists())

            val zip = File(directory, "export.zip")
            assertTrue(AppLogger.exportLogs(zip))
            val lines = File(directory, "app.log").readLines()
            assertEquals(1_000, lines.size)
            assertTrue(lines.first().contains("entry-200?token=<redacted>"))
            assertTrue(lines.last().contains("entry-1199?token=<redacted>"))
            assertEquals((200..1199).toList(), lines.map { it.substringAfter("entry-").substringBefore('?').toInt() })
            assertFalse(lines.any { "secret" in it })
            ZipFile(zip).use { archive ->
                assertEquals(lines, archive.getInputStream(archive.getEntry("app.log")).bufferedReader().readLines())
            }
        }

    @Test
    fun rotatesBeforeSizeLimitAndRetainsFiveArchives() =
        withLogger { directory, _ ->
            val maxBytes = 10L * 1024 * 1024
            val log = File(directory, "app.log")
            RandomAccessFile(log, "rw").use { it.setLength(maxBytes - 1) }
            (1..5).forEach { File(directory, "app.log.$it").writeText("archive-$it") }
            AppLogger.warning("Rotation", "first new record")
            AppLogger.warning("Rotation", "second new record")
            AppLogger.flush()

            assertEquals(maxBytes - 1, File(directory, "app.log.1").length())
            assertEquals("archive-4", File(directory, "app.log.5").readText())
            assertFalse(File(directory, "app.log.6").exists())
            assertTrue(log.length() <= maxBytes)
            assertEquals(listOf("first new record", "second new record"), log.readLines().map { it.substringAfter("[Rotation] ") })
        }

    @Test
    fun bufferedWriteFailureRetainsTheWholeBatchAheadOfNewRecords() =
        withLogger { directory, _ ->
            repeat(3) { AppLogger.warning("Retry", "pending-$it") }
            var attemptedBytes = 0
            AppLogger.flush {
                object : OutputStream() {
                    override fun write(b: Int) {
                        error("Expected a buffered write")
                    }

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        if (attemptedBytes == 0) AppLogger.warning("Retry", "new record")
                        attemptedBytes += len
                        throw IOException("Disk full")
                    }
                }
            }
            assertTrue(attemptedBytes > 0)
            AppLogger.flush()
            assertEquals(
                listOf("pending-0", "pending-1", "pending-2", "new record"),
                File(directory, "app.log").readLines().map { it.substringAfter("[Retry] ") },
            )
        }

    @Test
    fun retryAfterRotationDoesNotReplayRecordsAlreadyFlushedToTheArchive() =
        withLogger { directory, _ ->
            val log = File(directory, "app.log")
            RandomAccessFile(log, "rw").use { it.setLength(10L * 1024 * 1024 - 80) }
            AppLogger.warning("Retry", "first record")
            AppLogger.warning("Retry", "second record")
            var opened = 0
            AppLogger.flush { file ->
                if (++opened == 2) throw IOException("Cannot open rotated log")
                FileOutputStream(file, true)
            }
            assertEquals(2, opened)
            assertTrue("first record" in readLogTail(File(directory, "app.log.1")))

            AppLogger.flush()
            assertEquals(listOf("second record"), log.readLines().map { it.substringAfter("[Retry] ") })
        }

    @Test
    fun failedWriteKeepsPendingEntriesForRetry() =
        withLogger { directory, _ ->
            val log = File(directory, "app.log")
            assertTrue(log.mkdir())
            AppLogger.warning("Retry", "pending record")
            AppLogger.flush()
            assertTrue(log.delete())
            AppLogger.flush()
            assertTrue(log.readText().contains("pending record"))
        }

    private fun withLogger(block: (File, ByteArrayOutputStream) -> Unit) {
        val originalOut = System.out
        val originalErr = System.err
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        val originalSink = LogBridge.sink
        val console = ByteArrayOutputStream()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val directory = createTempDirectory("metrolist-logger-test").toFile()
        try {
            System.setOut(PrintStream(console))
            System.setErr(PrintStream(console))
            AppLogger.initialize(scope, directory, writerDispatcher = StandardTestDispatcher())
            AppLogger.setEnabled(true)
            block(directory, console)
        } finally {
            AppLogger.shutdown()
            scope.cancel()
            LogBridge.sink = originalSink
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
            System.setOut(originalOut)
            System.setErr(originalErr)
            directory.deleteRecursively()
        }
    }
}
