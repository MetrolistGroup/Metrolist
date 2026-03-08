package com.metrolist.shared.settings

import com.metrolist.shared.constants.DownloadedCollectionSortDirectionKey
import com.metrolist.shared.constants.DownloadedCollectionSortKey
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.SortDirection
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class LegacySettingsMigrationTest {
    @Test
    fun storedLyricsProvidersDropRetiredSourcesAndKeepTheRemainingOrder() {
        val backing =
            MapSettings(
                "lyricsProviderOrder" to "ZEMER,KUGOU,LyricsPlus,LRCLIB,BETTERLYRICS,LYPRIO,Zemer,UNISON,PAXSENIX,LRCRED",
                "lyricsEnabledProviders" to "LyricsPlus,LRCLIB,ZEMER,KUGOU",
                "enableLyricsPlus" to true,
            )
        val settings = Settings(backing)
        val expected =
            listOf(
                LyricsProvider.KUGOU,
                LyricsProvider.LRCLIB,
                LyricsProvider.BETTERLYRICS,
                LyricsProvider.LYPRIO,
                LyricsProvider.UNISON,
                LyricsProvider.PAXSENIX,
                LyricsProvider.LRCRED,
            )
        val order = LyricsProvider.parseUserOrderFromStoredCsv(settings.lyricsProviderOrder)
        assertEquals(expected, order)
        assertEquals(
            setOf(LyricsProvider.LRCLIB, LyricsProvider.KUGOU),
            LyricsProvider.parseEnabledFromStoredCsv(settings.lyricsEnabledProviders),
        )
        assertFalse(backing.hasKey("enableLyricsPlus"))
        settings.lyricsProviderOrder = order.joinToString(",") { it.name }
        assertEquals(expected, LyricsProvider.parseUserOrderFromStoredCsv(Settings(backing).lyricsProviderOrder))
        assertEquals(emptySet(), LyricsProvider.parseEnabledFromStoredCsv("LYRICSPLUS,Zemer"))
        for (csv in listOf(
            "BETTERLYRICS,LRCLIB,KUGOU",
            "BETTERLYRICS,UNISON,LRCLIB,KUGOU,PAXSENIX",
            "LYPRIO,BETTERLYRICS,UNISON,LRCLIB,KUGOU,PAXSENIX",
        )) {
            val remaining = csv.split(",").map { LyricsProvider.valueOf(it) }
            assertEquals(remaining.toSet(), LyricsProvider.parseEnabledFromStoredCsv(csv))
            assertEquals(remaining, LyricsProvider.parseUserOrderFromStoredCsv(csv).take(remaining.size))
        }
        assertEquals(
            setOf(LyricsProvider.BETTERLYRICS, LyricsProvider.UNISON, LyricsProvider.LRCLIB, LyricsProvider.KUGOU, LyricsProvider.PAXSENIX),
            LyricsProvider.parseEnabledFromStoredCsv("BETTERLYRICS,UNISON,LRCLIB,KUGOU,PAXSENIX,LYRICSPLUS"),
        )
        assertEquals(LyricsProvider.userConfigurable, LyricsProvider.parseUserOrderFromStoredCsv("LyricsPlus,ZEMER"))
    }

    @Test
    fun legacyLibraryFilterNamesKeepTheirStoredIds() {
        val filters = LibraryFilterType.entries.associateBy { it.name } + ("OWNED" to LibraryFilterType.PLAYLISTS)
        for ((name, expected) in filters) {
            val backing = MapSettings("libraryFilter" to name)
            val settings = Settings(StrictTypedSettings(backing))
            assertEquals(expected, settings.libraryFilter)
            assertEquals(expected.storedValue, backing.getInt("libraryFilter", -1))
            for (legacy in listOf(name, expected.storedValue)) {
                settings.libraryFilter = LibraryFilterType.TRACKS
                settings.importBackupSettings(settings.portableSettingsFromLegacy(mapOf("libraryFilter" to legacy)))
                assertEquals(expected, settings.libraryFilter)
            }
        }
        val settings = Settings(MapSettings())
        settings.importBackupSettings(settings.portableSettingsFromLegacy(mapOf("libraryFilter" to 4)))
        assertEquals(LibraryFilterType.PLAYLISTS, settings.libraryFilter)
    }

    @Test
    fun downloadedCollectionSortPreferencesPersist() {
        val backing = MapSettings()
        val settings = Settings(backing)

        settings.downloadedCollectionSort = CollectionSort.ARTIST
        settings.downloadedCollectionSortDirection = SortDirection.ASCENDING

        val restored = Settings(backing)
        assertEquals(CollectionSort.ARTIST, restored.downloadedCollectionSort)
        assertEquals(SortDirection.ASCENDING, restored.downloadedCollectionSortDirection)
        assertEquals(CollectionSort.ARTIST.ordinal, backing.getInt(DownloadedCollectionSortKey, -1))
        assertEquals(SortDirection.ASCENDING.ordinal, backing.getInt(DownloadedCollectionSortDirectionKey, -1))
    }

    @Test
    fun migrationsPreserveSavedTranslationAndDetectorModels() {
        for (version in listOf(0, 11, 12, 14)) {
            val backing = MapSettings()
            backing.putString("lyricsAiProvider", "OpenRouter")
            backing.putString("lyricsAiModel", "~google/gemini-flash-latest")
            backing.putString("lyricsAiDetectorModel", "google/gemini-flash-latest")
            backing.putInt("settingsMigrationVersion", version)
            migrateLegacySettings(backing)
            assertEquals("OpenRouter", backing.getString("lyricsAiProvider", ""))
            assertEquals("~google/gemini-flash-latest", backing.getString("lyricsAiModel", ""))
            assertEquals("google/gemini-flash-latest", backing.getString("lyricsAiDetectorModel", ""))
            assertEquals(14, backing.getInt("settingsMigrationVersion", 0))
        }
    }

    @Test
    fun migratesAppearanceKnobsFromLegacyAndPreviouslyMigratedStores() {
        for (version in listOf(0, 8, 9)) {
            val backing =
                MapSettings(
                    "settingsMigrationVersion" to version,
                    "density_scale_factor" to 0.85f,
                    "enableLandscapeScaling" to true,
                    "forceBottomNavBar" to true,
                )
            val settings = Settings(StrictTypedSettings(backing))
            assertEquals(0.85f, settings.densityScaleFactor)
            assertTrue(settings.enableLandscapeScaling)
            assertTrue(settings.forceBottomNavBar)
            migrateLegacySettings(backing)
            assertEquals(0.85f, backing.getFloat("density_scale_factor", 1f))
            assertEquals(14, backing.getInt("settingsMigrationVersion", 0))
        }
    }

    @Test
    fun importsAppearanceKnobsFromLegacyBackup() {
        val settings = Settings(MapSettings())
        settings.importBackupSettings(
            settings.portableSettingsFromLegacy(
                mapOf(
                    "density_scale_factor" to 1.25f,
                    "enableLandscapeScaling" to true,
                    "forceBottomNavBar" to true,
                ),
            ),
        )
        assertEquals(1.25f, settings.densityScaleFactor)
        assertTrue(settings.enableLandscapeScaling)
        assertTrue(settings.forceBottomNavBar)
    }

    @Test
    fun invalidLegacyDensityDoesNotReachTheLayout() {
        for (value in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val settings = Settings(MapSettings("density_scale_factor" to value))
            assertEquals(1f, settings.densityScaleFactor)
            assertTrue(settings.portableSettingsFromLegacy(mapOf("density_scale_factor" to value)).entries.isEmpty())
        }
    }

    @Test
    fun migratesLoudnessLevelNamesFromLegacyAndPreviouslyMigratedSettings() {
        for (version in listOf(0, 8, 9)) {
            for (level in LoudnessLevel.entries) {
                val backing = MapSettings("loudnessLevel" to level.name, "settingsMigrationVersion" to version)
                val settings = Settings(StrictTypedSettings(backing))
                assertEquals(level, settings.loudnessLevel)
                assertEquals(level.ordinal, backing.getInt("loudnessLevel", -1))
                assertEquals(level, Settings(StrictTypedSettings(backing)).loudnessLevel)
            }
        }
        assertEquals(LoudnessLevel.BALANCED, Settings(MapSettings()).loudnessLevel)
        assertEquals(LoudnessLevel.BALANCED, Settings(MapSettings("loudnessLevel" to "invalid")).loudnessLevel)
        assertEquals(LoudnessLevel.QUIET, Settings(MapSettings("loudnessLevel" to LoudnessLevel.QUIET.ordinal)).loudnessLevel)
    }

    @Test
    fun acceptsCurrentOrdinalEnumsWithoutMigrationMarker() {
        val backing =
            MapSettings(
                "libraryContentSort" to 3,
                "playerThumbnailStyle" to PlayerThumbnailStyle.COMPACT.ordinal,
                "playerBackgroundStyle" to PlayerBackgroundStyle.SOLID.ordinal,
            )

        val settings = Settings(StrictTypedSettings(backing))

        assertEquals(LibraryContentSort.MANUAL, settings.libraryContentSort)
        assertEquals(PlayerThumbnailStyle.COMPACT, settings.playerThumbnailStyle)
        assertEquals(PlayerBackgroundStyle.SOLID, settings.playerBackgroundStyle)
        assertFalse(settings.enableAnimatedCovers)
        assertEquals(14, backing.getInt("settingsMigrationVersion", 0))
    }

    @Test
    fun migratesCanvasThumbnailStyleToToggle() {
        val backing =
            MapSettings(
                "settingsMigrationVersion" to 5,
                "playerThumbnailStyle" to PlayerThumbnailStyle.CANVAS.ordinal,
                "enableAnimatedCovers" to true,
            )
        val settings = Settings(backing)

        assertTrue(settings.enableAnimatedCovers)
        assertTrue(settings.playerCanvasEnabled)
        assertEquals(PlayerThumbnailStyle.COMPACT, settings.playerThumbnailStyle)
        assertEquals(14, backing.getInt("settingsMigrationVersion", 0))
    }

    @Test
    fun canvasAndAnimatedCoversToggleIndependently() {
        val settings = Settings(MapSettings())
        settings.playerCanvasEnabled = true
        assertFalse(settings.enableAnimatedCovers)
        settings.enableAnimatedCovers = true
        settings.playerCanvasEnabled = false
        assertTrue(settings.enableAnimatedCovers)
        assertFalse(settings.playerCanvasEnabled)
    }

    @Test
    fun migrationKeepsBothArtworkTogglesAsStored() {
        for (canvas in listOf(true, false)) {
            for (covers in listOf(true, false)) {
                val backing =
                    MapSettings(
                        "settingsMigrationVersion" to 10,
                        "playerCanvasEnabled" to canvas,
                        "enableAnimatedCovers" to covers,
                    )
                val settings = Settings(backing)
                assertEquals(canvas, settings.playerCanvasEnabled)
                assertEquals(covers, settings.enableAnimatedCovers)
            }
        }
    }

    @Test
    fun mergedToggleTurnsCanvasBackOnOnlyForUsersWhoHadAnimatedCovers() {
        for (version in 11..13) {
            assertTrue(Settings(MapSettings("settingsMigrationVersion" to version, "enableAnimatedCovers" to true)).playerCanvasEnabled)
            assertFalse(Settings(MapSettings("settingsMigrationVersion" to version, "enableAnimatedCovers" to false)).playerCanvasEnabled)
        }
        assertFalse(Settings(MapSettings("settingsMigrationVersion" to 14, "enableAnimatedCovers" to true)).playerCanvasEnabled)
        assertFalse(
            Settings(
                MapSettings("settingsMigrationVersion" to 12, "playerCanvasEnabled" to false, "enableAnimatedCovers" to true),
            ).playerCanvasEnabled,
        )
    }

    @Test
    fun backupsKeepBothArtworkTogglesAsStored() {
        for (canvas in listOf(true, false)) {
            for (covers in listOf(true, false)) {
                val settings = Settings(MapSettings())
                val values = mapOf("playerCanvasEnabled" to canvas, "enableAnimatedCovers" to covers)
                settings.importBackupSettings(settings.portableSettingsFromLegacy(values))
                assertEquals(canvas, settings.playerCanvasEnabled)
                assertEquals(covers, settings.enableAnimatedCovers)
                assertTrue(settings.exportBackupSettings().entries.any { it.key == "playerCanvasEnabled" })
            }
        }
    }

    @Test
    fun convertsEnumsAndLegacyNumericTypesBeforeDelegatesRead() {
        val backing = MapSettings("themeMode" to "DARK", "selectedThemeColor" to 42, "topListLength" to "75")

        val settings = Settings(backing)

        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(42L, settings.selectedThemeColor)
        assertFalse(backing.hasKey("topListLength"))
    }

    @Test
    fun migratesPureBlackToggleToThemeMode() {
        val backing =
            MapSettings(
                "settingsMigrationVersion" to 4,
                "themeMode" to ThemeMode.AUTO.ordinal,
                "pureBlack" to true,
            )

        val settings = Settings(backing)

        assertEquals(ThemeMode.PURE_BLACK, settings.themeMode)
        assertFalse(backing.hasKey("pureBlack"))
        assertEquals(14, backing.getInt("settingsMigrationVersion", 0))
    }

    @Test
    fun migratesPureBlackClassicMiniPlayerToSolidBlackBackground() {
        val classic = MapSettings("useNewMiniPlayerDesign" to false, "pureBlackMiniPlayer" to true)
        val modern = MapSettings("useNewMiniPlayerDesign" to true, "pureBlackMiniPlayer" to true)

        assertEquals("SOLID_BLACK", Settings(classic).miniPlayerBackgroundStyle)
        assertEquals("DEFAULT", Settings(modern).miniPlayerBackgroundStyle)
        assertFalse(classic.hasKey("pureBlackMiniPlayer"))
        assertFalse(modern.hasKey("pureBlackMiniPlayer"))

        val portable =
            Settings(MapSettings()).portableSettingsFromLegacy(
                mapOf("useNewMiniPlayerDesign" to false, "pureBlackMiniPlayer" to true),
            )
        assertEquals("SOLID_BLACK", portable.entries.single { it.key == "miniPlayerBackgroundStyle" }.value)
        assertFalse(portable.entries.any { it.key == "pureBlackMiniPlayer" })
    }

    @Test
    fun migratesExactLegacyPreferenceKeyFamilies() {
        val backing =
            MapSettings(
                "darkMode" to "ON",
                "appLanguage" to "SYSTEM_DEFAULT",
                "SwipeToSong" to true,
                "SwipeToRemoveSong" to false,
                "show_liked_playlist" to false,
                "show_downloaded_playlist" to true,
                "show_top_playlist" to false,
                "show_cached_playlist" to true,
                "show_uploaded_playlist" to false,
                "discordRPCEnable" to true,
                "discordButton1Label" to "Open",
                "discordButton2Label" to "Visit",
                "listenTogetherAutoApproval" to true,
                "listenTogetherAutoApproveSuggestions" to false,
                "listenTogetherSyncVolume" to false,
                "lastUpdateCheckTime" to 123L,
                "aiProvider" to "OpenAI",
                "openRouterBaseUrl" to "https://example.test",
                "openRouterModel" to "model-x",
                "translateMode" to "Fluent",
                "translateLanguage" to "fr",
                "deeplFormality" to "more",
                "aiSystemPrompt" to "prompt",
                "lyricsRomanizeList" to "Japanese:true,Russian:false",
                "topSize" to "80",
                "swipeSensitivity" to 0.73f,
                "playerBackgroundStyle" to "DEFAULT",
                "miniPlayerBackgroundStyle" to "PURE_BLACK",
                "squigglySlider" to false,
                "useNewPlayerDesign" to false,
                "useNewMiniPlayerDesign" to true,
                "hideYoutubeShorts" to true,
                "playerVolume" to 0.42f,
                "enableBetterLyrics" to false,
                "enableLrclib" to true,
                "enableKugou" to false,
                "enablePaxsenix" to true,
                "enableLyricsPlus" to false,
            )

        val settings = Settings(backing)

        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals("SYSTEM", settings.appLanguage)
        assertTrue(settings.swipeToSong)
        assertFalse(settings.swipeToRemoveSong)
        assertFalse(settings.showLikedPlaylist)
        assertTrue(settings.showDownloadedPlaylist)
        assertFalse(settings.showTopPlaylist)
        assertTrue(settings.showCachedPlaylist)
        assertFalse(settings.showUploadedPlaylist)
        assertTrue(settings.enableDiscordIntegration)
        assertEquals("Open", settings.discordButton1Text)
        assertEquals("Visit", settings.discordButton2Text)
        assertTrue(settings.listenTogetherAutoApproveJoinRequests)
        assertFalse(settings.listenTogetherAutoApproveSongSuggestions)
        assertFalse(settings.listenTogetherSyncHostVolume)
        assertEquals(123L, settings.lastUpdateCheckTimestamp)
        assertEquals("OpenAI", settings.lyricsAiProvider)
        assertEquals("https://example.test", settings.lyricsAiBaseUrl)
        assertEquals("model-x", settings.lyricsAiModel)
        assertEquals("Fluent", settings.lyricsAiTranslationMode)
        assertEquals("fr", settings.lyricsAiTargetLanguage)
        assertEquals("more", settings.lyricsDeepLFormality)
        assertEquals("prompt", settings.lyricsAiSystemPrompt)
        assertEquals("Japanese:true,Russian:false", settings.lyricsRomanizeLanguages)
        assertFalse(settings.exportBackupSettings().entries.any { it.key == "topListLength" })
        assertEquals(73f, settings.swipeSensitivity)
        assertEquals(PlayerBackgroundStyle.SOLID, settings.playerBackgroundStyle)
        assertEquals("SOLID_BLACK", settings.miniPlayerBackgroundStyle)
        assertEquals(SliderStyle.DEFAULT, settings.sliderStyle)
        assertEquals(PlayerType.VINTAGE, settings.playerType)
        assertEquals(MiniPlayerType.MODERN, settings.miniPlayerType)
        assertTrue(settings.hideShortSongs)
        assertEquals(0.42f, settings.volume)
        assertEquals("LRCLIB,PAXSENIX,UNISON", settings.lyricsEnabledProviders)
    }

    @Test
    fun preservesCurrentSensitivityAndDoesNotRetireFailedConversion() {
        val backing = MapSettings("swipeSensitivity" to 73f, "crossfadeDurationFloat" to "not-a-number")
        val settings = Settings(backing)

        assertEquals(73f, settings.swipeSensitivity)
        assertTrue(backing.hasKey("crossfadeDurationFloat"))
    }

    @Test
    fun currentDestinationWinsAndMalformedValuesDoNotAbortMigration() {
        val backing =
            MapSettings(
                "lastfmUsername" to "legacy",
                "lastFmUsername" to "current",
                "themeMode" to "not-an-enum",
                "lyricsAiApiKey" to "secret",
                "crossfadeDurationFloat" to "bad",
            )

        val settings = Settings(backing)

        assertEquals("current", settings.lastFmUsername)
        assertEquals("secret", settings.lyricsAiApiKey)
        assertEquals(ThemeMode.AUTO, settings.themeMode)
        assertEquals(10f, settings.crossfadeDuration)
    }

    @Test
    fun combinesAndRetiresSimilarContentIdempotently() {
        val backing = MapSettings("autoLoadMore" to true, "similarContent" to false)
        val settings = Settings(backing)

        assertFalse(settings.autoLoadMore)
        assertFalse(backing.hasKey("similarContent"))
        assertTrue(backing.hasKey("settingsMigrationVersion"))

        backing.putBoolean("autoLoadMore", true)
        Settings(backing)
        assertTrue(backing.getBoolean("autoLoadMore", false))
    }

    @Test
    fun retiredSettingsAreRemovedFromLegacyStoreAndBackupConversion() {
        val backing =
            MapSettings(
                "enableMusicVideos" to true,
                "autoPlayMusicVideos" to false,
                "resumeOnBluetoothConnect" to true,
            )

        Settings(backing)

        assertTrue(
            listOf("enableMusicVideos", "autoPlayMusicVideos", "resumeOnBluetoothConnect")
                .none(backing::hasKey),
        )

        val portable =
            Settings(MapSettings()).portableSettingsFromLegacy(
                mapOf(
                    "contentCountry" to "DE",
                    "enableMusicVideos" to true,
                    "autoPlayMusicVideos" to false,
                    "resumeOnBluetoothConnect" to true,
                ),
            )
        assertEquals("DE", portable.entries.single { it.key == "contentCountry" }.value)
        assertTrue(
            listOf("enableMusicVideos", "autoPlayMusicVideos", "resumeOnBluetoothConnect")
                .none { key -> portable.entries.any { it.key == key } },
        )
    }

    @Test
    fun versionSixMigrationRemovesUnusedSettings() {
        val backing =
            MapSettings(
                "settingsMigrationVersion" to 6,
                "desktopSidebarExpanded" to false,
                "discordRpcAppId" to "123",
                "lastLibraryItemsJson" to "[]",
                "enableDiscordIntegration" to true,
            )

        migrateLegacySettings(backing)

        assertEquals(14, backing.getInt("settingsMigrationVersion", 0))
        assertTrue(listOf("desktopSidebarExpanded", "discordRpcAppId", "lastLibraryItemsJson").none(backing::hasKey))
        assertTrue(backing.getBoolean("enableDiscordIntegration", false))
    }

    @Test
    fun versionTwoMigrationRetiresArtworkSettingsAndPreservesCanvasPreferences() {
        val backing =
            MapSettings(
                "settingsMigrationVersion" to 2,
                "enableMusicVideos" to true,
                "autoPlayMusicVideos" to false,
                "resumeOnBluetoothConnect" to true,
                "enableAppleMusicArtwork" to true,
                "canvasContentOrder" to "APPLE_MUSIC,YOUTUBE_MUSIC_VIDEO",
                "youtubeCanvasMode" to "FULL_VIDEO",
                "youtubeCanvasQuality" to "P720",
                "appleMusicArtistVideoResolution" to "HIGH_1080",
                "appleMusicArtistCacheJson" to "{}",
                "swipeSensitivity" to 0.5f,
                "autoLoadMore" to true,
                "lastFmSession" to "session-key",
                "listenTogetherPairedRoomCode" to "pairing-code",
                "unrelated" to "preserved",
            )

        migrateLegacySettings(backing)

        assertEquals(14, backing.getInt("settingsMigrationVersion", 0))
        assertTrue(
            listOf(
                "enableMusicVideos",
                "autoPlayMusicVideos",
                "resumeOnBluetoothConnect",
                "enableAppleMusicArtwork",
            ).none(backing::hasKey),
        )
        assertEquals("APPLE_MUSIC,YOUTUBE_MUSIC_VIDEO", backing.getString("canvasContentOrder", ""))
        assertEquals(YouTubeCanvasMode.FULL_VIDEO.ordinal, backing.getInt("youtubeCanvasMode", -1))
        assertEquals(MusicVideoQuality.P720.ordinal, backing.getInt("youtubeCanvasQuality", -1))
        assertEquals(AnimatedCoverResolution.HIGH_1080.ordinal, backing.getInt("appleMusicArtistVideoResolution", -1))
        assertEquals("{}", backing.getString("appleMusicArtistCacheJson", ""))
        assertEquals(0.5f, backing.getFloat("swipeSensitivity", 0f))
        assertTrue(backing.getBoolean("autoLoadMore", false))
        assertEquals("session-key", backing.getString("lastFmSession", ""))
        assertEquals("pairing-code", backing.getString("listenTogetherPairedRoomCode", ""))
        assertEquals("preserved", backing.getString("unrelated", ""))
    }

    @Test
    fun portableLegacyConversionUsesCanonicalDestinationAndSkipsRemovedSettings() {
        val portable =
            Settings(MapSettings()).portableSettingsFromLegacy(
                mapOf(
                    "contentCountry" to "DE",
                    "lastFmUsername" to "canonical",
                    "lastfmUsername" to "alias",
                    "selectedThemeColor" to 7,
                    "pureBlack" to true,
                    "similarContent" to false,
                    "autoLoadMore" to true,
                    "topListLength" to "80",
                    "enableBetterLyrics" to false,
                    "enableLrclib" to true,
                    "squigglySlider" to true,
                    "useNewPlayerDesign" to false,
                    "useNewMiniPlayerDesign" to false,
                ),
            )

        assertEquals("canonical", portable.entries.single { it.key == "lastFmUsername" }.value)
        assertEquals("7", portable.entries.single { it.key == "selectedThemeColor" }.value)
        assertEquals(ThemeMode.PURE_BLACK.ordinal.toString(), portable.entries.single { it.key == "themeMode" }.value)
        assertFalse(portable.entries.any { it.key == "topListLength" })
        assertEquals("false", portable.entries.single { it.key == "autoLoadMore" }.value)
        assertEquals("LRCLIB,KUGOU,PAXSENIX,UNISON", portable.entries.single { it.key == "lyricsEnabledProviders" }.value)
        assertEquals(SliderStyle.SQUIGGLY.ordinal.toString(), portable.entries.single { it.key == "sliderStyle" }.value)
        assertEquals(PlayerType.VINTAGE.ordinal.toString(), portable.entries.single { it.key == "playerType" }.value)
        assertEquals(MiniPlayerType.VINTAGE.ordinal.toString(), portable.entries.single { it.key == "miniPlayerType" }.value)
        assertFalse(portable.entries.any { it.key == "similarContent" })
    }

    @Test
    fun explicitlyEmptyCurrentProviderListWinsOverLegacyFlags() {
        val backing =
            MapSettings(
                "lyricsEnabledProviders" to "",
                "enableBetterLyrics" to true,
                "enableLrclib" to true,
            )

        val settings = Settings(backing)

        assertEquals("", settings.lyricsEnabledProviders)
        assertFalse(backing.hasKey("enableBetterLyrics"))
        assertFalse(backing.hasKey("enableLrclib"))
    }

    @Test
    fun portableConversionPreservesExplicitlyEmptyProviderList() {
        val portable =
            Settings(MapSettings()).portableSettingsFromLegacy(
                mapOf(
                    "lyricsEnabledProviders" to "",
                    "enableBetterLyrics" to true,
                ),
            )

        assertEquals("", portable.entries.single { it.key == "lyricsEnabledProviders" }.value)
    }

    @Test
    fun retiredProxyValuesAreRemovedInsteadOfMigrated() {
        val backing =
            MapSettings(
                "proxyEnabled" to true,
                "proxyUrl" to "localhost:8080",
                "proxyUsername" to "user",
                "proxyPassword" to "secret",
            )

        Settings(backing)

        assertFalse(backing.hasKey("proxyEnabled"))
        assertFalse(backing.hasKey("proxyUrl"))
        assertFalse(backing.hasKey("proxyUsername"))
        assertFalse(backing.hasKey("proxyPassword"))
    }
}

private class StrictTypedSettings(
    private val delegate: KmpSettings,
) : KmpSettings by delegate {
    override fun getStringOrNull(key: String): String? = requireType(key) { delegate.getStringOrNull(key) }

    override fun getBooleanOrNull(key: String): Boolean? = requireType(key) { delegate.getBooleanOrNull(key) }

    override fun getIntOrNull(key: String): Int? = requireType(key) { delegate.getIntOrNull(key) }

    override fun getLongOrNull(key: String): Long? = requireType(key) { delegate.getLongOrNull(key) }

    override fun getFloatOrNull(key: String): Float? = requireType(key) { delegate.getFloatOrNull(key) }

    private fun <T> requireType(
        key: String,
        read: () -> T?,
    ): T? = read() ?: if (delegate.hasKey(key)) error("Preference $key has a different type") else null
}
