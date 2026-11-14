package com.metrolist.shared.settings

import com.metrolist.shared.utils.OsUtil
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialPersistenceTest {
    @Test
    fun startupDeadlineKeepsTimedOutLinuxCredentialsPendingForRecovery() {
        val directory = Files.createTempDirectory("metrolist-startup-deadline").toFile()
        try {
            val store =
                withStartupCredentialDeadline(timeoutMs = 0L) {
                    LinuxSecretServiceStore(
                        File(directory, "secrets.properties"),
                        secretToolPath = "/bin/sh",
                        runCommand = { _, _ -> runCredentialCommand(listOf("/bin/sh", "-c", "printf 'innerTubeCookie=recovered\\n'")) },
                    )
                }
            assertTrue(store.isPending)
            assertTrue(store.recover())
            assertFalse(store.isPending)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingSecretToolKeepsOwnerOnlyFallbackAvailable() {
        val directory = createTempDirectory("metrolist-missing-secret-tool").toFile()
        try {
            val file = File(directory, "secrets.properties").apply { writeText("innerTubeCookie=fallback\n") }
            val store =
                runCatching {
                    LinuxSecretServiceStore(file, secretToolPath = null, findSecretTool = { null }, runCommand = {
                        _,
                        _,
                        ->
                        error("must not run")
                    })
                }.getOrElse { UnavailableSecretStore(file, it) }
            assertEquals("fallback", store.get("innerTubeCookie"))
            store.put("visitorData", "local")
            store.flush()
            assertEquals("local", UnavailableSecretStore(file, RuntimeException("test")).get("visitorData"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedLookupWithNoSecretServiceKeepsFallbackAvailable() {
        val directory = createTempDirectory("metrolist-no-secret-service").toFile()
        try {
            val file = File(directory, "secrets.properties").apply { writeText("innerTubeCookie=fallback\n") }
            val store =
                runCatching {
                    LinuxSecretServiceStore(
                        file,
                        secretToolPath = "secret-tool",
                        runCommand = { _, _ -> CredentialCommandResult(1, "", "D-Bus service unavailable") },
                    )
                }.getOrElse { UnavailableSecretStore(file, it) }
            assertEquals("fallback", store.get("innerTubeCookie"))
            store.put("visitorData", "local")
            store.flush()
            assertEquals("local", UnavailableSecretStore(file, RuntimeException("test")).get("visitorData"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lockedLookupRecoversCachedSettingsAfterUnlock() {
        val directory = createTempDirectory("metrolist-locked-keyring").toFile()
        try {
            var lookupCount = 0
            var payload = "innerTubeCookie=recovered\n"
            val store =
                LinuxSecretServiceStore(
                    File(directory, "secrets.properties"),
                    secretToolPath = "secret-tool",
                    runCommand = { command, input ->
                        when (command[1]) {
                            "lookup" -> {
                                lookupCount++
                                if (lookupCount == 1) {
                                    CredentialCommandResult(1, "", "keyring locked")
                                } else {
                                    CredentialCommandResult(0, payload, "")
                                }
                            }

                            "store" -> {
                                payload = input
                                CredentialCommandResult(0, "", "")
                            }

                            else -> {
                                error("unexpected command")
                            }
                        }
                    },
                )
            val public =
                Properties().apply {
                    setProperty("desktopLegacySecretCleanupV2", "true")
                    setProperty("desktopLegacyPreferencesMigrated", "true")
                    setProperty("desktopAutoCacheDefaultDisabledV1", "true")
                    setProperty("lastfmSession", "legacy")
                }
            val backing = DesktopFileSettings(File(directory, "settings.properties"), public, store)
            val settings = Settings(backing)
            assertEquals("", settings.innerTubeCookie)
            assertEquals("", settings.lastFmSession)
            assertTrue(store.isPending)
            backing.putString("visitorData", "while-locked")
            assertTrue(File(directory, "secrets.properties").readText().contains("visitorData=while-locked"))
            assertEquals(true, backing.recoverSecrets(settings::finishPendingSecretMigrations))
            assertEquals("while-locked", backing.getStringOrNull("visitorData"))
            assertTrue(File(directory, "secrets.properties").exists())
            backing.putString("visitorData", "after-recovery")
            settings.refreshStoredValues()
            assertEquals("recovered", settings.innerTubeCookie)
            assertEquals("legacy", settings.lastFmSession)
            assertEquals("recovered", backing.getStringOrNull("innerTubeCookie"))
            assertNull(backing.recoverSecrets())
            assertFalse(File(directory, "secrets.properties").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fallbackMigrationWaitsForSuccessfulSecretServiceWrite() {
        val directory = createTempDirectory("metrolist-keyring-migration").toFile()
        try {
            val file = File(directory, "secrets.properties").apply { writeText("innerTubeCookie=fallback\n") }
            var lookupCount = 0
            var storeCount = 0
            var storedPayload = ""
            var keyringPayload = "innerTubeCookie=secure\naccountName=existing\n"
            val store =
                LinuxSecretServiceStore(
                    file,
                    secretToolPath = "secret-tool",
                    runCommand = { command, input ->
                        when (command[1]) {
                            "lookup" -> {
                                lookupCount++
                                if (lookupCount == 1) {
                                    CredentialCommandResult(1, "", "keyring locked")
                                } else {
                                    CredentialCommandResult(
                                        0,
                                        keyringPayload,
                                        "",
                                    )
                                }
                            }

                            "store" -> {
                                storeCount++
                                storedPayload = input
                                if (storeCount > 1) keyringPayload = input
                                CredentialCommandResult(if (storeCount == 1) 1 else 0, "", "")
                            }

                            else -> {
                                error("unexpected command")
                            }
                        }
                    },
                )
            val settings = DesktopFileSettings(File(directory, "settings.properties"), Properties(), store)
            settings.putString("innerTubeCookie", "replacement")
            assertTrue(file.readText().contains("innerTubeCookie=replacement"))
            assertFalse(store.recover())
            assertTrue(file.readText().contains("innerTubeCookie=replacement"))
            assertFalse(store.isPending)
            assertEquals("existing", store.get("accountName"))
            assertTrue(store.recover())
            assertFalse(file.exists())
            assertEquals("replacement", store.get("innerTubeCookie"))
            assertEquals("existing", store.get("accountName"))
            assertTrue(storedPayload.contains("innerTubeCookie=replacement"))
            assertTrue(storedPayload.contains("accountName=existing"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun credentialProcessesDrainBothStreamsWhileWritingInput() {
        assumeTrue(File("/bin/sh").canExecute())
        val input = "credential payload\n".repeat(20_000)
        val result = runCredentialCommand(listOf("/bin/sh", "-c", "head -c 262144 /dev/zero >&2; cat"), input, 5_000)
        assertEquals(0, result.exit)
        assertEquals(input, result.output)
        assertEquals(262144, result.error.length)
    }

    @Test
    fun credentialCancellationKillsTheProcessWithBlockedStdin() {
        assumeTrue(File("/bin/sh").canExecute())
        val directory = createTempDirectory("metrolist-credential-cancellation").toFile()
        val pidFile = File(directory, "pid")
        val command =
            FutureTask {
                assertFailsWith<IllegalStateException> {
                    runCredentialCommand(
                        listOf("/bin/sh", "-c", "echo $$ > \"$1.tmp\"; mv \"$1.tmp\" \"$1\"; while :; do :; done", "test", pidFile.path),
                        input = "x".repeat(1_000_000),
                    )
                }
            }
        val worker = Thread(command)
        try {
            directory.toPath().fileSystem.newWatchService().use { watcher ->
                directory.toPath().register(watcher, ENTRY_CREATE)
                worker.start()
                while (!pidFile.exists()) {
                    val key = assertNotNull(watcher.poll(30, TimeUnit.SECONDS), "Credential process did not start")
                    key.pollEvents()
                    key.reset()
                }
                worker.interrupt()
                command.get(30, TimeUnit.SECONDS)
            }
            val process = ProcessHandle.of(pidFile.readText().trim().toLong())
            if (process.isPresent) {
                process.get().onExit().get(30, TimeUnit.SECONDS)
                assertFalse(process.get().isAlive)
            }
        } finally {
            worker.interrupt()
            worker.join(30_000)
            directory.deleteRecursively()
        }
    }

    @Test
    fun settingsReadersRemainAvailableAndFailedSecretWritesRollBack() {
        val directory = createTempDirectory("metrolist-secret-lock").toFile()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val backing = UnavailableSecretStore(File(directory, "secrets.properties"), RuntimeException("test"))
        backing.put("innerTubeCookie", "previous")
        val store =
            object : SecretStore by backing {
                override fun flush() {
                    writing.countDown()
                    check(release.await(60, TimeUnit.SECONDS))
                    error("test write failure")
                }
            }
        val settings = DesktopFileSettings(File(directory, "settings.properties"), Properties(), store)
        var reader: Thread? = null
        val writer =
            thread(isDaemon = true) {
                try {
                    settings.putString("innerTubeCookie", "replacement")
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val read = CountDownLatch(1)
            reader =
                thread(isDaemon = true) {
                    assertEquals("previous", settings.getStringOrNull("innerTubeCookie"))
                    settings.putString("theme", "dark")
                    read.countDown()
                }
            assertTrue(read.await(30, TimeUnit.SECONDS), "Settings monitor blocked behind credential IO")
            reader.join()
            release.countDown()
            writer.join(5_000)
            assertNotNull(failure.get())
            assertEquals("previous", settings.getStringOrNull("innerTubeCookie"))
            assertEquals("previous", backing.get("innerTubeCookie"))
            settings.flush()
        } finally {
            release.countDown()
            writer.join(5_000)
            reader?.join(30_000)
            directory.deleteRecursively()
        }
    }

    @Test
    fun unreadableWindowsStoreIsPreservedBeforeReplacementAndBackupsAreUnique() {
        val directory = createTempDirectory("metrolist-windows-secrets").toFile()
        try {
            val file = File(directory, "secrets.properties")
            val original = byteArrayOf(1, 2, 3, 4)
            repeat(2) {
                file.writeBytes(original)
                val store = WindowsEncryptedSecretStore(file, encrypt = { it }, decrypt = { error("DPAPI unavailable") })
                assertTrue(store.keys().isEmpty())
                store.put("innerTubeCookie", "new session")
                store.flush()
                assertTrue(file.readText().contains("new session"))
            }
            val backups = directory.listFiles()!!.filter { it.extension == "bak" }
            assertEquals(2, backups.size)
            backups.forEach { assertContentEquals(original, it.readBytes()) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun windowsDpapiRoundTripsAllCredentialsAcrossRepeatedWrites() {
        assumeTrue(OsUtil.isWindows)
        val directory = createTempDirectory("metrolist-dpapi").toFile()
        try {
            val file = File(directory, "secrets.properties")
            val store = WindowsEncryptedSecretStore(file)
            store.put("innerTubeCookie", "retained")
            repeat(20) {
                store.put("visitorData", it.toString())
                store.flush()
                val restored = WindowsEncryptedSecretStore(file)
                assertEquals("retained", restored.get("innerTubeCookie"))
                assertEquals(it.toString(), restored.get("visitorData"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
