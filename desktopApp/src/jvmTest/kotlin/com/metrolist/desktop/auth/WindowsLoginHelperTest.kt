package com.metrolist.desktop.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsLoginHelperTest {
    @Test
    fun diagnosticOutputDoesNotContaminateTheResult() =
        runBlocking {
            assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
            val output =
                readWindowsLoginHelperOutput(
                    ProcessBuilder("/bin/sh", "-c", "printf diagnostic >&2; printf '{\"cookie\":\"test\"}'"),
                )
            assertEquals("{\"cookie\":\"test\"}", output)
        }

    @Test
    fun cancellationTerminatesAHelperBlockedOnOutput() =
        runBlocking {
            assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
            val directory = Files.createTempDirectory("login-helper-cancellation")
            val pidFile = directory.resolve("pid")
            val watcher = directory.fileSystem.newWatchService()
            directory.register(watcher, ENTRY_CREATE)
            val job =
                async(Dispatchers.IO) {
                    readWindowsLoginHelperOutput(
                        ProcessBuilder(
                            "/bin/sh",
                            "-c",
                            "echo $$ > \"$1.tmp\"; mv \"$1.tmp\" \"$1\"; read line",
                            "helper",
                            pidFile.toString(),
                        ),
                    )
                }
            try {
                while (!Files.exists(pidFile)) {
                    val event = checkNotNull(watcher.poll(10, TimeUnit.SECONDS)) { "Helper did not publish its PID" }
                    event.pollEvents()
                    check(event.reset())
                }
                val pid = Files.readString(pidFile).trim().toLong()
                withTimeout(10_000) { job.cancelAndJoin() }
                ProcessHandle
                    .of(pid)
                    .orElse(null)
                    ?.onExit()
                    ?.get(10, TimeUnit.SECONDS)
                assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            } finally {
                job.cancelAndJoin()
                watcher.close()
                directory.toFile().deleteRecursively()
            }
        }
}
