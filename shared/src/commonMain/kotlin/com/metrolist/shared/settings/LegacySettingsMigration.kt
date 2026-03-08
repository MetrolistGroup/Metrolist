package com.metrolist.shared.settings

import com.metrolist.shared.constants.AnimatedArtworkCacheSizeKey
import com.metrolist.shared.constants.AnimatedCoverCacheSizeKey
import com.metrolist.shared.constants.AnimatedCoverResolutionKey
import com.metrolist.shared.constants.AppleMusicArtistVideoResolutionKey
import com.metrolist.shared.constants.AppleMusicArtistVideosKey
import com.metrolist.shared.constants.CanvasCacheSizeKey
import com.metrolist.shared.constants.DensityScaleKey
import com.metrolist.shared.constants.DownloadedCollectionSortDirectionKey
import com.metrolist.shared.constants.DownloadedCollectionSortKey
import com.metrolist.shared.constants.EnableLandscapeScalingKey
import com.metrolist.shared.constants.ForceBottomNavBarKey
import com.metrolist.shared.constants.LoudnessLevelKey
import com.metrolist.shared.constants.YouTubeCanvasModeKey
import com.metrolist.shared.constants.YouTubeCanvasQualityKey
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SortDirection
import com.russhwolf.settings.Settings as KmpSettings

private const val LEGACY_MIGRATION_VERSION_KEY = "settingsMigrationVersion"
private const val LEGACY_VALUE_MIGRATION_VERSION = 2
private const val LEGACY_MIGRATION_VERSION = 14

private val removedPlayerSettingsKeys =
    setOf(
        "enableMusicVideos",
        "autoPlayMusicVideos",
        "resumeOnBluetoothConnect",
    )

private val removedArtworkSettingsKeys =
    setOf(
        "enableAppleMusicArtwork",
        "appleMusicArtworkCacheJson",
    )

private val removedUnusedSettingsKeys =
    setOf(
        "handleAudioFocus",
        "discordRpcShowIdle",
        "discordRpcUseDetails",
        "discordRpcShowButtons",
        "discordRpcButton1Visible",
        "discordRpcButton2Visible",
        "topListLength",
        "topSize",
        "shufflePlaylistFirst",
        "librarySortMode",
        "editPlaylistViewType",
        "sideMenuWelcomeSeen",
        "androidMiniPlayerVisible",
        "enableGoogleCast",
        "desktopSidebarExpanded",
        "discordRpcActivityType",
        "discordRpcAppId",
        "discordRpcButton1Text",
        "discordRpcButton1Url",
        "discordRpcButton2Text",
        "discordRpcButton2Url",
        "highQualityJapaneseRomanization",
        "japaneseDictionaryStatus",
        "lastLibraryItemsJson",
        "showArtistPhotos",
        "lastFmPhotoAutoCycle",
        "lastFmPhotoCycleInterval",
        "artistLastFmPhotosJson",
    )

internal val removedLegacyKeys =
    setOf(
        "hidePlayerThumbnail",
        "defaultLibraryChip",
        "newPlayerDesign",
        "showArtistDescription",
        "lyricsGlowEffect",
        "enableLyricsPlus",
        "randomizeHomeOrder",
        "homeSectionOrder",
        "quickPicksMode",
        "showLyrics",
        "macosWindowButtons",
        "similarContent",
        "lyricsRomanizationProvider",
        "proxyEnabled",
        "proxyUrl",
        "proxyType",
        "proxyUsername",
        "proxyPassword",
        "listenTogetherPairedRoomCode",
        "listenTogetherAutoJoinPairedRoom",
        "deviceConnectRequireApproval",
        "deviceConnectLastUsedDeviceId",
        "pureBlack",
    ) + removedPlayerSettingsKeys + removedArtworkSettingsKeys + removedUnusedSettingsKeys

internal val legacyAliases =
    linkedMapOf(
        "darkMode" to "themeMode",
        "hideYoutubeShorts" to "hideShortSongs",
        "SwipeToSong" to "swipeToSong",
        "SwipeToRemoveSong" to "swipeToRemoveSong",
        "show_liked_playlist" to "showLikedPlaylist",
        "show_downloaded_playlist" to "showDownloadedPlaylist",
        "show_top_playlist" to "showTopPlaylist",
        "show_cached_playlist" to "showCachedPlaylist",
        "show_uploaded_playlist" to "showUploadedPlaylist",
        "discordRPCEnable" to "enableDiscordIntegration",
        "discordButton1Label" to "discordButton1Text",
        "discordButton2Label" to "discordButton2Text",
        "listenTogetherAutoApproval" to "listenTogetherAutoApproveJoinRequests",
        "listenTogetherAutoApproveSuggestions" to "listenTogetherAutoApproveSongSuggestions",
        "listenTogetherSyncVolume" to "listenTogetherSyncHostVolume",
        "lastUpdateCheckTime" to "lastUpdateCheckTimestamp",
        "aiProvider" to "lyricsAiProvider",
        "openRouterBaseUrl" to "lyricsAiBaseUrl",
        "openRouterModel" to "lyricsAiModel",
        "translateMode" to "lyricsAiTranslationMode",
        "translateLanguage" to "lyricsAiTargetLanguage",
        "deeplFormality" to "lyricsDeepLFormality",
        "aiSystemPrompt" to "lyricsAiSystemPrompt",
        "lyricsRomanizeList" to "lyricsRomanizeLanguages",
        "playerVolume" to "volume",
        "lastfmUsername" to "lastFmUsername",
        "lastfmSession" to "lastFmSession",
        "openRouterApiKey" to "lyricsAiApiKey",
        "deeplApiKey" to "lyricsDeepLApiKey",
        "shuffleMode" to "shuffleEnabled",
        "enableSongCache" to "songCacheEnabled",
        "disableScreenshot" to "blockScreenshots",
        "crossfadeDurationFloat" to "crossfadeDuration",
        "lyricsClick" to "lyricsClickToSeek",
        "lyricsScrollKey" to "lyricsAutoScroll",
        "hideStatusBarOnFullscreen" to "lyricsHideStatusBarInFullscreen",
        "scrobbleDelayPercent" to "lastFmDelayPercent",
        "scrobbleMinSongDuration" to "lastFmMinDuration",
        "scrobbleDelaySeconds" to "lastFmMaxDelay",
        "lastfmScrobblingEnable" to "lastFmEnabled",
        "lastfmUseNowPlaying" to "lastFmUseNowPlaying",
    )

private val enumTypes =
    mapOf(
        LoudnessLevelKey to LoudnessLevel.entries.map { it.name },
        "themeMode" to ThemeMode.entries.map { it.name },
        "appLayout" to AppLayout.entries.map { it.name },
        "sliderStyle" to SliderStyle.entries.map { it.name },
        "playerThumbnailStyle" to PlayerThumbnailStyle.entries.map { it.name },
        "playerBackgroundStyle" to PlayerBackgroundStyle.entries.map { it.name },
        "themePaletteStyle" to ThemePaletteStyle.entries.map { it.name },
        "libraryViewType" to LibraryViewType.entries.map { it.name },
        "libraryFilter" to LibraryFilterType.entries.map { it.name },
        "libraryContentSort" to LibraryContentSort.entries.map { it.name },
        "playlistSongSort" to PlaylistSongSort.entries.map { it.name },
        "playlistSongSortDirection" to SortDirection.entries.map { it.name },
        DownloadedCollectionSortKey to CollectionSort.entries.map { it.name },
        DownloadedCollectionSortDirectionKey to SortDirection.entries.map { it.name },
        "appFont" to FontOption.entries.map { it.name },
        "playerType" to PlayerType.entries.map { it.name },
        "miniPlayerType" to MiniPlayerType.entries.map { it.name },
        "keepScreenOnMode" to KeepScreenOnMode.entries.map { it.name },
        AnimatedCoverResolutionKey to AnimatedCoverResolution.entries.map { it.name },
        AppleMusicArtistVideoResolutionKey to AnimatedCoverResolution.entries.map { it.name },
        YouTubeCanvasQualityKey to MusicVideoQuality.entries.map { it.name },
        YouTubeCanvasModeKey to YouTubeCanvasMode.entries.map { it.name },
        "androidAutoPinnedPlaylistPlaybackMode" to AutomotivePinnedPlaylistPlaybackMode.entries.map { it.name },
    )

private val booleanKeys =
    setOf(
        EnableLandscapeScalingKey,
        ForceBottomNavBarKey,
        "hideExplicit",
        "explicitOnly",
        "hideVideoSongs",
        "hidePodcasts",
        "hideShortSongs",
        "blockAiSlopArtists",
        "materialYouEnabled",
        "dynamicTheme",
        "animatedGradient",
        "enableHapticFeedback",
        "showMiniplayer",
        "miniplayerFocusMode",
        "cropAlbumArt",
        "swapPlayerControls",
        "immersivePlayer",
        "playerCanvasEnabled",
        "isExpanded",
        "slimNavBar",
        "floatingSearch",
        "voiceSearchEnabled",
        "audioNormalization",
        "persistentQueue",
        "promptContinuePlayback",
        "pendingContinuePlayback",
        "persistentShuffleAcrossQueues",
        "rememberShuffleAndRepeat",
        "shuffleEnabled",
        "crossfadeEnabled",
        "crossfadeGapless",
        "skipSilence",
        "skipSilenceInstant",
        "autoSkipNextOnError",
        "seekExtraSeconds",
        "pauseOnMute",
        "varispeed",
        "audioTrackPlaybackParams",
        "stopMusicOnTaskClear",
        "githubNTransformEnabled",
        "lyricsClickToSeek",
        "lyricsAutoScroll",
        "lyricsAiTranslationEnabled",
        "lyricsRomanizationEnabled",
        "lyricsRomanizeAsMain",
        "lyricsRomanizeCyrillicByLine",
        "respectAgentPositioning",
        "lyricsHideStatusBarInFullscreen",
        "pauseListenHistory",
        "pauseSearchHistory",
        "ytmSync",
        "queueYouTubeHistoryWhenOffline",
        "showBottomNavHome",
        "showBottomNavSearch",
        "showBottomNavLibrary",
        "speedDialSurprisePinned",
        "showLikedPlaylist",
        "showDownloadedPlaylist",
        "showTopPlaylist",
        "showUploadedPlaylist",
        "showCachedPlaylist",
        "songCacheEnabled",
        "autoDownloadOnLike",
        "checkForUpdates",
        "updateNotifications",
        "enableLogging",
        "sentryEnabled",
        "blockScreenshots",
        "keepScreenOn",
        "enableDiscordIntegration",
        "discordRpcShowArtistIcon",
        "discordAdvancedMode",
        "discordButton1Enabled",
        "discordButton2Enabled",
        "lastFmEnabled",
        "lastFmUseNowPlaying",
        "lastFmShowFriends",
        "listenBrainzEnabled",
        "listenBrainzUseNowPlaying",
        "systemInDarkTheme",
        "listenTogetherAutoApproveJoinRequests",
        "listenTogetherAutoApproveSongSuggestions",
        "listenTogetherSyncHostVolume",
        "deviceConnectEnabled",
        "swipeThumbnail",
        "swipeToSong",
        "swipeToRemoveSong",
        "autoLoadMore",
        "disableLoadMoreWhenRepeatAll",
        "preventDuplicateTracksInQueue",
        "isQueueLocked",
        "enableAnimatedCovers",
        "enableAppleMusicArtistArtwork",
        AppleMusicArtistVideosKey,
        "libraryGridView",
    )

private val intKeys =
    setOf(
        AnimatedArtworkCacheSizeKey,
        "minimumSongDurationSeconds",
        "seekDurationSeconds",
        "maxSongCacheSize",
        "maxImageCacheSize",
        "lastFmMinDuration",
        "lastFmMaxDelay",
        "launchesSinceUpdatePrompt",
    )

private val longKeys =
    setOf(
        "selectedThemeColor",
        "aiSlopBlocklistUpdatedAt",
        "lastUpdateCheckTimestamp",
        "lastUpdateCheckAttemptTimestamp",
        "playerConfigRemoteFetchedAt",
        "discordExpiresAt",
        "lastLibraryRefreshEpoch",
    )

private val floatKeys =
    setOf(
        DensityScaleKey,
        "sliderTrackThicknessDp",
        "playbackSpeed",
        "volume",
        "previousVolume",
        "crossfadeDuration",
        "historyDuration",
        "lastFmDelayPercent",
        "swipeSensitivity",
    )

internal fun migrateLegacySettings(settings: KmpSettings) {
    val migrationVersion = settings.readIntOrNull(LEGACY_MIGRATION_VERSION_KEY) ?: 0
    if (migrationVersion < LEGACY_MIGRATION_VERSION) removedUnusedSettingsKeys.forEach(settings::remove)
    if (migrationVersion in 11 until LEGACY_MIGRATION_VERSION) restoreCanvasToggle(settings)
    if (migrationVersion == 11) {
        settings.putInt(LEGACY_MIGRATION_VERSION_KEY, LEGACY_MIGRATION_VERSION)
        return
    }
    if (migrationVersion >= LEGACY_MIGRATION_VERSION) return
    listOf(DensityScaleKey, EnableLandscapeScalingKey, ForceBottomNavBarKey).forEach { key ->
        val raw = rawLegacyValue(settings, key) ?: return@forEach
        val converted = convertLegacyValue(key, raw)
        if (converted != null) {
            writeValue(settings, key, converted)
        } else {
            settings.remove(key)
        }
    }
    migrateCanvasThumbnailStyle(settings)
    if (settings.readBooleanOrNull("pureBlack") == true) {
        settings.putInt("themeMode", ThemeMode.PURE_BLACK.ordinal)
    }
    settings.remove("pureBlack")
    if (migrationVersion >= LEGACY_VALUE_MIGRATION_VERSION) {
        listOf(
            LoudnessLevelKey,
            AnimatedCoverResolutionKey,
            AppleMusicArtistVideoResolutionKey,
            YouTubeCanvasQualityKey,
            YouTubeCanvasModeKey,
        ).forEach { key ->
            settings.readStringOrNull(key)?.let { value ->
                convertLegacyValue(key, value)?.let { writeValue(settings, key, it) }
            }
        }
        (removedPlayerSettingsKeys + removedArtworkSettingsKeys + removedUnusedSettingsKeys).forEach(settings::remove)
        settings.putInt(LEGACY_MIGRATION_VERSION_KEY, LEGACY_MIGRATION_VERSION)
        return
    }

    enumTypes.forEach { (key, _) ->
        val raw = settings.readStringOrNull(key) ?: return@forEach
        convertLegacyValue(key, raw)?.let { writeValue(settings, key, it) }
    }
    normalizeStringSetting(settings, "appLanguage") { if (it == "SYSTEM_DEFAULT") "SYSTEM" else it }
    normalizeStringSetting(settings, "miniPlayerBackgroundStyle", ::legacyMiniPlayerBackground)
    if (settings.readLongOrNull("selectedThemeColor") == null) {
        settings.readIntOrNull("selectedThemeColor")?.toLong()?.let { settings.putLong("selectedThemeColor", it) }
    }
    settings.readFloatOrNull("swipeSensitivity")?.let { value ->
        legacySwipeSensitivity(value)?.let { settings.putFloat("swipeSensitivity", it) }
    }
    migrateLegacyBooleanEnum(settings, "squigglySlider", "sliderStyle", SliderStyle.SQUIGGLY.ordinal, SliderStyle.DEFAULT.ordinal)
    migrateLegacyBooleanEnum(settings, "useNewPlayerDesign", "playerType", PlayerType.MODERN.ordinal, PlayerType.VINTAGE.ordinal)
    migrateLegacyBooleanEnum(
        settings,
        "useNewMiniPlayerDesign",
        "miniPlayerType",
        MiniPlayerType.MODERN.ordinal,
        MiniPlayerType.VINTAGE.ordinal,
    )
    if (settings.readBooleanOrNull("pureBlackMiniPlayer") == true &&
        settings.readIntOrNull("miniPlayerType") == MiniPlayerType.VINTAGE.ordinal
    ) {
        settings.putString("miniPlayerBackgroundStyle", "SOLID_BLACK")
    }
    settings.remove("pureBlackMiniPlayer")

    if (settings.hasKey("similarContent")) {
        val similar = settings.readBooleanOrNull("similarContent")
        if (similar != null) {
            val autoLoadMore = settings.readBooleanOrNull("autoLoadMore") ?: true
            settings.putBoolean("autoLoadMore", autoLoadMore && similar)
        }
    }

    legacyAliases.forEach { (source, destination) ->
        if (settings.hasKey(destination) && isValidCurrent(settings, destination)) {
            settings.remove(source)
            return@forEach
        }
        val raw = rawLegacyValue(settings, source) ?: return@forEach
        val converted = convertLegacyValue(destination, raw)
        if (converted != null) {
            writeValue(settings, destination, converted)
            settings.remove(source)
        }
    }

    migrateLyricsProviderFlags(settings)

    settings.remove("similarContent")
    removedLegacyKeys.forEach(settings::remove)
    settings.putInt(LEGACY_MIGRATION_VERSION_KEY, LEGACY_MIGRATION_VERSION)
}

internal fun combinedAnimatedArtworkCacheLimit(
    canvasMb: Int?,
    coverMb: Int?,
): Int =
    ((canvasMb ?: 1024).coerceAtLeast(0).toLong() + (coverMb ?: 1024).coerceAtLeast(0))
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

internal fun migrateAnimatedArtworkCacheSettings(settings: KmpSettings) {
    if (!settings.hasKey(AnimatedArtworkCacheSizeKey) &&
        (settings.hasKey(CanvasCacheSizeKey) || settings.hasKey(AnimatedCoverCacheSizeKey))
    ) {
        settings.putInt(
            AnimatedArtworkCacheSizeKey,
            combinedAnimatedArtworkCacheLimit(
                settings.readIntOrNull(CanvasCacheSizeKey),
                settings.readIntOrNull(AnimatedCoverCacheSizeKey),
            ),
        )
    }
    settings.removeExisting(CanvasCacheSizeKey, AnimatedCoverCacheSizeKey)
}

private fun rawLegacyValue(
    settings: KmpSettings,
    key: String,
): Any? =
    settings.readStringOrNull(key)
        ?: settings.readBooleanOrNull(key)
        ?: settings.readIntOrNull(key)
        ?: settings.readLongOrNull(key)
        ?: settings.readFloatOrNull(key)

private fun isValidCurrent(
    settings: KmpSettings,
    key: String,
): Boolean =
    when {
        key == "libraryFilter" -> settings.readIntOrNull(key)?.let(LibraryFilterType::fromStoredValue) != null
        key in enumTypes -> settings.readIntOrNull(key)?.let { it in enumTypes.getValue(key).indices } == true
        key in booleanKeys -> settings.readBooleanOrNull(key) != null
        key in intKeys -> settings.readIntOrNull(key) != null
        key in longKeys -> settings.readLongOrNull(key) != null
        key in floatKeys -> settings.readFloatOrNull(key)?.isFinite() == true
        else -> settings.readStringOrNull(key) != null
    }

internal fun convertLegacyValue(
    key: String,
    value: Any,
): Any? =
    when {
        key == "themeMode" && value is String && value == "ON" -> {
            ThemeMode.DARK.ordinal
        }

        key == "themeMode" && value is String && value == "OFF" -> {
            ThemeMode.LIGHT.ordinal
        }

        key == "themeMode" && value is String && value == "DEFAULT" -> {
            ThemeMode.AUTO.ordinal
        }

        key == "appLanguage" && value == "SYSTEM_DEFAULT" -> {
            "SYSTEM"
        }

        key == "selectedThemeColor" -> {
            (value as? Number)?.toLong() ?: (value as? String)?.toLongOrNull()
        }

        key == "swipeSensitivity" -> {
            legacySwipeSensitivity(value)
        }

        key == "miniPlayerBackgroundStyle" -> {
            legacyMiniPlayerBackground(value.toString())
        }

        key == "playerBackgroundStyle" -> {
            legacyPlayerBackground(value.toString())?.let { name ->
                PlayerBackgroundStyle.entries.firstOrNull { it.name == name }?.ordinal
            }
        }

        key == "libraryFilter" -> {
            when (value) {
                is Number -> LibraryFilterType.fromStoredValue(value.toInt())
                "OWNED" -> LibraryFilterType.PLAYLISTS
                else -> LibraryFilterType.entries.firstOrNull { it.name == value }
            }?.storedValue
        }

        key in enumTypes -> {
            when (value) {
                is Number -> {
                    value.toInt().takeIf { it in enumTypes.getValue(key).indices }
                }

                is String -> {
                    if (key == "playerBackgroundStyle" && value == "BLUR") {
                        PlayerBackgroundStyle.BLURRED_ARTWORK.ordinal
                    } else {
                        enumTypes.getValue(key).indexOf(value).takeIf { it >= 0 }
                    }
                }

                else -> {
                    null
                }
            }
        }

        key in booleanKeys -> {
            value as? Boolean ?: (value as? String)?.toBooleanStrictOrNull()
        }

        key in intKeys -> {
            value.toString().toIntOrNull()
        }

        key in longKeys -> {
            value.toString().toLongOrNull()
        }

        key == DensityScaleKey -> {
            value.toString().toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        }

        key in floatKeys -> {
            value.toString().toFloatOrNull()?.takeIf(Float::isFinite)
        }

        else -> {
            value as? String
        }
    }

private fun restoreCanvasToggle(settings: KmpSettings) {
    if (settings.hasKey("playerCanvasEnabled")) return
    val covers =
        settings.readBooleanOrNull("enableAnimatedCovers")
            ?: settings.readStringOrNull("enableAnimatedCovers")?.toBooleanStrictOrNull() ?: false
    if (covers) settings.putBoolean("playerCanvasEnabled", true)
}

private fun migrateCanvasThumbnailStyle(settings: KmpSettings) {
    val isCanvasStyle =
        settings.readIntOrNull("playerThumbnailStyle") == PlayerThumbnailStyle.CANVAS.ordinal ||
            settings.readStringOrNull("playerThumbnailStyle") == PlayerThumbnailStyle.CANVAS.name
    if (!isCanvasStyle) return
    if (!settings.hasKey("playerCanvasEnabled")) settings.putBoolean("playerCanvasEnabled", true)
    settings.putInt("playerThumbnailStyle", PlayerThumbnailStyle.COMPACT.ordinal)
}

private fun writeValue(
    settings: KmpSettings,
    key: String,
    value: Any,
) {
    when (value) {
        is Boolean -> settings.putBoolean(key, value)
        is Int -> settings.putInt(key, value)
        is Long -> settings.putLong(key, value)
        is Float -> settings.putFloat(key, value)
        is String -> settings.putString(key, value)
    }
}

private fun normalizeStringSetting(
    settings: KmpSettings,
    key: String,
    transform: (String) -> String?,
) {
    settings.readStringOrNull(key)?.let { value -> transform(value)?.let { settings.putString(key, it) } }
}

private fun legacySwipeSensitivity(value: Any): Float? =
    value
        .toString()
        .toFloatOrNull()
        ?.takeIf(Float::isFinite)
        ?.let { if (it in 0f..1f) it * 100f else it }

private fun legacyPlayerBackground(value: String): String? =
    when (value) {
        "DEFAULT" -> PlayerBackgroundStyle.SOLID.name
        "BLUR" -> PlayerBackgroundStyle.BLURRED_ARTWORK.name
        else -> value.takeIf { it in PlayerBackgroundStyle.entries.map(PlayerBackgroundStyle::name) }
    }

private fun legacyMiniPlayerBackground(value: String): String? =
    when (value) {
        "BLUR" -> "BLURRED_ARTWORK"
        "PURE_BLACK" -> "SOLID_BLACK"
        "TRANSPARENT" -> "DEFAULT"
        else -> value.takeIf { it in setOf("DEFAULT", "BLURRED_ARTWORK", "GRADIENT", "SOLID_BLACK") }
    }

private fun migrateLyricsProviderFlags(settings: KmpSettings) {
    val providerFlags =
        linkedMapOf(
            "enableBetterLyrics" to "BETTERLYRICS",
            "enableLrclib" to "LRCLIB",
            "enableKugou" to "KUGOU",
            "enablePaxsenix" to "PAXSENIX",
        )
    if (settings.readStringOrNull("lyricsEnabledProviders") != null) {
        providerFlags.keys.forEach(settings::remove)
        return
    }
    val present = providerFlags.keys.filter(settings::hasKey)
    if (present.isNotEmpty()) {
        val converted = present.mapNotNull { key -> settings.readBooleanOrNull(key)?.let { key to it } }.toMap()
        val enabled =
            buildList {
                providerFlags.forEach { (key, provider) ->
                    if (converted[key] != false) add(provider)
                }
                add("UNISON")
            }
        settings.putString("lyricsEnabledProviders", enabled.joinToString(","))
        converted.keys.forEach(settings::remove)
    }
}

private fun migrateLegacyBooleanEnum(
    settings: KmpSettings,
    source: String,
    destination: String,
    enabledValue: Int,
    disabledValue: Int,
) {
    if (!settings.hasKey(source)) return
    if (isValidCurrent(settings, destination)) {
        settings.remove(source)
        return
    }
    settings.readBooleanOrNull(source)?.let { enabled ->
        settings.putInt(destination, if (enabled) enabledValue else disabledValue)
        settings.remove(source)
    }
}

private fun KmpSettings.readStringOrNull(key: String): String? = runCatching { getStringOrNull(key) }.getOrNull()

private fun KmpSettings.readBooleanOrNull(key: String): Boolean? = runCatching { getBooleanOrNull(key) }.getOrNull()

private fun KmpSettings.readIntOrNull(key: String): Int? = runCatching { getIntOrNull(key) }.getOrNull()

private fun KmpSettings.readLongOrNull(key: String): Long? = runCatching { getLongOrNull(key) }.getOrNull()

private fun KmpSettings.readFloatOrNull(key: String): Float? = runCatching { getFloatOrNull(key) }.getOrNull()
