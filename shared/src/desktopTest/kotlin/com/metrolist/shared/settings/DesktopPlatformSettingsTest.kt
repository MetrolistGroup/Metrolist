/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import java.io.File
import java.util.Properties
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlatformSettingsTest {
    @Test
    fun providerAiKeysMigrateToAndRemainInTheSecretStore() {
        val root = createTempDirectory("provider-ai-keys").toFile()
        try {
            val file = File(root, "settings.properties")
            val public = Properties().apply { setProperty("lyricsAiApiKeys", "test-provider-keys") }
            file.outputStream().use { public.store(it, null) }
            val secrets = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("Test store"))
            migratePublicSecrets(public, secrets, file)
            assertEquals("test-provider-keys", secrets.get("lyricsAiApiKeys"))
            assertFalse(loadPublicProperties(file).containsKey("lyricsAiApiKeys"))
            val settings = DesktopFileSettings(file, public, secrets)
            settings.putString("lyricsAiApiKeys", "updated-test-keys")
            settings.flush()
            assertEquals("updated-test-keys", secrets.get("lyricsAiApiKeys"))
            assertFalse(loadPublicProperties(file).containsKey("lyricsAiApiKeys"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun largeAtomicWritesDoNotRequirePayloadSizedDirectBuffers() {
        val root = createTempDirectory("desktop-settings-direct-memory").toFile()
        val output = File(root, "process.log")
        val process =
            ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-XX:MaxDirectMemorySize=1m",
                "-cp",
                System.getProperty("java.class.path"),
                javaClass.name,
                root.absolutePath,
            ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Settings write timed out")
            assertEquals(0, process.exitValue(), output.readText())
        } finally {
            process.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedBlobMigrationRetainsTheLegacyValue() {
        val root = createTempDirectory("desktop-blob-migration").toFile()
        try {
            File(root, "settings-blobs").writeText("unavailable directory")
            val file = File(root, "settings.properties")
            val public = Properties().apply { setProperty("persistentPlaybackState", "legacy") }
            file.outputStream().use { public.store(it, null) }
            val secrets = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("Test fallback"))
            val settings = DesktopFileSettings(file, public, secrets)
            assertEquals("legacy", settings.getStringOrNull("persistentPlaybackState"))
            settings.putString("persistentPlaybackState", "new")
            assertFails { settings.flush() }
            assertEquals("new", settings.getStringOrNull("persistentPlaybackState"))
            assertEquals("legacy", loadPublicProperties(file).getProperty("persistentPlaybackState"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun largeSettingsMigrateToFilesAndSmallWritesDoNotRewriteTheirPayloads() {
        val root = createTempDirectory("desktop-blob-settings").toFile()
        try {
            val file = File(root, "settings.properties")
            val public = Properties().apply { setProperty("persistentPlaybackState", "queue-東京") }
            file.outputStream().use { public.store(it, null) }
            val secrets = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("Test fallback"))
            val settings = DesktopFileSettings(file, public, secrets)
            settings.flush()
            val blob = File(root, "settings-blobs/persistentPlaybackState")
            assertEquals("queue-東京", blob.readText())
            assertFalse(file.readText().contains("persistentPlaybackState"))
            blob.setLastModified(1_600_000_000_000L)
            val modified = blob.lastModified()
            settings.putString("persistentPlaybackPosition", "5000")
            settings.flush()
            assertEquals("queue-東京", blob.readText())
            assertEquals(modified, blob.lastModified())
            assertEquals("queue-東京", settings.getStringOrNull("persistentPlaybackState"))
            assertTrue(settings.hasKey("persistentPlaybackState"))
            assertTrue("persistentPlaybackState" in settings.keys)
            settings.putString("persistentPlaybackState", "updated")
            settings.flush()
            val reopened = DesktopFileSettings(file, loadPublicProperties(file), secrets)
            assertEquals("updated", reopened.getStringOrNull("persistentPlaybackState"))
            reopened.remove("persistentPlaybackState")
            reopened.flush()
            assertNull(reopened.getStringOrNull("persistentPlaybackState"))
            assertFalse(blob.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedAsynchronousWriteRemainsDirtyUntilLatestSnapshotIsPersisted() {
        val tempDir = createTempDirectory("metrolist-settings-retry").toFile()
        try {
            val parent = File(tempDir, "blocked").apply { writeText("not a directory") }
            val file = File(parent, "settings.properties")
            val secrets = UnavailableSecretStore(File(tempDir, "secrets.properties"), RuntimeException("Test fallback"))
            val settings = DesktopFileSettings(file, Properties(), secrets)
            val scheduled = DesktopFileSettings::class.java.getDeclaredField("pendingWrite").apply { isAccessible = true }
            val write =
                synchronized(settings) {
                    settings.putString("queue", "old")
                    scheduled.get(settings) as ScheduledFuture<*>
                }
            write.get(30, TimeUnit.SECONDS)
            assertFailsWith<Exception> { settings.flush() }
            assertTrue(parent.delete())
            assertTrue(parent.mkdir())
            settings.flush()
            assertEquals("old", loadPublicProperties(file).getProperty("queue"))
            settings.putString("queue", "new")
            settings.flush()
            assertEquals("new", loadPublicProperties(file).getProperty("queue"))
            file.writeText("queue=external\n")
            settings.flush()
            assertEquals("external", loadPublicProperties(file).getProperty("queue"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun checkpointsAndCoalescedBlobsNeverRewriteThePublicStore() {
        val root = createTempDirectory("desktop-checkpoints").toFile()
        try {
            val file = File(root, "settings.properties")
            val secrets = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("test"))
            val settings = DesktopFileSettings(file, Properties(), secrets)
            settings.putString("theme", "dark")
            settings.putString("likedSongsJson", "large catalog")
            settings.flush()
            file.setLastModified(1_600_000_000_000L)
            val modified = file.lastModified()
            repeat(100) {
                settings.putString("persistentPlaybackState", "queue-$it")
                settings.putString("persistentPlaybackPosition", "position-$it")
                assertEquals("queue-$it", settings.getStringOrNull("persistentPlaybackState"))
            }
            settings.flush()
            assertEquals(modified, file.lastModified())
            assertFalse(file.readText().contains("large catalog"))
            val reopened = DesktopFileSettings(file, loadPublicProperties(file), secrets)
            assertEquals("queue-99", reopened.getStringOrNull("persistentPlaybackState"))
            assertEquals("position-99", reopened.getStringOrNull("persistentPlaybackPosition"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absentSecretsAndUnchangedBatchesDoNotWriteAndFailedBatchRollsBack() {
        val root = createTempDirectory("desktop-secret-batch").toFile()
        try {
            val backing = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("test"))
            var writes = 0
            var fail = false
            val secrets =
                object : SecretStore by backing {
                    override fun flush() {
                        writes++
                        if (fail) error("write failed")
                        backing.flush()
                    }
                }
            val settings = DesktopFileSettings(File(root, "settings.properties"), Properties(), secrets)
            settings.remove("listenTogetherPairedRoomCode")
            assertEquals(0, writes)
            val credentials =
                listOf(
                    PortableSetting("innerTubeCookie", SettingType.STRING, "cookie"),
                    PortableSetting("visitorData", SettingType.STRING, "visitor"),
                )
            settings.applyBatch(credentials, emptySet())
            settings.applyBatch(credentials, emptySet())
            assertEquals(1, writes)
            fail = true
            assertFails { settings.applyBatch(listOf(credentials.first().copy(value = "new")), setOf("visitorData")) }
            assertEquals("cookie", settings.getStringOrNull("innerTubeCookie"))
            assertEquals("visitor", settings.getStringOrNull("visitorData"))
            assertEquals("cookie", backing.get("innerTubeCookie"))
            assertEquals("visitor", backing.get("visitorData"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun migratesAndRoutesDesktopSpotifySessionOutsidePublicSettings() {
        val tempDir =
            createTempDirectory("metrolist-spotify-settings")
                .toFile()
        try {
            val settingsFile = File(tempDir, "settings.properties").apply { writeText("spotifySession=own-cookie\n") }
            val public = Properties().apply { setProperty("spotifySession", "own-cookie") }
            val secrets = UnavailableSecretStore(File(tempDir, "secrets.properties"), RuntimeException("Test fallback"))

            migratePublicSecrets(public, secrets, settingsFile)
            assertFalse(public.containsKey("spotifySession"))
            assertFalse(settingsFile.readText().contains("own-cookie"))
            assertEquals("own-cookie", secrets.get("spotifySession"))

            val settings = DesktopFileSettings(settingsFile, public, secrets)
            settings.putString("spotifySession", "updated-cookie")
            assertEquals("updated-cookie", settings.getString("spotifySession", ""))
            assertEquals("updated-cookie", secrets.get("spotifySession"))
            assertFalse(settingsFile.readText().contains("updated-cookie"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun recoversPendingPublicSettingsAndPromotesThemOnNextWrite() {
        val tempDir = createTempDirectory("metrolist-pending-settings").toFile()
        try {
            val settingsFile = File(tempDir, "settings.properties").apply { writeText("themeMode=0\n") }
            val pendingFile = File(tempDir, ".settings.properties.pending").apply { writeText("themeMode=2\n") }
            val public = loadPublicProperties(settingsFile)
            assertEquals("2", public.getProperty("themeMode"))

            val secrets = UnavailableSecretStore(File(tempDir, "secrets.properties"), RuntimeException("Test fallback"))
            DesktopFileSettings(settingsFile, public, secrets).apply {
                putString("shuffleEnabled", "true")
                flush()
            }

            assertFalse(pendingFile.exists())
            val reloaded = loadPublicProperties(settingsFile)
            assertEquals("2", reloaded.getProperty("themeMode"))
            assertEquals("true", reloaded.getProperty("shuffleEnabled"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun coalescedWritesFlushTheLatestValuesAndRemovals() {
        val tempDir = createTempDirectory("metrolist-coalesced-settings").toFile()
        try {
            val file = File(tempDir, "settings.properties")
            val secrets = UnavailableSecretStore(File(tempDir, "secrets.properties"), RuntimeException("Test fallback"))
            val settings = DesktopFileSettings(file, Properties(), secrets)
            settings.flush()
            assertFalse(file.exists())
            repeat(1000) { settings.putInt("position", it) }
            settings.putString("removed", "value")
            settings.remove("removed")
            assertEquals(999, settings.getInt("position", -1))
            settings.flush()
            val persisted = loadPublicProperties(file)
            assertEquals("999", persisted.getProperty("position"))
            assertFalse(persisted.containsKey("removed"))
            settings.putString("persistentPlaybackState", "queue-payload")
            settings.putString("innerTubeCookie", "session-cookie")
            assertEquals(setOf("position", "persistentPlaybackState", "innerTubeCookie"), settings.keys)
            assertEquals(3, settings.size)
            settings.clear()
            settings.flush()
            assertTrue(settings.keys.isEmpty())
            assertFalse(File(tempDir, "settings-blobs/persistentPlaybackState").exists())
            assertNull(settings.getStringOrNull("persistentPlaybackState"))
            assertNull(settings.getStringOrNull("innerTubeCookie"))
            assertNull(secrets.get("innerTubeCookie"))
            settings.putString("queue", "latest")
            settings.flush()
            assertEquals(setOf("queue"), loadPublicProperties(file).stringPropertyNames())
            assertEquals("latest", loadPublicProperties(file).getProperty("queue"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun unavailableSecretStorePersistsAndRestoresSecrets() {
        val tempDir =
            createTempDirectory("metrolist-test-secrets")
                .toFile()
        try {
            val secretsFile = File(tempDir, "secrets.properties")
            val store = UnavailableSecretStore(secretsFile, RuntimeException("Test failure"))

            store.put("innerTubeCookie", "test-cookie-123")
            store.put("discordAccessToken", "test-discord-token")
            store.flush()

            assertTrue(secretsFile.exists())
            assertTrue(secretsFile.length() > 0L)

            val reloadedStore = UnavailableSecretStore(secretsFile, RuntimeException("Test reload"))
            assertEquals("test-cookie-123", reloadedStore.get("innerTubeCookie"))
            assertEquals("test-discord-token", reloadedStore.get("discordAccessToken"))

            reloadedStore.clear()
            reloadedStore.flush()

            val clearedStore = UnavailableSecretStore(secretsFile, RuntimeException("Test cleared"))
            assertNull(clearedStore.get("innerTubeCookie"))
            assertNull(clearedStore.get("discordAccessToken"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val root = File(args.single())
            val file = File(root, "settings.properties")
            val secrets = UnavailableSecretStore(File(root, "secrets.properties"), RuntimeException("Test fallback"))
            val settings = DesktopFileSettings(file, Properties(), secrets)
            val payload = "queue-東京\n".repeat(256 * 1024)
            settings.putString("queue", payload)
            settings.putString("persistentPlaybackState", payload)
            settings.flush()
            assertEquals(payload, loadPublicProperties(file).getProperty("queue"))
            assertEquals(payload, File(root, "settings-blobs/persistentPlaybackState").readText())
            assertTrue(root.walkTopDown().none { it.extension == "tmp" })
        }
    }
}
