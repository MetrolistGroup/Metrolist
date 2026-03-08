package com.metrolist.shared.settings

import com.metrolist.shared.auth.AuthManager
import com.metrolist.shared.auth.invalidateYouTubeSession
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.LogBridge
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxCredentialRecoveryTest {
    @Test
    fun startupLoadFailuresReachTheLoggerWithoutCredentialOutput() {
        val root = createTempDirectory("metrolist-credential-log").toFile()
        val previousSink = LogBridge.sink
        try {
            LogBridge.sink = null
            LinuxSecretServiceStore(
                File(root, "secrets.properties"),
                secretToolPath = "secret-tool",
                runCommand = { _, _ -> CredentialCommandResult(2, "fake-secret-output", "fake-secret-error") },
            )
            val messages = mutableListOf<String>()
            LogBridge.sink = { level, tag, message, _ -> messages += "$level $tag $message" }
            flushDesktopCredentialWarnings()
            assertTrue(messages.any { it.contains("WARN DesktopCredentials Credential load failed") })
            assertFalse(messages.any { it.contains("fake-secret") })
        } finally {
            LogBridge.sink = previousSink
            root.deleteRecursively()
        }
    }

    private class Service {
        var payload = "innerTubeCookie=SAPISID=stored\nvisitorData=stored-visitor\nlastFmSession=stored-lastfm\n"
        var failure: String? = "locked"
        var failWrites = false
        var storedPayload: (String) -> String = { it }
        var beforeCommand: (String) -> Unit = {}
        val writes = mutableListOf<String>()

        fun command(
            command: List<String>,
            input: String,
        ): CredentialCommandResult {
            beforeCommand(command[1])
            return when {
                command.first() == "dbus-send" -> {
                    CredentialCommandResult(0, "array [ ] array [ object path \"/locked/item\" ]", "")
                }

                command[1] == "lookup" -> {
                    when (failure) {
                        "locked" -> CredentialCommandResult(1, "", "")
                        "timeout" -> throw IllegalStateException("timeout")
                        "absent" -> CredentialCommandResult(1, "", "No service")
                        "invalid" -> CredentialCommandResult(0, "innerTubeCookie=\\uXYZW", "")
                        else -> CredentialCommandResult(0, payload, "")
                    }
                }

                command[1] == "store" -> {
                    if (failWrites) return CredentialCommandResult(1, "", "locked")
                    writes += input
                    payload = storedPayload(input)
                    CredentialCommandResult(0, "", "")
                }

                else -> {
                    error("Unexpected command")
                }
            }
        }

        fun store(file: File) = LinuxSecretServiceStore(file, secretToolPath = "secret-tool", runCommand = ::command)
    }

    @Test
    fun restoredSessionsRemainAvailableWhenKeyringSyncFails() {
        for (lockedAtStartup in listOf(false, true)) {
            for (truncate in listOf(false, true)) {
                val root = createTempDirectory("metrolist-restored-sync-failure").toFile()
                try {
                    val service =
                        Service().apply {
                            failure = if (lockedAtStartup) "locked" else null
                            payload += "discordAccessToken=stored-discord\nspotifySession=stored-spotify\nlyricsDeepLApiKey=stored-deepl\n"
                            failWrites = !truncate
                            if (truncate) storedPayload = { it.lineSequence().first() }
                        }
                    val file = File(root, "secrets.properties").apply { writeText("visitorData=pending-visitor\n") }
                    val store = service.store(file)
                    val backing = DesktopFileSettings(File(root, "settings.properties"), migratedProperties(), store)
                    val settings = Settings(backing)
                    if (lockedAtStartup) {
                        assertTrue(settings.credentialsPending)
                        service.failure = null
                        assertEquals(true, backing.recoverSecrets(settings::finishPendingSecretMigrations))
                        settings.refreshStoredValues()
                    }
                    assertFalse(settings.credentialsPending)
                    assertEquals("SAPISID=stored", settings.innerTubeCookie)
                    assertEquals("stored-lastfm", settings.lastFmSession)
                    assertEquals("stored-discord", settings.discordAccessToken)
                    assertEquals("stored-spotify", settings.spotifySession)
                    assertEquals("stored-deepl", settings.lyricsDeepLApiKey)
                    assertEquals("pending-visitor", settings.visitorData)
                    assertTrue(store.needsSync)
                    assertTrue(file.exists())
                    val restarted = service.store(file)
                    assertFalse(restarted.isPending)
                    assertEquals(store.snapshot(), restarted.snapshot())
                    service.failWrites = false
                    service.storedPayload = { it }
                    assertTrue(restarted.recover())
                    assertFalse(file.exists())
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun successfulStoreWithTruncatedOrStaleLookupKeepsPendingTranslatorKeysAcrossRestart() {
        for (truncate in listOf(true, false)) {
            val root = createTempDirectory("metrolist-read-back").toFile()
            try {
                val service = Service().apply { failure = null }
                val original = service.payload
                service.storedPayload = { if (truncate) it.lineSequence().first() else original }
                val file = File(root, "secrets.properties")
                val store = service.store(file)
                store.update {
                    put("lyricsDeepLApiKey", "fake-deepl")
                    put("lyricsAiApiKeys", "{\"provider\":\"fake-ai\"}")
                }
                assertFalse(store.recover())
                assertTrue(file.exists())
                val restarted = service.store(file)
                assertEquals("fake-deepl", restarted.get("lyricsDeepLApiKey"))
                assertEquals("{\"provider\":\"fake-ai\"}", restarted.get("lyricsAiApiKeys"))
                assertEquals("stored-lastfm", restarted.get("lastFmSession"))
                assertEquals("SAPISID=stored", restarted.get("innerTubeCookie"))
                assertTrue(file.exists())
                service.storedPayload = { it }
                assertTrue(restarted.recover())
                assertFalse(file.exists())
                assertEquals(restarted.snapshot(), service.store(file).snapshot())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun failedReadsNeverReplaceUnreadCredentialsAndPartialWritesSurviveRestart() {
        for (failure in listOf("locked", "timeout", "absent", "invalid")) {
            val root = createTempDirectory("metrolist-read-failure").toFile()
            try {
                val service = Service().apply { this.failure = failure }
                val file = File(root, "secrets.properties")
                val store = service.store(file)
                assertTrue(store.isPending)
                store.put("visitorData", "new-visitor")
                assertFalse(store.recover())
                assertTrue(service.writes.isEmpty())
                val restarted = service.store(file)
                assertTrue(restarted.isPending)
                assertEquals("new-visitor", restarted.get("visitorData"))
                service.failure = null
                assertTrue(restarted.recover())
                assertEquals("SAPISID=stored", restarted.get("innerTubeCookie"))
                assertEquals("stored-lastfm", restarted.get("lastFmSession"))
                assertEquals("new-visitor", restarted.get("visitorData"))
                assertFalse(file.exists())
                assertEquals(restarted.snapshot(), service.store(file).snapshot())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun missingToolIsRetriedAndNewLoginWinsOverStoredAccount() {
        val root = createTempDirectory("metrolist-missing-tool").toFile()
        try {
            val service = Service().apply { failure = null }
            var tool: String? = null
            val file = File(root, "secrets.properties")
            val store = LinuxSecretServiceStore(file, null, service::command, findSecretTool = { tool })
            assertTrue(store.isPending)
            store.update {
                put("innerTubeCookie", "SAPISID=new")
                put("innerTubeAuthUser", "2")
                put("visitorData", "new-visitor")
            }
            assertFalse(store.recover())
            tool = "secret-tool"
            assertTrue(store.recover())
            assertEquals("SAPISID=new", store.get("innerTubeCookie"))
            assertEquals("2", store.get("innerTubeAuthUser"))
            assertEquals("stored-lastfm", store.get("lastFmSession"))
            assertEquals(store.snapshot(), service.store(file).snapshot())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onlyConfirmedAbsenceAllowsANewEmptyStore() {
        val root = createTempDirectory("metrolist-empty-keyring").toFile()
        try {
            var search = CredentialCommandResult(1, "", "no service")
            val store =
                LinuxSecretServiceStore(
                    File(root, "secrets.properties"),
                    "secret-tool",
                    runCommand = { command, _ ->
                        when {
                            command.last() == "string:Collections" -> CredentialCommandResult(0, "variant array [ ]", "")
                            command.first() == "dbus-send" -> search
                            else -> CredentialCommandResult(1, "", "")
                        }
                    },
                )
            assertTrue(store.isPending)
            search = CredentialCommandResult(0, "array [ ] array [ ]", "")
            assertTrue(store.recover())
            assertFalse(store.isPending)
            assertTrue(store.keys().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptySearchCannotHideALockedCollection() {
        val root = createTempDirectory("metrolist-locked-collection").toFile()
        try {
            var locked = true
            val store =
                LinuxSecretServiceStore(
                    File(root, "secrets.properties"),
                    "secret-tool",
                    runCommand = { command, _ ->
                        when {
                            command[1] == "lookup" -> {
                                CredentialCommandResult(1, "", "")
                            }

                            command.last() == "string:Collections" -> {
                                CredentialCommandResult(
                                    0,
                                    "variant array [ object path \"/collection/login\" ]",
                                    "",
                                )
                            }

                            command.last() == "string:Locked" -> {
                                CredentialCommandResult(0, "variant boolean $locked", "")
                            }

                            else -> {
                                CredentialCommandResult(0, "array [ ] array [ ]", "")
                            }
                        }
                    },
                )
            assertTrue(store.isPending)
            assertFalse(store.recover())
            locked = false
            assertTrue(store.recover())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removalsAndClearWhileLockedDoNotResurrectSecretsAfterRestart() {
        for (clear in listOf(false, true)) {
            val root = createTempDirectory("metrolist-credential-removal").toFile()
            try {
                val service = Service()
                val file = File(root, "secrets.properties")
                val store = service.store(file)
                if (clear) store.clear() else store.remove("innerTubeCookie")
                store.put("visitorData", "after-removal")
                val restarted = service.store(file)
                service.failure = null
                assertTrue(restarted.recover())
                assertNull(restarted.get("innerTubeCookie"))
                assertEquals(if (clear) null else "stored-lastfm", restarted.get("lastFmSession"))
                assertEquals("after-removal", restarted.get("visitorData"))
                assertEquals(restarted.snapshot(), service.store(file).snapshot())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun writesDuringLookupStoreAndMigrationAreNonblockingDurableAndReplayedInOrder() {
        for (blockedCommand in listOf("lookup", "store", "migration")) {
            val root = createTempDirectory("metrolist-recovery-race").toFile()
            val executor = Executors.newFixedThreadPool(2)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val service = Service()
                val file = File(root, "secrets.properties")
                val store = service.store(file)
                val settings = DesktopFileSettings(File(root, "settings.properties"), migratedProperties(), store)
                settings.putString("visitorData", "before-recovery")
                service.failure = null
                service.beforeCommand = {
                    if (it == blockedCommand) {
                        started.countDown()
                        check(release.await(60, TimeUnit.SECONDS))
                    }
                }
                val recovering =
                    executor.submit<Boolean?> {
                        settings.recoverSecrets {
                            if (blockedCommand == "migration") {
                                started.countDown()
                                check(release.await(60, TimeUnit.SECONDS))
                            }
                        }
                    }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                executor
                    .submit {
                        settings.applyBatch(
                            listOf(
                                PortableSetting("innerTubeCookie", SettingType.STRING, "SAPISID=first-login"),
                                PortableSetting("innerTubeAuthUser", SettingType.STRING, "1"),
                            ),
                            emptySet(),
                        )
                        settings.remove("innerTubeCookie")
                        settings.applyBatch(
                            listOf(
                                PortableSetting("innerTubeCookie", SettingType.STRING, "SAPISID=second-login"),
                                PortableSetting("innerTubeAuthUser", SettingType.STRING, "2"),
                            ),
                            emptySet(),
                        )
                        settings.putString("visitorData", "latest-visitor")
                        assertEquals("SAPISID=second-login", settings.getStringOrNull("innerTubeCookie"))
                        assertTrue(file.readText().contains("SAPISID\\=second-login"))
                    }.get(30, TimeUnit.SECONDS)
                release.countDown()
                assertEquals(true, recovering.get(5, TimeUnit.SECONDS))
                val restarted = service.store(file)
                assertEquals("SAPISID=second-login", restarted.get("innerTubeCookie"))
                assertEquals("2", restarted.get("innerTubeAuthUser"))
                assertEquals("latest-visitor", restarted.get("visitorData"))
                assertEquals("stored-lastfm", restarted.get("lastFmSession"))
                assertEquals(restarted.snapshot(), store.snapshot())
            } finally {
                release.countDown()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun serviceFailureAfterSuccessfulStartupRetainsWritesAcrossRestart() {
        val root = createTempDirectory("metrolist-write-failure").toFile()
        try {
            val service = Service().apply { failure = null }
            val file = File(root, "secrets.properties")
            val store = service.store(file)
            service.failWrites = true
            store.put("innerTubeCookie", "SAPISID=new")
            assertFalse(store.recover())
            assertEquals("SAPISID=new", store.get("innerTubeCookie"))
            val restarted = service.store(file)
            assertFalse(restarted.isPending)
            service.failWrites = false
            assertTrue(restarted.recover())
            assertEquals("SAPISID=new", restarted.get("innerTubeCookie"))
            assertEquals("stored-lastfm", restarted.get("lastFmSession"))
            assertEquals(restarted.snapshot(), service.store(file).snapshot())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedLocalCommitKeepsThePersistedAndRuntimeSession() {
        val root = createTempDirectory("metrolist-local-write-failure").toFile()
        val previous = GlobalInnerTube.instance.sessionSnapshot()
        try {
            val service = Service().apply { failure = null }
            val file = File(root, "secrets.properties")
            val store = service.store(file)
            val settings = Settings(DesktopFileSettings(File(root, "settings.properties"), migratedProperties(), store))
            Settings.replaceSharedForTest(settings)
            GlobalInnerTube.instance.replaceSession("SAPISID=stored", "stored-visitor", "sync", "1", true)
            val before = GlobalInnerTube.instance.sessionSnapshot()
            if (file.exists()) assertTrue(file.delete())
            assertTrue(file.mkdir())
            File(file, "cannot-replace-directory").writeText("test")
            assertFailsWith<Exception> { invalidateYouTubeSession(GlobalInnerTube.instance) }
            assertEquals(before, GlobalInnerTube.instance.sessionSnapshot())
            assertEquals("SAPISID=stored", settings.innerTubeCookie)
            assertEquals("SAPISID=stored", store.get("innerTubeCookie"))
        } finally {
            GlobalInnerTube.instance.replaceSession(
                previous.cookie,
                previous.visitorData,
                previous.dataSyncId,
                previous.authUser,
                previous.useLoginForBrowse,
            )
            Settings.replaceSharedForTest(null)
            root.deleteRecursively()
        }
    }

    @Test
    fun pendingStartupDoesNotFetchAnonymousVisitorDataAndExplicitLogoutIsDurable() =
        runBlocking {
            val root = createTempDirectory("metrolist-pending-session").toFile()
            val previous = GlobalInnerTube.instance.sessionSnapshot()
            val previousLocale = GlobalYouTube.innerTube.locale
            val previousRegionOverride = GlobalYouTube.innerTube.regionOverrideActive
            try {
                val service = Service()
                val file = File(root, "secrets.properties")
                val store = service.store(file)
                val settings = Settings(DesktopFileSettings(File(root, "settings.properties"), migratedProperties(), store))
                Settings.replaceSharedForTest(settings)
                AuthManager().restoreSession { error("Pending credentials must not fetch anonymous visitor data") }
                GlobalYouTube.applyContentRegion("SYSTEM", "SYSTEM", "PL", "pl", settings)
                assertFalse(file.exists())
                invalidateYouTubeSession(GlobalInnerTube.instance)
                val restarted = service.store(file)
                service.failure = null
                assertTrue(restarted.recover())
                assertEquals("", restarted.get("innerTubeCookie"))
                assertEquals("", restarted.get("visitorData"))
                assertEquals("stored-lastfm", restarted.get("lastFmSession"))
            } finally {
                GlobalYouTube.innerTube.locale = previousLocale
                GlobalYouTube.innerTube.regionOverrideActive = previousRegionOverride
                GlobalInnerTube.instance.replaceSession(
                    previous.cookie,
                    previous.visitorData,
                    previous.dataSyncId,
                    previous.authUser,
                    previous.useLoginForBrowse,
                )
                Settings.replaceSharedForTest(null)
                root.deleteRecursively()
            }
        }

    private fun migratedProperties() =
        Properties().apply {
            setProperty("desktopLegacySecretCleanupV2", "true")
            setProperty("desktopLegacyPreferencesMigrated", "true")
            setProperty("desktopAutoCacheDefaultDisabledV1", "true")
        }
}
