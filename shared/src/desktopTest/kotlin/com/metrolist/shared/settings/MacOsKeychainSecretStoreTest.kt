package com.metrolist.shared.settings

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
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

class MacOsKeychainSecretStoreTest {
    @Test
    fun malformedPlaintextCredentialsArePreservedBeforeMigration() {
        val directory = createTempDirectory("metrolist-macos-corrupt").toFile()
        try {
            val file =
                File(directory, "secrets.properties").apply {
                    writeText("innerTubeCookie=partial-secret\nvisitorData=\\u12")
                }
            val original = file.readBytes()
            val keychain = TestKeychain()
            val store = MacOsKeychainSecretStore(file, keychain::run)
            assertTrue(store.keys().isEmpty())
            val backup = directory.listFiles()!!.single { it.name.startsWith("secrets.properties.corrupt-") }
            assertContentEquals(original, backup.readBytes())
            store.put("innerTubeCookie", "new-session")
            store.flush()
            assertEquals("new-session", MacOsKeychainSecretStore(file, keychain::run).get("innerTubeCookie"))
            assertContentEquals(original, backup.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resetKeychainPreservesUnreadableCredentialsAndAllowsStartupAndNewLogin() {
        val directory = createTempDirectory("metrolist-macos-secrets").toFile()
        try {
            val file = File(directory, "secrets.properties")
            val keychain = TestKeychain()
            val originals = mutableListOf<ByteArray>()
            repeat(2) { attempt ->
                MacOsKeychainSecretStore(file, keychain::run).apply {
                    put("innerTubeCookie", "old-session-$attempt")
                    put("spotifySession", "old-spotify-session")
                    flush()
                }
                originals += file.readBytes()
                keychain.encryptionKey = null

                val recovered = MacOsKeychainSecretStore(file, keychain::run)
                assertTrue(recovered.keys().isEmpty())
                val settings = DesktopFileSettings(File(directory, "settings.properties"), Properties(), recovered)
                Settings(settings)
                assertNull(settings.getStringOrNull("innerTubeCookie"))
                settings.putString("innerTubeCookie", "new-session-$attempt")
                settings.putString("spotifySession", "new-spotify-session")
                settings.flush()

                val restarted = MacOsKeychainSecretStore(file, keychain::run)
                assertEquals("new-session-$attempt", restarted.get("innerTubeCookie"))
                assertEquals("new-spotify-session", restarted.get("spotifySession"))
            }

            val backups = directory.listFiles()!!.filter { it.extension == "bak" }
            assertEquals(2, backups.size)
            originals.forEach { original -> assertTrue(backups.any { it.readBytes().contentEquals(original) }) }
            backups.forEach { backup ->
                if (Files.getFileStore(backup.toPath()).supportsFileAttributeView("posix")) {
                    assertEquals(
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(backup.toPath()),
                    )
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lockedKeychainPreservesEncryptedFileAndRestoresCredentialsAfterUnlock() {
        val directory = createTempDirectory("metrolist-macos-locked").toFile()
        try {
            val file = File(directory, "secrets.properties")
            val keychain = TestKeychain()
            MacOsKeychainSecretStore(file, keychain::run).apply {
                put("innerTubeCookie", "retained-session")
                put("spotifySession", "retained-spotify-session")
                flush()
            }
            val original = file.readBytes()
            keychain.locked = true

            val failure = assertFailsWith<IllegalStateException> { MacOsKeychainSecretStore(file, keychain::run) }
            assertFalse(failure.message.orEmpty().contains("sensitive-command-output"))
            val fallback = UnavailableSecretStore(file, failure)
            val settings = DesktopFileSettings(File(directory, "settings.properties"), Properties(), fallback)
            Settings(settings)
            settings.flush()
            assertContentEquals(original, file.readBytes())
            assertTrue(directory.listFiles()!!.none { it.extension == "bak" })

            keychain.locked = false
            val restored = MacOsKeychainSecretStore(file, keychain::run)
            assertEquals("retained-session", restored.get("innerTubeCookie"))
            assertEquals("retained-spotify-session", restored.get("spotifySession"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fallbackMigrationEncryptsAllCredentialsBeforeRestart() {
        val directory = createTempDirectory("metrolist-macos-fallback").toFile()
        try {
            val file = File(directory, "secrets.properties")
            UnavailableSecretStore(file, IllegalStateException("unavailable")).apply {
                put("innerTubeCookie", "fallback-session")
                put("spotifySession", "fallback-spotify-session")
                flush()
            }
            val keychain = TestKeychain()
            val migrated = MacOsKeychainSecretStore(file, keychain::run)
            assertEquals("fallback-session", migrated.get("innerTubeCookie"))
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("fallback-session"))

            val restored = MacOsKeychainSecretStore(file, keychain::run)
            assertEquals("fallback-session", restored.get("innerTubeCookie"))
            assertEquals("fallback-spotify-session", restored.get("spotifySession"))
            assertTrue(directory.listFiles()!!.none { it.extension == "bak" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unavailableStoreDiagnosticsExcludeExceptionDetails() {
        val directory = createTempDirectory("metrolist-secret-diagnostics").toFile()
        val previousError = System.err
        val output = ByteArrayOutputStream()
        try {
            PrintStream(output).use { errorStream ->
                System.setErr(errorStream)
                UnavailableSecretStore(File(directory, "secrets.properties"), IllegalStateException("sensitive-command-output"))
            }
            assertTrue(output.toString().contains("IllegalStateException"))
            assertFalse(output.toString().contains("sensitive-command-output"))
        } finally {
            System.setErr(previousError)
            directory.deleteRecursively()
        }
    }

    private class TestKeychain {
        var encryptionKey: String? = null
        var locked = false

        fun run(command: List<String>): CredentialCommandResult {
            if (locked) return CredentialCommandResult(36, "", "sensitive-command-output")
            return when (command[1]) {
                "find-generic-password" -> {
                    val service = command[command.indexOf("-s") + 1]
                    val value = if (service == "com.metrolist.desktop.encryption-key-v1") encryptionKey else null
                    if (value == null) CredentialCommandResult(44, "", "") else CredentialCommandResult(0, value, "")
                }

                "add-generic-password" -> {
                    encryptionKey = command.last()
                    CredentialCommandResult(0, "", "")
                }

                "delete-generic-password" -> {
                    CredentialCommandResult(44, "", "")
                }

                else -> {
                    error("Unexpected Keychain command")
                }
            }
        }
    }
}
