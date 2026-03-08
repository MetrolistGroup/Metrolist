package com.metrolist.shared.settings

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object LinuxSecretServiceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(requireNotNull(System.getenv("METROLIST_CREDENTIAL_TEST_ROOT")))
        check(System.getenv("HOME") == root.path)
        check(System.getenv("XDG_DATA_HOME") == File(root, "data").path)
        val file = File(root, "profile/secrets.properties")
        val public =
            Properties().apply {
                setProperty("desktopLegacySecretCleanupV2", "true")
                setProperty("desktopLegacyPreferencesMigrated", "true")
                setProperty("desktopAutoCacheDefaultDisabledV1", "true")
            }
        val store = withStartupCredentialDeadline { LinuxSecretServiceStore(file) }
        val settings = DesktopFileSettings(File(root, "profile/settings.properties"), public, store)
        when (args[0]) {
            "seed" -> {
                assertFalse(store.isPending)
                settings.applyBatch(
                    listOf(
                        PortableSetting("innerTubeCookie", SettingType.STRING, "SAPISID=fake-cookie"),
                        PortableSetting("innerTubeAuthUser", SettingType.STRING, "1"),
                        PortableSetting("visitorData", SettingType.STRING, "fake-visitor"),
                        PortableSetting("lastFmSession", SettingType.STRING, "fake-lastfm"),
                        PortableSetting("discordAccessToken", SettingType.STRING, "fake-discord"),
                    ),
                    emptySet(),
                )
                assertTrue(store.recover())
            }

            "pending" -> {
                assertTrue(store.isPending)
                args.drop(1).forEach { entry -> settings.putString(entry.substringBefore('='), entry.substringAfter('=')) }
                assertFalse(store.recover())
                settings.flush()
            }

            "recovered" -> {
                assertFalse(store.isPending)
                args.drop(1).forEach { entry ->
                    assertEquals(entry.substringAfter('='), settings.getStringOrNull(entry.substringBefore('=')))
                }
                assertFalse(file.exists())
            }

            "tool-recovery" -> {
                assertTrue(store.isPending)
                settings.putString("lyricsDeepLApiKey", "fake-tool-recovery")
                Files.createSymbolicLink(
                    File(root, "without-secret-tool/secret-tool").toPath(),
                    File(requireNotNull(System.getenv("METROLIST_CREDENTIAL_TEST_SECRET_TOOL"))).toPath(),
                )
                assertEquals(true, settings.recoverSecrets())
                assertEquals("SAPISID=fake-cookie", settings.getStringOrNull("innerTubeCookie"))
                assertFalse(file.exists())
            }

            "race" -> {
                race(root, file, public)
            }

            "large-write" -> {
                assertFalse(store.isPending)
                settings.putString("lyricsAiApiKeys", "x".repeat(9000))
                settings.putString("lyricsDeepLApiKey", "fake-large-write")
                val attributes = listOf("service", "com.metrolist.desktop.cli-limit-test")
                val legacyPayload = "#Metrolist desktop secrets\nlyricsAiApiKeys=" + "x".repeat(9000)
                assertEquals(
                    0,
                    runCredentialCommand(
                        listOf("secret-tool", "store", "--label=Metrolist Desktop Secrets") + attributes,
                        legacyPayload,
                    ).exit,
                )
                val truncated = runCredentialCommand(listOf("secret-tool", "lookup") + attributes)
                assertEquals(8192, truncated.output.length)
                assertTrue(store.recover())
                assertFalse(file.exists())
                assertEquals("SAPISID=last-login", settings.getStringOrNull("innerTubeCookie"))
            }

            "large-restart" -> {
                assertFalse(file.exists())
                assertEquals("x".repeat(9000), settings.getStringOrNull("lyricsAiApiKeys"))
                assertEquals("fake-large-write", settings.getStringOrNull("lyricsDeepLApiKey"))
                assertEquals("SAPISID=last-login", settings.getStringOrNull("innerTubeCookie"))
                assertEquals("lastfm-while-service-absent", settings.getStringOrNull("lastFmSession"))
            }

            "default-keyring" -> {
                assertFalse(store.isPending)
                settings.putString("lyricsDeepLApiKey", "fake-default-keyring")
                assertTrue(store.recover())
                assertFalse(file.exists())
            }

            "duplicate-keyrings" -> {
                assertFalse(store.isPending)
                assertEquals("SAPISID=last-login", settings.getStringOrNull("innerTubeCookie"))
                settings.putString("lyricsDeepLApiKey", "fake-duplicate-keyrings")
                assertTrue(store.recover())
                assertFalse(file.exists())
                assertEquals("lastfm-while-service-absent", store.get("lastFmSession"))
                assertEquals("fake-discord", store.get("discordAccessToken"))
            }

            "duplicate-write" -> {
                assertFalse(store.isPending)
                settings.putString("lyricsDeepLApiKey", args[1])
                assertTrue(store.recover())
                assertFalse(file.exists())
            }

            "unreadable-duplicates" -> {
                assertTrue(store.isPending)
                settings.putString("visitorData", "visitor-while-unreadable")
                assertFalse(store.recover())
                assertTrue(file.exists())
                assertEquals(
                    1,
                    runLinuxCredentialCommand(
                        listOf("secret-tool", "store", "--label=Metrolist Desktop Secrets") +
                            listOf("service", "com.metrolist.desktop", "schema", "metrolist-settings", "account", "desktop-secrets"),
                        "innerTubeCookie=must-not-replace-unreadable",
                    ).exit,
                )
            }

            "interrupted-save" -> {
                val interrupted =
                    LinuxSecretServiceStore(file, runCommand = { command, input ->
                        val result = runLinuxCredentialCommand(command, input)
                        if (command[1] == "store" && result.exit == 0) Runtime.getRuntime().halt(0)
                        result
                    })
                interrupted.put("visitorData", "visitor-before-halt")
                interrupted.recover()
                error("The credential writer must halt before verification")
            }

            "clear-duplicates" -> {
                store.clear()
                assertTrue(store.recover())
                assertTrue(store.snapshot().isEmpty())
                assertFalse(file.exists())
            }

            "cleared" -> {
                assertFalse(store.isPending)
                assertTrue(store.snapshot().isEmpty())
                assertFalse(file.exists())
            }

            "native-timeout" -> {
                val started = System.nanoTime()
                val result =
                    withStartupCredentialDeadline(1_000L) {
                        runLinuxCredentialCommand(
                            listOf("secret-tool", "store", "--label=Metrolist Desktop Secrets", "service", "test-timeout"),
                            "fake-timeout",
                        )
                    }
                assertEquals(1, result.exit)
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5L))
            }

            else -> {
                error("Unknown probe mode")
            }
        }
        println("PASS ${args[0]}")
    }

    private fun race(
        root: File,
        file: File,
        public: Properties,
    ) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var holdLookup = false
        val store =
            withStartupCredentialDeadline {
                LinuxSecretServiceStore(file, runCommand = { command, input ->
                    val result = runLinuxCredentialCommand(command, input)
                    if (holdLookup && command[1] == "lookup") {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    result
                })
            }
        assertTrue(store.isPending)
        val settings = DesktopFileSettings(File(root, "profile/settings.properties"), public, store)
        try {
            assertEquals(
                0,
                runCredentialCommand(
                    listOf("python3", File(root, "unlock-keyring.py").path),
                ).exit,
            )
            holdLookup = true
            val recovering = executor.submit<Boolean?> { settings.recoverSecrets() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            settings.putString("innerTubeCookie", "SAPISID=first-login")
            settings.remove("innerTubeCookie")
            settings.applyBatch(
                listOf(
                    PortableSetting("innerTubeCookie", SettingType.STRING, "SAPISID=last-login"),
                    PortableSetting("innerTubeAuthUser", SettingType.STRING, "2"),
                    PortableSetting("visitorData", SettingType.STRING, "last-visitor"),
                ),
                emptySet(),
            )
            release.countDown()
            assertEquals(true, recovering.get(5, TimeUnit.SECONDS))
            assertEquals("SAPISID=last-login", settings.getStringOrNull("innerTubeCookie"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
