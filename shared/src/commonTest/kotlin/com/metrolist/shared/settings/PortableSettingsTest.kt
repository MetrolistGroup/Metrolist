package com.metrolist.shared.settings

import com.metrolist.shared.constants.LastFmArtistCacheJsonKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class PortableSettingsTest {
    @Test
    fun negativeImageCacheLimitIsRejectedBeforeAnySettingsChange() {
        val settings =
            Settings(FakeSettings()).apply {
                maxImageCacheSize = 512
                innerTubeCookie = "existing"
            }
        assertFailsWith<IllegalArgumentException> {
            settings.importBackupSettings(PortableSettings(entries = listOf(PortableSetting("maxImageCacheSize", SettingType.INT, "-1"))))
        }
        assertEquals(512, settings.maxImageCacheSize)
        assertEquals("existing", settings.innerTubeCookie)
        settings.importBackupSettings(PortableSettings(entries = listOf(PortableSetting("maxImageCacheSize", SettingType.INT, "0"))))
        assertEquals(0, settings.maxImageCacheSize)
    }

    @Test
    fun removedPairingSettingsAreDeleted() {
        val backing =
            FakeSettings().apply {
                putString("listenTogetherPairedRoomCode", "ROOM")
                putBoolean("listenTogetherAutoJoinPairedRoom", true)
                putBoolean("deviceConnectRequireApproval", false)
            }

        Settings(backing)

        assertFalse(backing.hasKey("listenTogetherPairedRoomCode"))
        assertFalse(backing.hasKey("listenTogetherAutoJoinPairedRoom"))
        assertFalse(backing.hasKey("deviceConnectRequireApproval"))
    }

    @Test
    fun hiddenLibraryItemsRemainPortableUnderLegacySettingKey() {
        val settings = Settings(FakeSettings())
        settings.hiddenLibraryPlaylistsJson = "[{\"type\":\"album\",\"id\":\"album-id\",\"title\":\"Album\"}]"

        val entry = settings.exportBackupSettings().entries.single { it.key == "hiddenLibraryPlaylistsJson" }

        assertEquals(settings.hiddenLibraryPlaylistsJson, entry.value)
    }

    @Test
    fun backupSettingsIncludeAndRestoreCredentials() {
        val source =
            Settings(FakeSettings()).apply {
                innerTubeCookie = "youtube-cookie"
                visitorData = "visitor"
                dataSyncId = "sync"
                innerTubeAuthUser = "2"
                accountName = "Account"
                accountEmail = "account@example.com"
                accountChannelHandle = "@channel"
                accountThumbnail = "https://example.com/avatar"
                lastFmSession = "lastfm-session"
                listenBrainzToken = "listenbrainz-token"
                lyricsAiApiKey = "ai-key"
                lyricsOpenRouterOAuthSession = "pending-verifier"
                lyricsDeepLApiKey = "deepl-key"
                discordAccessToken = "discord-access"
                discordRefreshToken = "discord-refresh"
                listenTogetherDesktopSession = "listen-together-session"
            }
        val backup = source.exportBackupSettings()
        val exportedKeys = backup.entries.mapTo(mutableSetOf()) { it.key }
        assertFalse("lyricsOpenRouterOAuthSession" in exportedKeys)

        assertTrue(
            setOf(
                "innerTubeCookie",
                "visitorData",
                "dataSyncId",
                "innerTubeAuthUser",
                "accountName",
                "accountEmail",
                "accountChannelHandle",
                "accountThumbnail",
                "lastFmSession",
                "listenBrainzToken",
                "lyricsAiApiKey",
                "lyricsDeepLApiKey",
                "discordAccessToken",
                "discordRefreshToken",
                "listenTogetherDesktopSession",
            ).all(exportedKeys::contains),
        )

        val restoredBacking = FakeSettings()
        Settings(restoredBacking)
            .apply {
                innerTubeCookie = "stale-cookie"
                discordAccessToken = "stale-token"
            }.importBackupSettings(backup)
        val restored = Settings(restoredBacking)

        assertEquals("youtube-cookie", restored.innerTubeCookie)
        assertEquals("Account", restored.accountName)
        assertEquals("lastfm-session", restored.lastFmSession)
        assertEquals("listenbrainz-token", restored.listenBrainzToken)
        assertEquals("discord-access", restored.discordAccessToken)
        assertEquals("listen-together-session", restored.listenTogetherDesktopSession)
    }

    @Test
    fun backupRestoreClearsCredentialsMissingFromBackupAfterValidation() {
        val backing = FakeSettings()
        val settings =
            Settings(backing).apply {
                innerTubeCookie = "current-cookie"
                discordAccessToken = "current-token"
            }

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            settings.importBackupSettings(
                PortableSettings(entries = listOf(PortableSetting("dynamicTheme", SettingType.BOOLEAN, "invalid"))),
            )
        }
        assertEquals("current-cookie", Settings(backing).innerTubeCookie)
        assertEquals("current-token", Settings(backing).discordAccessToken)

        settings.importBackupSettings(PortableSettings(entries = emptyList()))
        assertEquals("", Settings(backing).innerTubeCookie)
        assertEquals("", Settings(backing).discordAccessToken)
    }

    @Test
    fun backupRollbackRestoresExactSnapshotAndPreservesInstallLocalSettings() {
        val backing = FakeSettings()
        val settings =
            Settings(backing).apply {
                dynamicTheme = false
                innerTubeCookie = "original-cookie"
                deviceConnectEnabled = true
            }
        val snapshot = settings.exportBackupSettings()

        settings.importBackupSettings(
            PortableSettings(
                entries =
                    listOf(
                        PortableSetting("contentCountry", SettingType.STRING, "DE"),
                        PortableSetting("dynamicTheme", SettingType.BOOLEAN, "true"),
                        PortableSetting("innerTubeCookie", SettingType.STRING, "replacement-cookie"),
                    ),
            ),
        )
        backing.removedKeys.clear()
        settings.restoreBackupSettingsSnapshot(snapshot)

        assertEquals(setOf("contentCountry"), backing.removedKeys)
        val restored = Settings(backing)
        assertFalse(backing.hasKey("contentCountry"))
        assertFalse(restored.dynamicTheme)
        assertEquals("original-cookie", restored.innerTubeCookie)
        assertTrue(restored.deviceConnectEnabled)
    }

    @Test
    fun stringBackedDesktopSettingsKeepDeclaredTypes() {
        val desktop =
            Settings(FakeSettings(stringBacked = true)).apply {
                dynamicTheme = false
                maxSongCacheSize = 2048
                volume = 0.5f
            }
        val backup = desktop.exportBackupSettings()
        val androidBacking = FakeSettings()
        Settings(androidBacking).importBackupSettings(backup)
        val restored = Settings(androidBacking)

        assertFalse(restored.dynamicTheme)
        assertEquals(2048, restored.maxSongCacheSize)
        assertEquals(0.5f, restored.volume)
    }

    @Test
    fun declaredTypesRoundTripForImplicitAndExplicitKeys() {
        for (stringBacked in listOf(false, true)) {
            val source = Settings(FakeSettings(stringBacked))
            source.appLanguage = "fr"
            source.appIcon = "MONOCHROME"
            source.dynamicTheme = false
            source.hideShortSongs = true
            source.maxSongCacheSize = 2048
            source.seekDurationSeconds = 20
            source.selectedThemeColor = 0xFF123456L
            source.lastLibraryRefreshEpoch = 1234567890123L
            source.volume = 0.5f
            source.playbackSpeed = 1.5f
            source.themeMode = ThemeMode.DARK
            source.musicVideoQuality = MusicVideoQuality.entries.last()

            val backup = source.exportBackupSettings()
            val expectedTypes =
                mapOf(
                    "appLanguage" to SettingType.STRING,
                    "appIcon" to SettingType.STRING,
                    "dynamicTheme" to SettingType.BOOLEAN,
                    "hideShortSongs" to SettingType.BOOLEAN,
                    "maxSongCacheSize" to SettingType.INT,
                    "seekDurationSeconds" to SettingType.INT,
                    "selectedThemeColor" to SettingType.LONG,
                    "lastLibraryRefreshEpoch" to SettingType.LONG,
                    "volume" to SettingType.FLOAT,
                    "playbackSpeed" to SettingType.FLOAT,
                    "themeMode" to SettingType.INT,
                    "musicVideoQuality" to SettingType.INT,
                )
            assertEquals(expectedTypes, backup.entries.associate { it.key to it.type })

            val destination = FakeSettings(!stringBacked)
            Settings(destination).importBackupSettings(backup)
            assertEquals(backup, Settings(destination).exportBackupSettings())
        }
    }

    @Test
    fun importPreservesDestinationOnlySettingsMissingFromBackup() {
        val backing = FakeSettings()
        val settings =
            Settings(backing).apply {
                dynamicTheme = false
            }

        settings.importBackupSettings(
            PortableSettings(entries = listOf(PortableSetting("contentCountry", SettingType.STRING, "DE"))),
        )

        val restored = Settings(backing)
        assertEquals("DE", restored.contentCountry)
        assertFalse(restored.dynamicTheme)
    }

    @Test
    fun customDownloadFolderStaysLocalDuringExportImportAndRollback() {
        val settings = Settings(FakeSettings()).apply { customDownloadPath = "/music/Metrolist" }
        val snapshot = settings.exportBackupSettings()
        assertFalse(snapshot.entries.any { it.key == "customDownloadPath" })

        settings.importBackupSettings(
            PortableSettings(
                entries =
                    listOf(
                        PortableSetting("customDownloadPath", SettingType.STRING, "content://provider/tree/primary%3AMusic"),
                        PortableSetting("dynamicTheme", SettingType.BOOLEAN, "false"),
                    ),
            ),
        )

        assertEquals("/music/Metrolist", settings.customDownloadPath)
        assertFalse(settings.dynamicTheme)
        settings.restoreBackupSettingsSnapshot(snapshot)
        assertEquals("/music/Metrolist", settings.customDownloadPath)
        settings.importBackupSettings(PortableSettings(entries = emptyList()))
        assertEquals("/music/Metrolist", settings.customDownloadPath)
    }

    @Test
    fun onboardingCompletionIsInstallLocal() {
        val settings =
            Settings(FakeSettings()).apply {
                desktopOnboardingComplete = true
                desktopOnboardingInitialized = true
                desktopPendingOnboardingSentryConsent = "false"
                iosOnboardingComplete = true
            }

        assertFalse(settings.exportBackupSettings().entries.any { it.key == "desktopOnboardingComplete" })
        assertFalse(settings.exportBackupSettings().entries.any { it.key == "desktopOnboardingInitialized" })
        assertFalse(settings.exportBackupSettings().entries.any { it.key == "desktopPendingOnboardingSentryConsent" })
        assertFalse(settings.exportBackupSettings().entries.any { it.key == "iosOnboardingComplete" })
        assertFalse(settings.exportBackupSettings().entries.any { it.key == "iosOnboardingComplete" })
    }

    @Test
    fun malformedPortableSettingsAreRejectedBeforeMutation() {
        val backing = FakeSettings()
        val settings = Settings(backing).apply { contentCountry = "US" }

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            settings.importBackupSettings(
                PortableSettings(
                    entries =
                        listOf(
                            PortableSetting("contentCountry", SettingType.STRING, "DE"),
                            PortableSetting("dynamicTheme", SettingType.BOOLEAN, "not-a-boolean"),
                        ),
                ),
            )
        }

        assertEquals("US", Settings(backing).contentCountry)
    }

    @Test
    fun retiredSettingsRemainValidButAreIgnoredByPortableAndFullRestore() {
        val preChangeBackup =
            PortableSettings(
                entries =
                    listOf(
                        PortableSetting("handleAudioFocus", SettingType.BOOLEAN, "true"),
                        PortableSetting("topListLength", SettingType.FLOAT, "80.0"),
                        PortableSetting("librarySortMode", SettingType.INT, "1"),
                        PortableSetting("autoPlayMusicVideos", SettingType.BOOLEAN, "true"),
                        PortableSetting("contentCountry", SettingType.STRING, "DE"),
                        PortableSetting("discordRpcAppId", SettingType.STRING, "123"),
                        PortableSetting("enableMusicVideos", SettingType.BOOLEAN, "false"),
                        PortableSetting("innerTubeCookie", SettingType.STRING, "old-cookie"),
                        PortableSetting("resumeOnBluetoothConnect", SettingType.BOOLEAN, "true"),
                    ),
            )

        val fullBacking = FakeSettings()
        Settings(fullBacking).importBackupSettings(preChangeBackup)
        val full = Settings(fullBacking)
        assertEquals("DE", full.contentCountry)
        assertEquals("old-cookie", full.innerTubeCookie)
        assertTrue(
            listOf(
                "handleAudioFocus",
                "topListLength",
                "librarySortMode",
                "enableMusicVideos",
                "autoPlayMusicVideos",
                "resumeOnBluetoothConnect",
                "discordRpcAppId",
            ).none(fullBacking::hasKey),
        )
    }

    @Test
    fun removedArtworkAndBrowseSettingsAllowOlderBackupsWithoutRestoringRetiredValues() {
        val retiredEntries =
            listOf(
                PortableSetting("useLoginForBrowse", SettingType.BOOLEAN, "false"),
                PortableSetting("enableAppleMusicArtwork", SettingType.BOOLEAN, "true"),
                PortableSetting("appleMusicArtworkCacheJson", SettingType.STRING, "{}"),
                PortableSetting("showArtistPhotos", SettingType.BOOLEAN, "true"),
                PortableSetting("lastFmPhotoAutoCycle", SettingType.BOOLEAN, "true"),
                PortableSetting("lastFmPhotoCycleInterval", SettingType.FLOAT, "8.0"),
                PortableSetting("artistLastFmPhotosJson", SettingType.STRING, "{}"),
                PortableSetting("showArtistSubscriberCount", SettingType.BOOLEAN, "false"),
                PortableSetting("showMonthlyListeners", SettingType.BOOLEAN, "false"),
                PortableSetting("lastFmShowOnHome", SettingType.BOOLEAN, "false"),
                PortableSetting("lastFmArtistAvatarsJson", SettingType.STRING, "{}"),
            )
        for (retired in retiredEntries) {
            val backing = FakeSettings()
            val settings = Settings(backing)
            settings.importBackupSettings(
                PortableSettings(entries = listOf(retired, PortableSetting("contentCountry", SettingType.STRING, "DE"))),
            )
            assertEquals("DE", settings.contentCountry)
            assertFalse(backing.hasKey(retired.key))
            assertFalse(settings.exportBackupSettings().entries.any { it.key == retired.key })
            val invalidType = if (retired.type == SettingType.STRING) SettingType.BOOLEAN else SettingType.STRING
            assertFailsWith<IllegalArgumentException> {
                settings.validatePortableSettings(PortableSettings(entries = listOf(retired.copy(type = invalidType))))
            }
        }
        val settings = Settings(FakeSettings())
        assertFailsWith<IllegalArgumentException> {
            settings.validatePortableSettings(
                PortableSettings(entries = listOf(PortableSetting("lastFmPhotoAutoCycle", SettingType.BOOLEAN, "yes"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            settings.validatePortableSettings(
                PortableSettings(entries = listOf(PortableSetting("lastFmPhotoCycleInterval", SettingType.FLOAT, "NaN"))),
            )
        }
    }

    @Test
    fun legacyKeepScreenOnRestoresModeAndPreservesExplicitMode() {
        for (enabled in listOf(false, true)) {
            val settings = Settings(FakeSettings()).apply { keepScreenOnMode = KeepScreenOnMode.LYRICS }
            val legacy = PortableSetting("keepScreenOn", SettingType.BOOLEAN, enabled.toString())
            val expected = if (enabled) KeepScreenOnMode.PLAYER_AND_LYRICS else KeepScreenOnMode.NEVER

            settings.importBackupSettings(PortableSettings(entries = listOf(legacy)))
            assertEquals(expected, settings.keepScreenOnMode)
            assertFalse(settings.exportBackupSettings().entries.any { it.key == "keepScreenOn" })

            settings.importBackupSettings(
                PortableSettings(
                    entries =
                        listOf(
                            legacy,
                            PortableSetting("keepScreenOnMode", SettingType.INT, KeepScreenOnMode.LYRICS.ordinal.toString()),
                        ),
                ),
            )
            assertEquals(KeepScreenOnMode.LYRICS, settings.keepScreenOnMode)

            settings.importBackupSettings(settings.portableSettingsFromLegacy(mapOf("keepScreenOn" to enabled)))
            assertEquals(expected, settings.keepScreenOnMode)
            for (mode in listOf(KeepScreenOnMode.LYRICS.ordinal, KeepScreenOnMode.LYRICS.name)) {
                settings.importBackupSettings(
                    settings.portableSettingsFromLegacy(
                        mapOf(
                            "keepScreenOn" to enabled,
                            "keepScreenOnMode" to mode,
                        ),
                    ),
                )
                assertEquals(KeepScreenOnMode.LYRICS, settings.keepScreenOnMode)
            }
        }
        val settings = Settings(FakeSettings())
        assertFailsWith<IllegalArgumentException> {
            settings.importBackupSettings(PortableSettings(entries = listOf(PortableSetting("keepScreenOn", SettingType.STRING, "true"))))
        }
        assertFailsWith<IllegalArgumentException> {
            settings.importBackupSettings(PortableSettings(entries = listOf(PortableSetting("keepScreenOn", SettingType.BOOLEAN, "yes"))))
        }
    }

    @Test
    fun legacyPureBlackBackupRestoresThemeMode() {
        val backup =
            PortableSettings(
                entries =
                    listOf(
                        PortableSetting("themeMode", SettingType.INT, ThemeMode.AUTO.ordinal.toString()),
                        PortableSetting("pureBlack", SettingType.BOOLEAN, "true"),
                    ),
            )
        val backing = FakeSettings()

        Settings(backing).importBackupSettings(backup)

        assertEquals(ThemeMode.PURE_BLACK, Settings(backing).themeMode)
        assertFalse(backing.hasKey("pureBlack"))
    }

    @Test
    fun retiredSettingTypesAndValuesRemainStrict() {
        val settings = Settings(FakeSettings())

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            settings.validatePortableSettings(
                PortableSettings(entries = listOf(PortableSetting("enableMusicVideos", SettingType.STRING, "true"))),
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            settings.validatePortableSettings(
                PortableSettings(entries = listOf(PortableSetting("autoPlayMusicVideos", SettingType.BOOLEAN, "yes"))),
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            settings.validatePortableSettings(
                PortableSettings(entries = listOf(PortableSetting("unknownSetting", SettingType.BOOLEAN, "true"))),
            )
        }
    }

    @Test
    fun legacySettingsMapAliasesAndKeepSecrets() {
        val settings = Settings(FakeSettings())
        val portable =
            settings.portableSettingsFromLegacy(
                mapOf(
                    "crossfadeDurationFloat" to 3.5f,
                    "innerTubeCookie" to "secret-cookie",
                    "listenTogetherUsername" to "private-user",
                ),
            )

        assertFalse(portable.entries.any { it.key == "newPlayerDesign" })
        assertTrue(portable.entries.any { it.key == "crossfadeDuration" && it.value == "3.5" })
        assertTrue(portable.entries.any { it.key == "innerTubeCookie" && it.value == "secret-cookie" })
        assertTrue(portable.entries.any { it.key == "listenTogetherUsername" && it.value == "private-user" })
    }

    @Test
    fun lastFmCachesStayOutOfBackups() {
        val backing = FakeSettings()
        val settings = Settings(backing)
        settings.lastFmArtistCacheJson = """{"artist":{}}"""
        settings.lastFmPhotoCacheJson = """{"photo":{}}"""

        val keys = settings.exportBackupSettings().entries.map { it.key }

        assertFalse(LastFmArtistCacheJsonKey in keys)
        assertFalse("lastFmPhotoCacheJson" in keys)
        assertEquals(settings.lastFmPhotoCacheJson, Settings(backing).lastFmPhotoCacheJson)
    }
}

private class FakeSettings(
    private val stringBacked: Boolean = false,
) : KmpSettings {
    private val values = mutableMapOf<String, Any>()
    val removedKeys = mutableSetOf<String>()
    override val keys get() = values.keys
    override val size get() = values.size

    override fun clear() = values.clear()

    override fun remove(key: String) {
        removedKeys += key
        values.remove(key)
    }

    override fun hasKey(key: String) = key in values

    override fun putInt(
        key: String,
        value: Int,
    ) = put(key, value)

    override fun getInt(
        key: String,
        defaultValue: Int,
    ) = getIntOrNull(key) ?: defaultValue

    override fun getIntOrNull(key: String) = (values[key] as? Int) ?: values[key]?.toString()?.toIntOrNull()

    override fun putLong(
        key: String,
        value: Long,
    ) = put(key, value)

    override fun getLong(
        key: String,
        defaultValue: Long,
    ) = getLongOrNull(key) ?: defaultValue

    override fun getLongOrNull(key: String) = (values[key] as? Long) ?: values[key]?.toString()?.toLongOrNull()

    override fun putString(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override fun getString(
        key: String,
        defaultValue: String,
    ) = values[key] as? String ?: defaultValue

    override fun getStringOrNull(key: String) = values[key] as? String

    override fun putFloat(
        key: String,
        value: Float,
    ) = put(key, value)

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ) = getFloatOrNull(key) ?: defaultValue

    override fun getFloatOrNull(key: String) = (values[key] as? Float) ?: values[key]?.toString()?.toFloatOrNull()

    override fun putDouble(
        key: String,
        value: Double,
    ) = put(key, value)

    override fun getDouble(
        key: String,
        defaultValue: Double,
    ) = getDoubleOrNull(key) ?: defaultValue

    override fun getDoubleOrNull(key: String) = (values[key] as? Double) ?: values[key]?.toString()?.toDoubleOrNull()

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) = put(key, value)

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ) = getBooleanOrNull(key) ?: defaultValue

    override fun getBooleanOrNull(key: String) = (values[key] as? Boolean) ?: values[key]?.toString()?.toBooleanStrictOrNull()

    private fun put(
        key: String,
        value: Any,
    ) {
        values[key] = if (stringBacked) value.toString() else value
    }
}
