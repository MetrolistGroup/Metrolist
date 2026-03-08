package com.metrolist.shared.settings

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSettingsCorruptionTest {
    @Test
    fun corruptPendingFallsBackToMainWithoutKeepingPartiallyParsedValues() =
        withProfile { root ->
            val main = File(root, "settings.properties").apply { writeText("themeMode=2\n") }
            val pending =
                File(root, ".settings.properties.pending").apply {
                    writeText("partial=discard\npath=C:\\users\\music\n")
                }
            val original = pending.readBytes()

            assertEquals(Properties().apply { setProperty("themeMode", "2") }, loadPublicProperties(main))
            assertBackup(root, pending.name, original)
            assertFalse(pending.exists())
            assertEquals("2", loadPublicProperties(main).getProperty("themeMode"))
        }

    @Test
    fun corruptMainUsesValidPendingAndPreservesMainBeforeNextWrite() =
        withProfile { root ->
            val main = File(root, "settings.properties").apply { writeText("name=\\u12") }
            val original = main.readBytes()
            val pending = File(root, ".settings.properties.pending").apply { writeText("themeMode=1\n") }
            val recovered = loadPublicProperties(main)

            assertEquals("1", recovered.getProperty("themeMode"))
            assertBackup(root, main.name, original)
            atomicStore(main, recovered, "test", deferAccessDenied = true)
            assertFalse(pending.exists())
            assertEquals(recovered, loadPublicProperties(main))
            assertBackup(root, main.name, original)
        }

    @Test
    fun corruptMainWithoutPendingStartsWithDefaults() =
        withProfile { root ->
            val main = File(root, "settings.properties").apply { writeText("partial=discard\nname=\\uZZZZ\n") }
            val original = main.readBytes()

            assertTrue(loadPublicProperties(main).isEmpty())
            assertBackup(root, main.name, original)
            atomicStore(main, Properties().apply { setProperty("themeMode", "0") }, "test")
            assertEquals("0", loadPublicProperties(main).getProperty("themeMode"))
            assertBackup(root, main.name, original)
        }

    @Test
    fun bothCorruptStartWithDefaultsAndKeepSeparateBackups() =
        withProfile { root ->
            val main = File(root, "settings.properties").apply { writeText("name=\\u123") }
            val pending = File(root, ".settings.properties.pending").apply { writeText("name=\\uZZZZ") }
            val originals = listOf(main, pending).associate { it.name to it.readBytes() }

            assertTrue(loadPublicProperties(main).isEmpty())
            originals.forEach { (name, bytes) -> assertBackup(root, name, bytes) }
            assertTrue(loadPublicProperties(main).isEmpty())
            assertEquals(2, root.listFiles()!!.size)
        }

    @Test
    fun plaintextSecretsRecoverWithoutLoggingContentsOrPartialCredentials() =
        withProfile { root ->
            val file =
                File(root, "secrets.properties").apply {
                    writeText("innerTubeCookie=private-session\nvisitorData=\\uZZZZ-private-value\n")
                }
            val original = file.readBytes()
            val store = UnavailableSecretStore(file, IllegalStateException("unavailable"))

            assertTrue(store.keys().isEmpty())
            assertBackup(root, file.name, original)
            val diagnostics =
                PlaybackEventLog.events.value
                    .filter { it.kind == PlaybackEventKind.RECOVERY }
                    .joinToString()
            assertTrue(diagnostics.contains("secrets.properties.corrupt-"))
            assertFalse(diagnostics.contains("private-session"))
            assertFalse(diagnostics.contains("private-value"))
            store.put("innerTubeCookie", "new-session")
            store.flush()
            assertEquals("new-session", UnavailableSecretStore(file, IllegalStateException("unavailable")).get("innerTubeCookie"))
            assertBackup(root, file.name, original)
        }

    @Test
    fun malformedDecryptedWindowsPropertiesPreserveEncryptedBytes() =
        withProfile { root ->
            val file = File(root, "secrets.properties").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val original = file.readBytes()
            val store =
                WindowsEncryptedSecretStore(file, encrypt = { it }, decrypt = {
                    "innerTubeCookie=partial-secret\nvisitorData=\\u12".toByteArray()
                })

            assertNull(store.get("innerTubeCookie"))
            assertTrue(store.keys().isEmpty())
            assertBackup(root, file.name, original)
        }

    @Test
    fun failedBackupKeepsOriginalAndBlocksOverwrite() =
        withProfile { root ->
            if (!Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")) return@withProfile
            val main = File(root, "settings.properties").apply { writeText("name=\\u12") }
            val original = main.readBytes()
            val permissions = Files.getPosixFilePermissions(root.toPath())
            try {
                Files.setPosixFilePermissions(root.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
                assertTrue(loadPublicProperties(main).isEmpty())
                assertContentEquals(original, main.readBytes())
            } finally {
                Files.setPosixFilePermissions(root.toPath(), permissions)
            }
            assertFailsWith<IllegalStateException> { atomicStore(main, Properties(), "test") }
            assertContentEquals(original, main.readBytes())
            assertEquals(1, root.listFiles()!!.size)
        }

    @Test
    fun unreadableMainDoesNotPreventUsingValidPending() =
        withProfile { root ->
            if (!Files.getFileStore(root.toPath()).supportsFileAttributeView("posix")) return@withProfile
            val main = File(root, "settings.properties").apply { writeText("name=original\n") }
            File(root, ".settings.properties.pending").writeText("name=pending\n")
            val permissions = Files.getPosixFilePermissions(main.toPath())
            try {
                Files.setPosixFilePermissions(main.toPath(), emptySet())
                assertEquals("pending", loadPublicProperties(main).getProperty("name"))
            } finally {
                Files.setPosixFilePermissions(main.toPath(), permissions)
            }
            assertEquals("name=original\n", main.readText())
            assertFailsWith<IllegalStateException> { atomicStore(main, Properties(), "test") }
        }

    @Test
    fun corruptLinuxCredentialJournalDoesNotReplaceValidKeyringCredentials() =
        withProfile { root ->
            val file = File(root, "secrets.properties").apply { writeText("innerTubeCookie=partial\nvisitorData=\\uZZZZ") }
            val original = file.readBytes()
            val store =
                LinuxSecretServiceStore(file, secretToolPath = "secret-tool", runCommand = { command, _ ->
                    assertEquals("lookup", command[1])
                    CredentialCommandResult(0, "innerTubeCookie=keyring-session\n", "")
                })

            assertEquals("keyring-session", store.get("innerTubeCookie"))
            assertFalse(store.isPending)
            assertBackup(root, file.name, original)
        }

    @Test
    fun deniedWindowsReplacementKeepsCompleteEscapedPendingSnapshot() =
        withProfile { root ->
            val main = File(root, "settings.properties").apply { writeText("old=retained\n") }
            val pending = File(root, ".settings.properties.pending")
            val properties =
                Properties().apply {
                    setProperty("downloadPath", "C:\\users\\音楽\\unicode\\u12\\")
                    setProperty("name", "café 東京\nnext line")
                }
            atomicStore(main, properties, "test", deferAccessDenied = true, replace = { source, target ->
                if (target == main.toPath()) throw AccessDeniedException(target.toString())
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            })

            assertEquals("old=retained\n", main.readText())
            assertEquals(properties, pending.inputStream().use { Properties().apply { load(it) } })
            assertTrue(pending.readText().contains("\\\\users"))
            assertEquals(properties, loadPublicProperties(main))
            atomicStore(main, properties, "test")
            assertEquals(properties, loadPublicProperties(main))
            assertFalse(pending.exists())
            assertTrue(root.listFiles()!!.none { it.extension == "tmp" })
        }

    @Test
    fun failedPublicationLeavesPreviousMainAndPendingUntouched() =
        withProfile { root ->
            val main = File(root, "settings.properties").apply { writeText("name=main\n") }
            val pending = File(root, ".settings.properties.pending").apply { writeText("name=pending\n") }
            val properties = Properties().apply { setProperty("name", "東京") }
            assertFailsWith<IOException> {
                atomicStore(main, properties, "test", deferAccessDenied = true, replace = { source, _ ->
                    assertEquals(properties, source.toFile().inputStream().use { Properties().apply { load(it) } })
                    throw IOException("interrupted before publication")
                })
            }
            assertEquals("name=main\n", main.readText())
            assertEquals("name=pending\n", pending.readText())
            assertTrue(root.listFiles()!!.none { it.extension == "tmp" })
        }

    private fun assertBackup(
        root: File,
        name: String,
        original: ByteArray,
    ) {
        val backup = root.listFiles()!!.single { it.name.startsWith("$name.corrupt-") }
        assertContentEquals(original, backup.readBytes())
        if (Files.getFileStore(backup.toPath()).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(backup.toPath()),
            )
        }
    }

    private fun withProfile(block: (File) -> Unit) {
        val root = createTempDirectory("desktop-settings-corruption").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
