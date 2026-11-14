package com.metrolist.shared.settings

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopSecretStoreTest {
    @Test
    fun unavailableKeychainRecoveryMergesNewerFallbackCredentials() {
        val directory = createTempDirectory("metrolist-keychain-recovery")
        try {
            val fallbackFile = directory.resolve("fallback.properties").toFile()
            val recoveredFile = directory.resolve("recovered.properties").toFile()
            val unavailable =
                UnavailableSecretStore(fallbackFile, IllegalStateException("locked")) {
                    UnavailableSecretStore(recoveredFile, IllegalStateException("test store")).apply {
                        put("innerTubeCookie", "old")
                        put("visitorData", "retained")
                    }
                }
            unavailable.put("innerTubeCookie", "new")
            unavailable.flush()
            assertTrue(unavailable.isPending)
            val recovered = assertNotNull(unavailable.recover())
            assertEquals("new", recovered.get("innerTubeCookie"))
            assertEquals("retained", recovered.get("visitorData"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun newerFallbackSecretsOverrideRecoveredSecureValues() {
        val secure =
            Properties().apply {
                setProperty("innerTubeCookie", "old-cookie")
                setProperty("secureOnly", "retained")
            }
        val fallback = Properties().apply { setProperty("innerTubeCookie", "new-cookie") }

        mergeFallbackSecrets(secure, fallback)

        assertEquals("new-cookie", secure.getProperty("innerTubeCookie"))
        assertEquals("retained", secure.getProperty("secureOnly"))
    }

    @Test
    fun unavailableSecureStorePersistsCredentialsInOwnerOnlyFile() {
        val directory = createTempDirectory("metrolist-secrets-test")
        try {
            val file = directory.resolve("secrets.properties").toFile()
            UnavailableSecretStore(file, IllegalStateException("unavailable")).apply {
                put("innerTubeCookie", "login-cookie")
                flush()
            }

            val restored = UnavailableSecretStore(file, IllegalStateException("unavailable"))
            assertEquals("login-cookie", restored.get("innerTubeCookie"))
            if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file.toPath()),
                )
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
