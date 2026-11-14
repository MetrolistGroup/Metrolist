/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.constants.ActiveLyricsTextPositionKey
import com.metrolist.shared.constants.AiSlopBlocklistCacheKey
import com.metrolist.shared.constants.AiSlopBlocklistUpdatedAtKey
import com.metrolist.shared.constants.AndroidActiveFontIdKey
import com.metrolist.shared.constants.AndroidAutoPinnedPlaylistPlaybackModeKey
import com.metrolist.shared.constants.AndroidAutoPinnedPlaylistsJsonKey
import com.metrolist.shared.constants.AndroidAutoTargetPlaylistKey
import com.metrolist.shared.constants.AndroidCustomFontFileKey
import com.metrolist.shared.constants.AndroidCustomFontNameKey
import com.metrolist.shared.constants.AndroidLastStorageAuditKey
import com.metrolist.shared.constants.AndroidLastTrackedPlaylistSyncKey
import com.metrolist.shared.constants.AndroidStorageAuditLocationKey
import com.metrolist.shared.constants.AnimatedArtworkCacheSizeKey
import com.metrolist.shared.constants.AnimatedCoverCacheJsonKey
import com.metrolist.shared.constants.AnimatedCoverCacheSizeKey
import com.metrolist.shared.constants.AnimatedCoverResolutionKey
import com.metrolist.shared.constants.AppIconKey
import com.metrolist.shared.constants.AppLanguageKey
import com.metrolist.shared.constants.AppleMusicArtistCacheJsonKey
import com.metrolist.shared.constants.AppleMusicArtistImagesKey
import com.metrolist.shared.constants.AppleMusicArtistVideoResolutionKey
import com.metrolist.shared.constants.AppleMusicArtistVideosKey
import com.metrolist.shared.constants.ArtistNameAliasesJsonKey
import com.metrolist.shared.constants.AudioBoostDbKey
import com.metrolist.shared.constants.AudioTrackPlaybackParamsKey
import com.metrolist.shared.constants.AutoPureBlackKey
import com.metrolist.shared.constants.BlockAiSlopArtistsKey
import com.metrolist.shared.constants.CachedCollectionSortDirectionKey
import com.metrolist.shared.constants.CachedCollectionSortKey
import com.metrolist.shared.constants.CanvasCacheSizeKey
import com.metrolist.shared.constants.CanvasContentOrderKey
import com.metrolist.shared.constants.CanvasEnabledSourcesKey
import com.metrolist.shared.constants.CastEnabledKey
import com.metrolist.shared.constants.CollectionCoversKey
import com.metrolist.shared.constants.DefaultOpenTabKey
import com.metrolist.shared.constants.DeletedPlaylistIdsJsonKey
import com.metrolist.shared.constants.DensityScaleKey
import com.metrolist.shared.constants.DesktopRenderBackendKey
import com.metrolist.shared.constants.DesktopStoppedDownloadIdsJsonKey
import com.metrolist.shared.constants.DesktopWindowPlacementKey
import com.metrolist.shared.constants.DesktopWindowPositionKey
import com.metrolist.shared.constants.DeviceConnectDefaultDeviceIdKey
import com.metrolist.shared.constants.DeviceConnectExperimentalEnabledKey
import com.metrolist.shared.constants.DevtoolsBenchmarkClientsKey
import com.metrolist.shared.constants.DevtoolsPlaybackClientKey
import com.metrolist.shared.constants.DevtoolsUnlockedKey
import com.metrolist.shared.constants.DisableAnimatedArtworkOnMobileDataKey
import com.metrolist.shared.constants.DiscordPendingAuthSessionKey
import com.metrolist.shared.constants.DownloadQueueJsonKey
import com.metrolist.shared.constants.DownloadedCollectionSortDirectionKey
import com.metrolist.shared.constants.DownloadedCollectionSortKey
import com.metrolist.shared.constants.EnableHapticFeedbackKey
import com.metrolist.shared.constants.EnableLandscapeScalingKey
import com.metrolist.shared.constants.EqualizerActiveProfileIdKey
import com.metrolist.shared.constants.EqualizerCurrentProfileJsonKey
import com.metrolist.shared.constants.EqualizerEnabledKey
import com.metrolist.shared.constants.EqualizerProfilesJsonKey
import com.metrolist.shared.constants.ExplicitOnlyKey
import com.metrolist.shared.constants.ExpressiveMiniPlayerArtworkPlaybackKey
import com.metrolist.shared.constants.ExpressiveMiniPlayerCookieArtworkKey
import com.metrolist.shared.constants.ExpressiveMiniPlayerShapeMotionKey
import com.metrolist.shared.constants.FloatingNavBarKey
import com.metrolist.shared.constants.FloatingTopBarKey
import com.metrolist.shared.constants.ForceBottomNavBarKey
import com.metrolist.shared.constants.GitHubNTransformConfigUrlKey
import com.metrolist.shared.constants.GitHubNTransformEnabledKey
import com.metrolist.shared.constants.GlobalLyricsOffsetMsKey
import com.metrolist.shared.constants.GridItemSizeKey
import com.metrolist.shared.constants.HasNewUploadsKey
import com.metrolist.shared.constants.HiddenHomeItemsJsonKey
import com.metrolist.shared.constants.HideNonMusicalVideosKey
import com.metrolist.shared.constants.HideRecentSearchesKey
import com.metrolist.shared.constants.HideShortSongsKey
import com.metrolist.shared.constants.HistoryPlaylistsEnabledKey
import com.metrolist.shared.constants.IosCustomFontFileKey
import com.metrolist.shared.constants.IosCustomFontNameKey
import com.metrolist.shared.constants.IosCustomFontRevisionKey
import com.metrolist.shared.constants.IosOnboardingCompleteKey
import com.metrolist.shared.constants.KeepDownloadedPlaylistsJsonKey
import com.metrolist.shared.constants.KeepPausedOnSongChangeKey
import com.metrolist.shared.constants.KnownUploadIdsJsonKey
import com.metrolist.shared.constants.LastFmArtistCacheJsonKey
import com.metrolist.shared.constants.LastFmPhotoCacheJsonKey
import com.metrolist.shared.constants.LastLibraryRefreshAccountKey
import com.metrolist.shared.constants.LastLibraryRefreshEpochKey
import com.metrolist.shared.constants.LastRemoteLibraryItemKeysJsonKey
import com.metrolist.shared.constants.LibraryGridColumnsKey
import com.metrolist.shared.constants.LibraryManualOrderKey
import com.metrolist.shared.constants.LibraryPinchColumnsKey
import com.metrolist.shared.constants.LibrarySortDirectionKey
import com.metrolist.shared.constants.LibrarySortingByFilterKey
import com.metrolist.shared.constants.LikedSongSortDirectionKey
import com.metrolist.shared.constants.LikedSongSortKey
import com.metrolist.shared.constants.LikedSongsCustomOrdersJsonKey
import com.metrolist.shared.constants.LikedSongsOrderAccountKey
import com.metrolist.shared.constants.LikedSongsOrderCustomizedKey
import com.metrolist.shared.constants.LikedSongsRecencyJsonKey
import com.metrolist.shared.constants.ListenTogetherEnabledKey
import com.metrolist.shared.constants.ListenTogetherServerMetadataKey
import com.metrolist.shared.constants.LoudnessLevelKey
import com.metrolist.shared.constants.LyricsActiveByRealTimingKey
import com.metrolist.shared.constants.LyricsNeutralColorKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthKey
import com.metrolist.shared.constants.LyricsOpenRouterOAuthSessionKey
import com.metrolist.shared.constants.LyricsPlusLighterKey
import com.metrolist.shared.constants.LyricsProgressiveBlurKey
import com.metrolist.shared.constants.LyricsShareImageStyleKey
import com.metrolist.shared.constants.MinimumSongDurationSecondsKey
import com.metrolist.shared.constants.MusicVideoCacheJsonKey
import com.metrolist.shared.constants.MusicVideoPlaybackEnabledKey
import com.metrolist.shared.constants.MusicVideoQualityKey
import com.metrolist.shared.constants.PauseOnMuteKey
import com.metrolist.shared.constants.PendingContinuePlaybackKey
import com.metrolist.shared.constants.PendingRemoteLibraryRemovalKeysJsonKey
import com.metrolist.shared.constants.PendingSongVotesJsonKey
import com.metrolist.shared.constants.PersistentPlaybackPositionKey
import com.metrolist.shared.constants.PlayVideosInMiniPlayerKey
import com.metrolist.shared.constants.PlaybackClientHealthJsonKey
import com.metrolist.shared.constants.PlaybackPitchKey
import com.metrolist.shared.constants.PlaybackSpeedKey
import com.metrolist.shared.constants.PlayerConfigRemoteEtagKey
import com.metrolist.shared.constants.PlayerConfigRemoteSourceUrlKey
import com.metrolist.shared.constants.PlaylistCollectionSortingsKey
import com.metrolist.shared.constants.PlaylistPickerSortDirectionKey
import com.metrolist.shared.constants.PlaylistPickerSortKey
import com.metrolist.shared.constants.PlaylistSongSortingsKey
import com.metrolist.shared.constants.PreferWordSyncedLyricsKey
import com.metrolist.shared.constants.PromptContinuePlaybackKey
import com.metrolist.shared.constants.QueuedYouTubeHistoryJsonKey
import com.metrolist.shared.constants.RecentPlayedSearchSongsJsonKey
import com.metrolist.shared.constants.SeekDurationSecondsKey
import com.metrolist.shared.constants.SentryEnabledKey
import com.metrolist.shared.constants.ShowBottomNavHomeKey
import com.metrolist.shared.constants.ShowBottomNavLibraryKey
import com.metrolist.shared.constants.ShowBottomNavSearchKey
import com.metrolist.shared.constants.ShowExpressiveMiniPlayerLikeButtonKey
import com.metrolist.shared.constants.ShowExpressiveMiniPlayerNextButtonKey
import com.metrolist.shared.constants.ShowExpressiveMiniPlayerPlayPauseButtonKey
import com.metrolist.shared.constants.ShowExpressiveMiniPlayerPlaylistButtonKey
import com.metrolist.shared.constants.ShowExpressiveMiniPlayerPreviousButtonKey
import com.metrolist.shared.constants.ShowExpressiveMiniPlayerProgressKey
import com.metrolist.shared.constants.ShowExpressiveMiniPlayerSubscribeButtonKey
import com.metrolist.shared.constants.ShowLyricsOnPlayerKey
import com.metrolist.shared.constants.ShowMiniPlayerLikeButtonKey
import com.metrolist.shared.constants.ShowMiniPlayerPlaylistButtonKey
import com.metrolist.shared.constants.ShowMiniPlayerSubscribeButtonKey
import com.metrolist.shared.constants.ShowPlayerCastButtonKey
import com.metrolist.shared.constants.ShowPlayerVideoButtonKey
import com.metrolist.shared.constants.SleepTimerDefaultFadeOutKey
import com.metrolist.shared.constants.SleepTimerDefaultFinishCurrentSongKey
import com.metrolist.shared.constants.SleepTimerDefaultMinutesKey
import com.metrolist.shared.constants.SliderTrackThicknessDpKey
import com.metrolist.shared.constants.SlimNavBarKey
import com.metrolist.shared.constants.SpeedDialArtworkRefreshTimesKey
import com.metrolist.shared.constants.SpeedDialSurprisePinnedKey
import com.metrolist.shared.constants.SponsorBlockEnabledKey
import com.metrolist.shared.constants.SwipeToRemoveSongKey
import com.metrolist.shared.constants.SwipeToSongKey
import com.metrolist.shared.constants.TempoPitchPresetsJsonKey
import com.metrolist.shared.constants.TokenLoginWarningDismissedKey
import com.metrolist.shared.constants.UseLegacyShuffleKey
import com.metrolist.shared.constants.VarispeedKey
import com.metrolist.shared.constants.VideoViewerVisibleKey
import com.metrolist.shared.constants.VoiceSearchEnabledKey
import com.metrolist.shared.constants.YouTubeCanvasModeKey
import com.metrolist.shared.constants.YouTubeCanvasQualityKey
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.PlaylistPickerSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.playback.SleepTimerDefaults
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.enums.enumEntries
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.uuid.Uuid
import com.russhwolf.settings.Settings as KmpSettings

class Settings(
    private val settings: KmpSettings,
) {
    private val settingTypes = mutableMapOf<String, SettingType>()
    private val preferenceRefreshers = mutableMapOf<String, () -> Unit>()
    private val preferenceDefaults = mutableMapOf<String, Any?>()

    init {
        if ((settings as? DeferredSecretMigrationSettings)?.secretRecoveryPending != true) migrateInitialSettings()
    }

    private fun migrateInitialSettings() {
        migrateLegacySettings(settings)
        migrateAnimatedArtworkCacheSettings(settings)
        settings.getFloatOrNull(SliderTrackThicknessDpKey)?.let { thickness ->
            if (thickness !in 2f..10f) settings.putFloat(SliderTrackThicknessDpKey, thickness.coerceIn(2f, 10f))
        }
        if (!settings.hasKey("keepScreenOnMode") && settings.getBoolean("keepScreenOn", false)) {
            settings.putInt("keepScreenOnMode", KeepScreenOnMode.PLAYER_AND_LYRICS.ordinal)
        }
        settings.removeExisting(
            "keepScreenOn",
            "listenTogetherPairedRoomCode",
            "listenTogetherAutoJoinPairedRoom",
            "deviceConnectRequireApproval",
            "deviceConnectLastUsedDeviceId",
        )
    }

    fun migrateLegacyV13AccountSettings() {
        if (lastFmUsername.isBlank()) {
            lastFmUsername = settings.getString("lastfmUsername", "")
        }
        if (lastFmSession.isBlank()) {
            lastFmSession = settings.getString("lastfmSession", "")
        }
    }

    private var sleepTimerDefaultMinutes by preference(SleepTimerDefaultMinutesKey, 15)
    private var sleepTimerDefaultFinishCurrentSong by preference(SleepTimerDefaultFinishCurrentSongKey, false)
    private var sleepTimerDefaultFadeOut by preference(SleepTimerDefaultFadeOutKey, false)

    var sleepTimerDefaults: SleepTimerDefaults
        get() =
            if (sleepTimerDefaultMinutes in 1..1439) {
                SleepTimerDefaults(sleepTimerDefaultMinutes, sleepTimerDefaultFinishCurrentSong, sleepTimerDefaultFadeOut)
            } else {
                SleepTimerDefaults()
            }
        set(value) {
            require(value.durationMinutes in 1..1439)
            sleepTimerDefaultMinutes = value.durationMinutes
            sleepTimerDefaultFinishCurrentSong = value.finishCurrentSong
            sleepTimerDefaultFadeOut = value.fadeOut
        }

    var artistNameAliasesJson by preference(ArtistNameAliasesJsonKey, "{}")
    val artistNameAliases: Map<String, String> by derivedStateOf { ArtistNameAliases.deserialize(artistNameAliasesJson) }

    var tokenLoginWarningDismissed by preference(TokenLoginWarningDismissedKey, false)
    var visitorData by preference("")
    var dataSyncId by preference("")
    var innerTubeAuthUser by preference("0")
    var accountName by preference("")
    var accountEmail by preference("")
    var accountChannelHandle by preference("")
    var accountThumbnail by preference("")
    var spotifySession by preference("")

    private var storedAppLanguage by preference(AppLanguageKey, "SYSTEM")
    var appLanguage: String
        get() = canonicalAppLanguageTag(storedAppLanguage)
        set(value) {
            storedAppLanguage = canonicalAppLanguageTag(value)
        }
    var contentLanguage by preference("SYSTEM")
    var contentCountry by preference("SYSTEM")
    var lastResolvedContentCountry by preference("")
    var lastResolvedContentLanguage by preference("")
    var hideExplicit by preference(false)
    var explicitOnly by preference(ExplicitOnlyKey, false)
    var hideVideoSongs by preference(false)
    var hideNonMusicalVideos by preference(HideNonMusicalVideosKey, false)
    var hidePodcasts by preference(false)
    var hideShortSongs by preference(HideShortSongsKey, false)
    var minimumSongDurationSeconds by preference(MinimumSongDurationSecondsKey, 30)
    var blockAiSlopArtists by preference(BlockAiSlopArtistsKey, true)
    var aiSlopBlocklistCache by blobPreference(AiSlopBlocklistCacheKey)
    var aiSlopBlocklistUpdatedAt by preference(AiSlopBlocklistUpdatedAtKey, 0L)
    var knownUploadIdsJson by preference(KnownUploadIdsJsonKey, "")
    var hasNewUploads by preference(HasNewUploadsKey, false)

    var materialYouEnabled by preference(true)
    var uiEnhancements by preference(true)
    var dynamicTheme by preference(true)
    var themeMode by preference(ThemeMode.AUTO)
    var autoPureBlack by preference(AutoPureBlackKey, false)
    var selectedThemeColor by preference(0L)
    var themePaletteStyle by preference(ThemePaletteStyle.TONAL_SPOT)

    var appFont by preference(FontOption.SYSTEM)
    var androidCustomFontFile by preference(AndroidCustomFontFileKey, "")
    var androidCustomFontName by preference(AndroidCustomFontNameKey, "")
    var androidActiveFontId by preference(AndroidActiveFontIdKey, "")
    var iosCustomFontFile by preference(IosCustomFontFileKey, "")
    var iosCustomFontName by preference(IosCustomFontNameKey, "")
    var iosCustomFontRevision by preference(IosCustomFontRevisionKey, 0L)
    var animatedGradient by preference(true)
    var appIcon by preference(AppIconKey, "ORIGINAL")
    var enableHapticFeedback by preference(EnableHapticFeedbackKey, true)

    var appLayout by preference(AppLayout.EXPRESS)
    var showMiniplayer by preference(false)
    var videoViewerVisible by preference(VideoViewerVisibleKey, false)
    var playVideosInMiniPlayer by preference(PlayVideosInMiniPlayerKey, false)
    var showMiniPlayerPlaylistButton by preference(ShowMiniPlayerPlaylistButtonKey, true)
    var showMiniPlayerLikeButton by preference(ShowMiniPlayerLikeButtonKey, true)
    var showMiniPlayerSubscribeButton by preference(ShowMiniPlayerSubscribeButtonKey, false)
    var showExpressiveMiniPlayerPreviousButton by preference(ShowExpressiveMiniPlayerPreviousButtonKey, true)
    var showExpressiveMiniPlayerPlayPauseButton by preference(ShowExpressiveMiniPlayerPlayPauseButtonKey, true)
    var showExpressiveMiniPlayerNextButton by preference(ShowExpressiveMiniPlayerNextButtonKey, true)
    var showExpressiveMiniPlayerPlaylistButton by preference(ShowExpressiveMiniPlayerPlaylistButtonKey, false)
    var showExpressiveMiniPlayerSubscribeButton by preference(ShowExpressiveMiniPlayerSubscribeButtonKey, false)
    var showExpressiveMiniPlayerLikeButton by preference(ShowExpressiveMiniPlayerLikeButtonKey, false)
    var showExpressiveMiniPlayerProgress by preference(ShowExpressiveMiniPlayerProgressKey, false)
    var expressiveMiniPlayerArtworkPlayback by preference(ExpressiveMiniPlayerArtworkPlaybackKey, false)
    var expressiveMiniPlayerCookieArtwork by preference(ExpressiveMiniPlayerCookieArtworkKey, true)
    var expressiveMiniPlayerShapeMotion by preference(ExpressiveMiniPlayerShapeMotionKey, true)

    var miniplayerFocusMode by preference(false)
    var miniPlayerBackgroundStyle by preference("DEFAULT")
    var playerThumbnailStyle by preference(PlayerThumbnailStyle.COMPACT)
    var castEnabled by preference(CastEnabledKey, true)
    var showPlayerCastButton by preference(ShowPlayerCastButtonKey, true)
    var showPlayerVideoButton by preference(ShowPlayerVideoButtonKey, true)

    val isPlayerCastButtonVisible: Boolean
        get() = castEnabled && showPlayerCastButton
    var playerBackgroundStyle by preference(PlayerBackgroundStyle.SOLID)
    var sliderStyle by preference(SliderStyle.DEFAULT)
    var sliderTrackThicknessDp by preference(SliderTrackThicknessDpKey, 5f)
    var playbackSpeed by preference(PlaybackSpeedKey, 1f)
    var playbackPitch by preference(PlaybackPitchKey, 1f)
    private var tempoPitchPresetsJson by preference(TempoPitchPresetsJsonKey, "[]")

    val tempoPitchPresets: List<TempoPitchPreset> by derivedStateOf { TempoPitchPreset.decode(tempoPitchPresetsJson) }

    fun saveTempoPitchPreset(
        name: String,
        speed: Float,
        pitch: Float,
    ) {
        val preset = TempoPitchPreset(id = Uuid.random().toString(), name = name.trim(), speed = speed, pitch = pitch)
        if (!preset.isValid) return
        tempoPitchPresetsJson = Json.encodeToString(tempoPitchPresets + preset)
    }

    fun deleteTempoPitchPreset(id: String) {
        tempoPitchPresetsJson = Json.encodeToString(tempoPitchPresets.filterNot { it.id == id })
    }

    var varispeed by preference(VarispeedKey, false)
    var audioTrackPlaybackParams by preference(AudioTrackPlaybackParamsKey, false)
    var equalizerProfilesJson by preference(EqualizerProfilesJsonKey, "")
    var equalizerCurrentProfileJson by preference(EqualizerCurrentProfileJsonKey, "")
    var equalizerActiveProfileId by preference(EqualizerActiveProfileIdKey, "")
    var equalizerEnabled by preference(EqualizerEnabledKey, false)
    var audioBoostDb by preference(AudioBoostDbKey, 0f)
    var playerType by preference(PlayerType.MODERN)
    var miniPlayerType by preference(MiniPlayerType.EXPRESSIVE)
    var useRoundedRectPlayButton by preference(false)
    var cropAlbumArt by preference(true)
    var swapPlayerControls by preference(false)
    var immersivePlayer by preference(false)
    var isExpanded by preference(false)
    var densityScaleFactor by preference(DensityScaleKey, 1f)
    var enableLandscapeScaling by preference(EnableLandscapeScalingKey, false)
    var forceBottomNavBar by preference(ForceBottomNavBarKey, false)
    var slimNavBar by preference(SlimNavBarKey, false)
    var floatingNavBar by preference(FloatingNavBarKey, false)
    var floatingTopBar by preference(FloatingTopBarKey, false)
    var floatingSearch by preference(false)
    var voiceSearchEnabled by preference(VoiceSearchEnabledKey, true)

    var playerCanvasEnabled by preference(false)

    var hasCanvasToggle = false

    val isCanvasEnabled: Boolean
        get() = if (hasCanvasToggle) playerCanvasEnabled else enableAnimatedCovers

    val isAnimatedArtworkOrCanvasEnabled: Boolean
        get() = isCanvasEnabled || enableAnimatedCovers

    var audioQuality by preference("AUTO")
    var audioNormalization by preference(false)
    var loudnessLevel by preference(LoudnessLevelKey, LoudnessLevel.BALANCED)
    var volume by preference(0.7f)

    /** False until a volume is saved, so Android keeps full volume instead of the desktop default. */
    val hasSavedVolume: Boolean get() = settings.hasKey("volume")
    var previousVolume by preference(0.7f)
    var persistentQueue by preference(true)
    var persistentPlaybackPosition by blobPreference(PersistentPlaybackPositionKey)
    var persistentPlaybackState by blobPreference("persistentPlaybackState")
    var promptContinuePlayback by preference(PromptContinuePlaybackKey, false)
    var pendingContinuePlayback by preference(PendingContinuePlaybackKey, false)
    var persistentShuffleAcrossQueues by preference(false)
    var useLegacyShuffle by preference(UseLegacyShuffleKey, true)
    var rememberShuffleAndRepeat by preference(true)
    var shuffleEnabled by preference(false)
    var androidAutoTargetPlaylist by preference(AndroidAutoTargetPlaylistKey, "")
    var androidAutoPinnedPlaylistsJson by preference(AndroidAutoPinnedPlaylistsJsonKey, "[]")
    var androidAutoPinnedPlaylistPlaybackMode by preference(
        AndroidAutoPinnedPlaylistPlaybackModeKey,
        AutomotivePinnedPlaylistPlaybackMode.IN_ORDER,
    )
    var crossfadeEnabled by preference(false)
    var crossfadeDuration by preference(10f)
    var crossfadeGapless by preference(true)
    var sponsorBlockEnabled by preference(SponsorBlockEnabledKey, false)
    var skipSilence by preference(false)
    var skipSilenceInstant by preference(false)
    var keepPausedOnSongChange by preference(KeepPausedOnSongChangeKey, false)
    var autoSkipNextOnError by preference(false)
    var seekExtraSeconds by preference(false)
    var seekDurationSeconds by preference(SeekDurationSecondsKey, 10)
    var pauseOnMute by preference(PauseOnMuteKey, false)
    var stopMusicOnTaskClear by preference(true)
    var githubNTransformEnabled by preference(GitHubNTransformEnabledKey, false)
    var githubNTransformConfigUrl by preference(
        GitHubNTransformConfigUrlKey,
        DEFAULT_FARADAY_PLAYER_CONFIG_URL,
    )

    var globalLyricsOffsetMs by preference(GlobalLyricsOffsetMsKey, 0)
    var lyricsAnimationStyle by preference("Apple")
    var lyricsTextSize by preference(36f)
    var lyricsLineSpacing by preference(1.3f)
    var lyricsPlusLighter by preference(LyricsPlusLighterKey, true)
    var lyricsNeutralColor by preference(LyricsNeutralColorKey, false)
    var subtitleTextSize by preference(16f)
    var lyricsClickToSeek by preference(true)
    var showLyricsOnPlayer by preference(ShowLyricsOnPlayerKey, false)
    var lyricsActiveByRealTiming by preference(LyricsActiveByRealTimingKey, false)
    var lyricsProgressiveBlur by preference(LyricsProgressiveBlurKey, false)

    var lyricsTextPosition by preference("CENTER")
    var activeLyricsTextPosition by preference(ActiveLyricsTextPositionKey, "CENTER")
    var lyricsShareImageStyle by preference(LyricsShareImageStyleKey, "")
    var lyricsAutoScroll by preference(true)
    var lyricsProviderOrder by preference(LyricsProvider.userConfigurable.joinToString(",") { it.name })
    var lyricsEnabledProviders by preference("")
    var preferWordSyncedLyrics by preference(PreferWordSyncedLyricsKey, false)
    var canvasContentOrder by preference(CanvasContentOrderKey, "YOUTUBE_MUSIC_VIDEO,SPOTIFY_CANVAS,APPLE_MUSIC")
    var canvasEnabledSources by preference(CanvasEnabledSourcesKey, "")
    var lyricsShowTranslations by preference(false)
    var lyricsAiTranslationEnabled by preference(false)
    var lyricsAiProvider by preference("OpenRouter")
    var lyricsAiApiKey by preference("")
    var lyricsOpenRouterOAuthKey by preference(LyricsOpenRouterOAuthKey, "")
    var lyricsOpenRouterOAuthSession by preference(LyricsOpenRouterOAuthSessionKey, "")

    var lyricsAiApiKeys by preference("")

    fun lyricsAiApiKeyFor(provider: String): String =
        lyricsAiApiKeys
            .takeIf(String::isNotBlank)
            ?.let {
                runCatching {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject[provider]
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()
            }
            ?: lyricsAiApiKey.takeIf { provider == lyricsAiProvider && lyricsAiApiKeys.isBlank() }.orEmpty()

    fun setLyricsAiApiKey(
        provider: String,
        key: String,
    ) {
        val current =
            lyricsAiApiKeys
                .takeIf(String::isNotBlank)
                ?.let { runCatching { Json.parseToJsonElement(it).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content } }.getOrNull() }
                ?: lyricsAiApiKey.takeIf(String::isNotBlank)?.let { mapOf(lyricsAiProvider to it) }.orEmpty()
        lyricsAiApiKeys =
            JsonObject((current + (provider to key)).filterValues(String::isNotBlank).mapValues { JsonPrimitive(it.value) }).toString()
    }

    fun lyricsAiCredentials(provider: String): Pair<String, String> =
        if (provider == "OpenRouter") {
            lyricsOpenRouterOAuthKey.takeIf(String::isNotBlank)?.let { it to "https://openrouter.ai/api/v1/chat/completions" }
                ?: (lyricsAiApiKeyFor(provider) to lyricsAiBaseUrl)
        } else {
            lyricsAiApiKeyFor(provider) to lyricsAiBaseUrl
        }

    var lyricsAiBaseUrl by preference("https://openrouter.ai/api/v1/chat/completions")
    var lyricsAiModel by preference(AiProviderPresets.DEFAULT_LYRICS_AI_MODEL)

    var lyricsAiDetectorModel by preference("")
    var lyricsAiTargetLanguage by preference("device")
    var lyricsAiTranslationMode by preference("Literal")
    var lyricsAiActivationMode by preference("Automatic")
    var lyricsAiSystemPrompt by preference("")
    var lyricsDeepLApiKey by preference("")
    var lyricsDeepLFormality by preference("default")
    var lyricsRomanizationEnabled by preference(false)
    var lyricsRomanizeAsMain by preference(false)
    var lyricsSyllableRomanizationEnabled by preference(true)
    var lyricsRomanizeCyrillicByLine by preference(false)
    var lyricsRomanizeLanguages by preference(
        "Japanese:true,Korean:true,Chinese:true,Arabic:true,Hindi:true,Punjabi:true,Thai:true,Russian:true,Ukrainian:true,Serbian:true,Bulgarian:true,Belarusian:true,Kyrgyz:true,Macedonian:true",
    )

    var respectAgentPositioning by preference(true)

    var lyricsHideStatusBarInFullscreen by preference(false)

    var pauseListenHistory by preference(false)
    var pauseSearchHistory by preference(false)
    var hideRecentSearches by preference(HideRecentSearchesKey, false)
    var searchHistory by preference("")
    var recentPlayedSearchSongsJson by preference(RecentPlayedSearchSongsJsonKey, "")
    var historyDuration by preference(30f)
    var historyPlaylistsEnabled by preference(HistoryPlaylistsEnabledKey, false)
    var ytmSync by preference(true)
    var queueYouTubeHistoryWhenOffline by preference(false)
    var queuedYouTubeHistoryJson by preference(QueuedYouTubeHistoryJsonKey, "")
    var downloadQueueJson by preference(DownloadQueueJsonKey, "")
    var defaultOpenTab by preference(DefaultOpenTabKey, "HOME")
    var showBottomNavHome by preference(ShowBottomNavHomeKey, true)
    var showBottomNavSearch by preference(ShowBottomNavSearchKey, true)
    var showBottomNavLibrary by preference(ShowBottomNavLibraryKey, true)
    var speedDialArtworkRefreshTimes by blobPreference(SpeedDialArtworkRefreshTimesKey)
    var speedDialSurprisePinned by preference(SpeedDialSurprisePinnedKey, false)

    var showLikedPlaylist by preference(true)
    var showDownloadedPlaylist by preference(true)
    var showTopPlaylist by preference(true)
    var showUploadedPlaylist by preference(true)
    var showCachedPlaylist by preference(true)

    var songCacheEnabled by preference(false)
    var maxSongCacheSize by preference(1024)
    var maxImageCacheSize by preference(512)
    var audioCacheLocation by preference("INTERNAL")
    var downloadLocation by preference("INTERNAL")
    var customDownloadPath by preference("")
    var autoDownloadOnLike by preference(false)
    var keepDownloadedPlaylistsJson by preference(KeepDownloadedPlaylistsJsonKey, "")
    var desktopStoppedDownloadIdsJson by preference(DesktopStoppedDownloadIdsJsonKey, "")
    var androidLastStorageAudit by preference(AndroidLastStorageAuditKey, 0L)
    var androidStorageAuditLocation by preference(AndroidStorageAuditLocationKey, "")
    var androidLastTrackedPlaylistSync by preference(AndroidLastTrackedPlaylistSyncKey, 0L)

    var checkForUpdates by preference(true)
    var updateNotifications by preference(true)
    var lastUpdateCheckTimestamp by preference(0L)
    var lastUpdateCheckAttemptTimestamp by preference(0L)
    var lastRunAppVersion by preference("")
    var cachedAvailableUpdateJson by preference("")
    var cachedUpdateReleasesJson by preference("")
    var updateCacheSource by preference("")
    var updatePromptVersion by preference("")
    var launchesSinceUpdatePrompt by preference(0)
    var lastNotifiedUpdateVersion by preference("")
    var pendingUpdateReleaseJson by preference("")
    var enableLogging by preference(false)
    var sentryEnabled by preference(SentryEnabledKey, false)
    var blockScreenshots by preference(false)
    var keepScreenOnMode by preference(KeepScreenOnMode.NEVER)
    var enableDiscordIntegration by preference(false)
    var discordRpcShowArtistIcon by preference(true)
    var discordAdvancedMode by preference(false)
    var discordActivityType by preference("2")
    var discordActivityName by preference("")
    var discordStateTemplate by preference("{artist.name}")
    var discordDetailsTemplate by preference("{song.name}")
    var discordButton1Enabled by preference(true)
    var discordButton1Text by preference("Listen on YouTube Music")
    var discordButton1Url by preference("{song.url}")
    var discordButton2Enabled by preference(true)
    var discordButton2Text by preference("Visit Metrolist")
    var discordButton2Url by preference("https://github.com/MetrolistGroup/Metrolist")
    var discordUsername by preference("")
    var discordName by preference("")
    var discordAvatar by preference("")
    var discordPendingAuthSession by preference(DiscordPendingAuthSessionKey, "")
    var discordAccessToken by preference("")
    var discordRefreshToken by preference("")
    var discordExpiresAt by preference(0L)
    var discordDeviceVendorId by preference("")
    var discordClientUuid by preference("")

    var lastFmUsername by preference("")
    var lastFmSession by preference("")
    var lastFmEnabled by preference(true)
    var lastFmUseNowPlaying by preference(true)
    var lastFmShowFriends by preference(true)
    var lastFmActivityEnabled by preference(true)
    var lastFmFriendsCacheUser by preference("")
    var lastFmFriendsCacheJson by preference("")
    var lastFmDelayPercent by preference(0.5f)
    var lastFmMinDuration by preference(30)
    var lastFmMaxDelay by preference(180)
    var artistBannerSource by preference("YOUTUBE")
    var artistIconSource by preference("YOUTUBE")
    var artistCustomBannersJson by preference("{}")
    var lastFmArtistCacheJson by preference(LastFmArtistCacheJsonKey, "{}")
    var lastFmPhotoCacheJson by preference(LastFmPhotoCacheJsonKey, "{}")

    var listenBrainzToken by preference("")
    var listenBrainzUsername by preference("")
    var listenBrainzEnabled by preference(false)
    var listenBrainzUseNowPlaying by preference(true)
    var pendingScrobblesJson by preference("[]")
    var corruptPendingScrobblesJson by preference("")
    var systemInDarkTheme by preference(false)
    var listenTogetherEnabled by preference(ListenTogetherEnabledKey, true)
    var listenTogetherDesktopSession by preference("")
    var listenTogetherUsername by preference("")
    var listenTogetherServerUrl by preference(DEFAULT_LISTEN_TOGETHER_SERVER_URL)
    var listenTogetherCustomServers by preference("")
    var listenTogetherServerMetadata by preference(ListenTogetherServerMetadataKey, "{}")
    var listenTogetherAutoApproveJoinRequests by preference(false)
    var listenTogetherAutoApproveSongSuggestions by preference(false)
    var listenTogetherSyncHostVolume by preference(true)
    var listenTogetherBlockedUsers by preference("")

    var deviceConnectEnabled by preference(false)
    var deviceConnectDeviceId by preference("")
    var deviceConnectDeviceName by preference("")
    var deviceConnectPairedDevices by preference("")
    var deviceConnectDefaultDeviceId by preference(DeviceConnectDefaultDeviceIdKey, "")

    var swipeThumbnail by preference(false)
    var swipeSensitivity by preference(73f)
    var swipeToSong by preference(SwipeToSongKey, false)
    var swipeToRemoveSong by preference(SwipeToRemoveSongKey, false)
    var autoLoadMore by preference(true)
    var disableLoadMoreWhenRepeatAll by preference(false)
    var preventDuplicateTracksInQueue by preference(false)
    var innerTubeCookie by preference("")
    var isQueueLocked by preference(true)
    var playerConfigRemoteJson by preference("")
    var playerConfigRemoteFetchedAt by preference(0L)
    var playerConfigRemoteSourceUrl by preference(PlayerConfigRemoteSourceUrlKey, "")
    var playerConfigRemoteEtag by preference(PlayerConfigRemoteEtagKey, "")
    var devtoolsUnlocked by preference(DevtoolsUnlockedKey, false)
    var devtoolsPlaybackClient by preference(DevtoolsPlaybackClientKey, "AUTO")
    var devtoolsBenchmarkClients by preference(DevtoolsBenchmarkClientsKey, "")
    var desktopRenderBackend by preference(DesktopRenderBackendKey, "AUTO")

    /** Disabling Devtools clears playback overrides because playback reads them even while Devtools are hidden. */
    fun setDevtoolsEnabled(enabled: Boolean) {
        devtoolsUnlocked = enabled
        if (!enabled) {
            devtoolsPlaybackClient = "AUTO"
            devtoolsBenchmarkClients = ""
        }
    }

    var playbackClientHealthJson by blobPreference(PlaybackClientHealthJsonKey)

    var enableAnimatedCovers by preference(false)
    var disableAnimatedArtworkOnMobileData by preference(DisableAnimatedArtworkOnMobileDataKey, false)

    fun isAnimatedArtworkAllowedOnNetwork(network: PlaybackNetworkState = PlaybackNetworkStateStore.current): Boolean =
        !disableAnimatedArtworkOnMobileData || !network.isCellular

    var enableAppleMusicArtistImages by preference(AppleMusicArtistImagesKey, false)
    var enableAppleMusicArtistVideos by preference(AppleMusicArtistVideosKey, enableAppleMusicArtistImages)
    var animatedCoverResolution by preference(AnimatedCoverResolutionKey, AnimatedCoverResolution.MEDIUM_720)
    var appleMusicArtistVideoResolution by preference(AppleMusicArtistVideoResolutionKey, AnimatedCoverResolution.MEDIUM_720)
    var animatedCoverCacheJson by preference(AnimatedCoverCacheJsonKey, "{}")
    var appleMusicArtistCacheJson by preference(AppleMusicArtistCacheJsonKey, "{}")
    var animatedArtworkCacheSize by preference(AnimatedArtworkCacheSizeKey, 2048)

    var musicVideoCacheJson by preference(MusicVideoCacheJsonKey, "{}")
    var musicVideoPlaybackEnabled by preference(MusicVideoPlaybackEnabledKey, true)
    var musicVideoQuality by preference(MusicVideoQualityKey, MusicVideoQuality.AUTO)
    var youtubeCanvasQuality by preference(YouTubeCanvasQualityKey, MusicVideoQuality.P480)
    var youtubeCanvasMode by preference(YouTubeCanvasModeKey, YouTubeCanvasMode.SHORT_CLIPS)

    var libraryViewType by preference(LibraryViewType.LIST)
    var libraryFilter by preference(
        null,
        LibraryFilterType.DEFAULT,
        SettingType.INT,
        { name, fallback ->
            val stored = settings.getInt(name, fallback.storedValue)
            val filter = LibraryFilterType.fromStoredValue(stored) ?: fallback
            if (stored == 4) settings.putInt(name, filter.storedValue)
            filter
        },
        { name, value -> settings.putInt(name, value.storedValue) },
    )
    var libraryContentSort by preference(LibraryContentSort.RECENTLY_ADDED)
    var libraryManualOrder by preference(LibraryManualOrderKey, "")
    var playlistPickerSort by preference(PlaylistPickerSortKey, PlaylistPickerSort.LIBRARY)
    var playlistPickerSortDirection by preference(PlaylistPickerSortDirectionKey, SortDirection.ASCENDING)
    var playlistSongSort by preference(PlaylistSongSort.ORIGINAL)
    var playlistSongSortDirection by preference(SortDirection.ASCENDING)
    var likedSongSort by preference(LikedSongSortKey, PlaylistSongSort.DATE_ADDED)
    var likedSongSortDirection by preference(LikedSongSortDirectionKey, SortDirection.DESCENDING)
    var cachedCollectionSort by preference(CachedCollectionSortKey, CollectionSort.NEWEST)
    var cachedCollectionSortDirection by preference(CachedCollectionSortDirectionKey, SortDirection.DESCENDING)
    var downloadedCollectionSort by preference(DownloadedCollectionSortKey, CollectionSort.NEWEST)
    var downloadedCollectionSortDirection by preference(DownloadedCollectionSortDirectionKey, SortDirection.DESCENDING)
    private var collectionCoversJson by preference(CollectionCoversKey, "{}")
    val collectionCovers: Map<String, String> by derivedStateOf {
        runCatching { Json.decodeFromString<Map<String, String>>(collectionCoversJson) }.getOrDefault(emptyMap())
    }

    fun setCollectionCover(
        type: String,
        path: String?,
    ) {
        require(type.isNotBlank())
        require(path == null || path.isNotBlank())
        collectionCoversJson = Json.encodeToString(if (path == null) collectionCovers - type else collectionCovers + (type to path))
    }

    private var playlistSongSortingsJson by preference(PlaylistSongSortingsKey, "{}")
    private var playlistCollectionSortingsJson by preference(PlaylistCollectionSortingsKey, "{}")
    var visibleAutoPlaylists by preference("liked,downloaded,top,uploaded,cached")
    var likedSongsJson by preference("[]")
    var likedSongsOrderCustomized by preference(LikedSongsOrderCustomizedKey, false)
    var likedSongsOrderAccount by preference(LikedSongsOrderAccountKey, "")
    var likedSongsCustomOrdersJson by preference(LikedSongsCustomOrdersJsonKey, "{}")
    var likedSongsRecencyJson by preference(LikedSongsRecencyJsonKey, "[]")

    var pendingSongVotesJson by preference(PendingSongVotesJsonKey, "{}")

    var likedSongIds by preference("")
    var dislikedSongIds by preference("")
    var blockedSongIds by preference("")
    var blockedSongsJson by preference("[]")
    var blockedArtistIds by preference("")
    var blockedArtistsJson by preference("[]")
    var lastLibraryRefreshEpoch by preference(LastLibraryRefreshEpochKey, 0L)
    var lastLibraryRefreshAccount by preference(LastLibraryRefreshAccountKey, "")
    var lastRemoteLibraryItemKeysJson by preference(LastRemoteLibraryItemKeysJsonKey, "[]")
    var pendingRemoteLibraryRemovalKeysJson by preference(PendingRemoteLibraryRemovalKeysJsonKey, "[]")
    var deletedPlaylistIdsJson by preference(DeletedPlaylistIdsJsonKey, "[]")
    var libraryGridView by preference(true)
    var gridItemSize by preference(GridItemSizeKey, GridItemSize.BIG)
    private var storedLibraryGridColumns by preference(LibraryGridColumnsKey, 3)
    var libraryGridColumns: Int
        get() = storedLibraryGridColumns.coerceIn(LibraryGridColumnsRange)
        set(value) {
            storedLibraryGridColumns = value.coerceIn(LibraryGridColumnsRange)
        }
    private var storedLibraryPinchColumns by preference(LibraryPinchColumnsKey, 0)

    var libraryPinchColumns: Int
        get() = storedLibraryPinchColumns.takeIf { it in LibraryGridColumnsRange } ?: 0
        set(value) {
            storedLibraryPinchColumns = value.takeIf { it in LibraryGridColumnsRange } ?: 0
        }
    var librarySortDirection by preference(
        LibrarySortDirectionKey,
        if (libraryContentSort in setOf(LibraryContentSort.RECENTLY_ADDED, LibraryContentSort.LAST_UPDATED)) {
            SortDirection.DESCENDING
        } else {
            SortDirection.ASCENDING
        },
    )
    private var librarySortingByFilterJson by preference(LibrarySortingByFilterKey, "{}")

    fun playlistSongSorting(
        playlistKey: String,
        defaultSort: PlaylistSongSort,
        defaultDirection: SortDirection,
    ): SavedPlaylistSongSorting =
        if (playlistKey.startsWith("playlist:") || playlistKey.startsWith("local:")) {
            SavedPlaylistSongSorting(playlistSongSort, playlistSongSortDirection)
        } else {
            playlistSongSortings()[playlistKey] ?: SavedPlaylistSongSorting(defaultSort, defaultDirection)
        }

    fun setPlaylistSongSorting(
        playlistKey: String,
        sorting: SavedPlaylistSongSorting,
    ) {
        if (playlistKey.startsWith("playlist:") || playlistKey.startsWith("local:")) {
            playlistSongSort = sorting.sort
            playlistSongSortDirection = sorting.direction
        } else {
            playlistSongSortingsJson = Json.encodeToString(playlistSongSortings() + (playlistKey to sorting))
        }
    }

    fun playlistCollectionSorting(
        playlistKey: String,
        defaultSort: CollectionSort,
        defaultDirection: SortDirection,
    ): SavedPlaylistCollectionSorting =
        playlistCollectionSortings()[playlistKey] ?: SavedPlaylistCollectionSorting(defaultSort, defaultDirection)

    fun setPlaylistCollectionSorting(
        playlistKey: String,
        sorting: SavedPlaylistCollectionSorting,
    ) {
        playlistCollectionSortingsJson = Json.encodeToString(playlistCollectionSortings() + (playlistKey to sorting))
    }

    private fun playlistSongSortings(): Map<String, SavedPlaylistSongSorting> =
        runCatching { Json.decodeFromString<Map<String, SavedPlaylistSongSorting>>(playlistSongSortingsJson) }
            .getOrDefault(emptyMap())

    private fun playlistCollectionSortings(): Map<String, SavedPlaylistCollectionSorting> =
        runCatching { Json.decodeFromString<Map<String, SavedPlaylistCollectionSorting>>(playlistCollectionSortingsJson) }
            .getOrDefault(emptyMap())

    fun librarySorting(filter: LibraryFilterType): LibrarySorting =
        librarySortingByFilter()[(filter.takeUnless { it == LibraryFilterType.DEFAULT } ?: LibraryFilterType.ALL).name]
            ?: LibrarySorting(libraryContentSort, librarySortDirection)

    fun setLibrarySorting(
        filter: LibraryFilterType,
        sorting: LibrarySorting,
    ) {
        val key = filter.takeUnless { it == LibraryFilterType.DEFAULT } ?: LibraryFilterType.ALL
        librarySortingByFilterJson = Json.encodeToString(librarySortingByFilter() + (key.name to sorting))
    }

    private fun librarySortingByFilter(): Map<String, LibrarySorting> =
        runCatching { Json.decodeFromString<Map<String, LibrarySorting>>(librarySortingByFilterJson) }
            .getOrDefault(emptyMap())

    var hiddenLibraryPlaylistsJson by preference("[]")
    var hiddenHomeItemsJson by preference(HiddenHomeItemsJsonKey, "[]")

    var homeSectionVisibility by preference("")
    var lastHomePageJson by blobPreference("lastHomePageJson")

    var enableSystemWindowFrame by preference(false)
    var waylandDefaultFrameApplied by preference(false)
    var disableWaylandScaleFix by preference(false)
    var macDefaultFrameApplied by preference(false)
    var desktopOnboardingComplete by preference(false)
    var desktopOnboardingInitialized by preference(false)
    var desktopPendingOnboardingSentryConsent by preference("")
    var iosOnboardingComplete by preference(IosOnboardingCompleteKey, false)
    var sidebarNavItems by preference(
        listOf(
            NavItem(NavigationId.HOME),
            NavItem(NavigationId.LIBRARY),
            NavItem(NavigationId.HISTORY),
            NavItem(NavigationId.STATISTICS),
            NavItem(NavigationId.LISTEN_TOGETHER),
        ).joinToString(",") { "${it.id}:${it.visible}" },
    )
    var desktopWindowPosition by preference(DesktopWindowPositionKey, "")
    var desktopWindowPlacement by preference(DesktopWindowPlacementKey, "")
    var savedWidth by preference(1080)
    var savedHeight by preference(720)

    // Read these non-reactive payloads on demand to avoid retaining a second copy in Compose state.
    private fun blobPreference(key: String): ReadWriteProperty<Settings, String> {
        settingTypes[key] = SettingType.STRING
        return object : ReadWriteProperty<Settings, String> {
            override fun getValue(
                thisRef: Settings,
                property: KProperty<*>,
            ): String = settings.getString(key, "")

            override fun setValue(
                thisRef: Settings,
                property: KProperty<*>,
                value: String,
            ) {
                settings.putString(key, value)
            }
        }
    }

    private fun preference(defaultValue: String) = preference(null, defaultValue)

    private fun preference(
        key: String?,
        defaultValue: String,
    ) = preference(key, defaultValue, SettingType.STRING, settings::get, settings::set)

    private fun preference(defaultValue: Boolean) = preference(null, defaultValue)

    private fun preference(
        key: String?,
        defaultValue: Boolean,
    ) = preference(key, defaultValue, SettingType.BOOLEAN, settings::get, settings::set)

    private fun preference(defaultValue: Int) = preference(null, defaultValue)

    private fun preference(
        key: String?,
        defaultValue: Int,
    ) = preference(key, defaultValue, SettingType.INT, settings::get, settings::set)

    private fun preference(defaultValue: Long) = preference(null, defaultValue)

    private fun preference(
        key: String?,
        defaultValue: Long,
    ) = preference(key, defaultValue, SettingType.LONG, settings::get, settings::set)

    private fun preference(defaultValue: Float) = preference(null, defaultValue)

    private fun preference(
        key: String?,
        defaultValue: Float,
    ) = preference(key, defaultValue, SettingType.FLOAT, settings::get, settings::set)

    private inline fun <reified E : Enum<E>> preference(defaultValue: E) = preference(null, defaultValue)

    private inline fun <reified E : Enum<E>> preference(
        key: String?,
        defaultValue: E,
    ) = preference(
        key,
        defaultValue,
        SettingType.INT,
        { name, fallback -> enumEntries<E>().getOrElse(settings.getInt(name, fallback.ordinal)) { fallback } },
        { name, value -> settings.putInt(name, value.ordinal) },
    )

    @Suppress("UNCHECKED_CAST")
    fun <T> defaultValue(property: KProperty0<T>): T = preferenceDefaults.getValue(property.name) as T

    private fun <T> preference(
        key: String?,
        defaultValue: T,
        type: SettingType,
        getter: (String, T) -> T,
        setter: (String, T) -> Unit,
    ) = PropertyDelegateProvider<Settings, Preference<T>> { _, property ->
        val name = key ?: property.name
        settingTypes[name] = type
        preferenceDefaults[property.name] = defaultValue
        Preference(name, defaultValue, getter, setter).also {
            preferenceRefreshers[name] = it::refresh
        }
    }

    // Adapted from https://github.com/zt64/tau/blob/main/core/src/main/kotlin/dev/zt64/tau/domain/manager/base/BasePreferencesManager.kt
    // Copyright 2025 zt and contributors, SPDX-License-Identifier: GPL-3.0-only
    private class Preference<T>(
        private val key: String,
        private val defaultValue: T,
        private val getter: (String, T) -> T,
        private val setter: (String, T) -> Unit,
    ) {
        private val lock = SynchronizedObject()

        private var value by mutableStateOf(
            lazy(LazyThreadSafetyMode.NONE) { getter(key, defaultValue) },
            object : SnapshotMutationPolicy<Lazy<T>> {
                override fun equivalent(
                    a: Lazy<T>,
                    b: Lazy<T>,
                ): Boolean = a === b || (a.isInitialized() && b.isInitialized() && a.value == b.value)
            },
        )

        fun refresh() {
            synchronized(lock) {
                if (value.isInitialized()) {
                    val restored = getter(key, defaultValue)
                    if (value.value != restored) value = lazyOf(restored)
                }
            }
        }

        operator fun getValue(
            thisRef: Settings,
            property: KProperty<*>,
        ): T = synchronized(lock) { value.value }

        operator fun setValue(
            thisRef: Settings,
            property: KProperty<*>,
            value: T,
        ) {
            synchronized(lock) {
                if (this.value.value == value) return
                setter(key, value)
                this.value = lazyOf(value)
            }
        }
    }

    fun exportBackupSettings(): PortableSettings = exportSettings(backupExcludedKeys)

    fun flush() {
        (settings as? FlushableSettings)?.flush()
    }

    private fun exportSettings(excludedKeys: Set<String>): PortableSettings =
        PortableSettings(
            entries =
                settings.keys
                    .asSequence()
                    .filterNot { it in excludedKeys }
                    .mapNotNull { key ->
                        val type = settingTypes[key] ?: return@mapNotNull null
                        val value =
                            when (type) {
                                SettingType.STRING -> settings.getStringOrNull(key)
                                SettingType.BOOLEAN -> settings.getBooleanOrNull(key)?.toString()
                                SettingType.INT -> settings.getIntOrNull(key)?.toString()
                                SettingType.LONG -> settings.getLongOrNull(key)?.toString()
                                SettingType.FLOAT -> settings.getFloatOrNull(key)?.toString()
                            }
                        value?.let { PortableSetting(key, type, it) }
                    }.sortedBy(PortableSetting::key)
                    .toList(),
        )

    fun importBackupSettings(backup: PortableSettings) {
        validatePortableSettings(backup)
        importSettings(backup, backupExcludedKeys, backupCredentialKeys + ArtistNameAliasesJsonKey)
    }

    fun restoreBackupSettingsSnapshot(snapshot: PortableSettings) {
        validatePortableSettings(snapshot)
        val snapshotKeys = snapshot.entries.mapTo(mutableSetOf(), PortableSetting::key)
        val removedKeys = settings.keys.filterTo(mutableSetOf()) { it in settingTypes && it !in backupExcludedKeys && it !in snapshotKeys }
        importSettings(snapshot, backupExcludedKeys, removedKeys)
    }

    private fun importSettings(
        backup: PortableSettings,
        excludedKeys: Set<String>,
        removedKeys: Set<String>,
    ) {
        val entries =
            backup.entries
                .filterNot {
                    it.key in excludedKeys || it.key in retiredPortableSettingTypes
                }.associateBy { it.key }
                .toMutableMap()
        if ("keepScreenOnMode" !in entries) {
            backup.entries.firstOrNull { it.key == "keepScreenOn" }?.let { legacy ->
                val mode = if (legacy.value.toBooleanStrict()) KeepScreenOnMode.PLAYER_AND_LYRICS else KeepScreenOnMode.NEVER
                entries["keepScreenOnMode"] = PortableSetting("keepScreenOnMode", SettingType.INT, mode.ordinal.toString())
            }
        }
        val legacyCanvasLimit =
            backup.entries
                .firstOrNull { it.key == CanvasCacheSizeKey }
                ?.value
                ?.toInt()
        val legacyCoverLimit =
            backup.entries
                .firstOrNull { it.key == AnimatedCoverCacheSizeKey }
                ?.value
                ?.toInt()
        if (backup.entries.none { it.key == AnimatedArtworkCacheSizeKey } && (legacyCanvasLimit != null || legacyCoverLimit != null)) {
            entries[AnimatedArtworkCacheSizeKey] =
                PortableSetting(
                    AnimatedArtworkCacheSizeKey,
                    SettingType.INT,
                    combinedAnimatedArtworkCacheLimit(legacyCanvasLimit, legacyCoverLimit).toString(),
                )
        }
        if (backup.entries.any { it.key == "pureBlack" && it.value == "true" }) {
            entries["themeMode"] = PortableSetting("themeMode", SettingType.INT, ThemeMode.PURE_BLACK.ordinal.toString())
        }
        settings.applyBatch(entries.values.toList(), removedKeys)
        flush()
        preferenceRefreshers.values.forEach { it() }
    }

    internal fun applyStringBatch(values: Map<String, String>) {
        settings.applyBatch(values.map { (key, value) -> PortableSetting(key, SettingType.STRING, value) }, emptySet())
        values.keys.forEach { preferenceRefreshers[it]?.invoke() }
    }

    var credentialsPending by mutableStateOf((settings as? DeferredSecretMigrationSettings)?.secretRecoveryPending == true)
        private set

    internal fun refreshStoredValues() {
        preferenceRefreshers.values.forEach { it() }
        credentialsPending = (settings as? DeferredSecretMigrationSettings)?.secretRecoveryPending == true
    }

    internal fun finishPendingSecretMigrations() {
        migrateInitialSettings()
    }

    fun validatePortableSettings(backup: PortableSettings) {
        require(backup.formatVersion in 1..PORTABLE_SETTINGS_VERSION) { "Unsupported backup settings version" }
        val entries = backup.entries
        require(entries.map(PortableSetting::key).distinct().size == entries.size) { "Backup contains duplicate settings" }
        entries.forEach { entry ->
            require((settingTypes[entry.key] ?: retiredPortableSettingTypes[entry.key]) == entry.type) {
                "Invalid backup setting type for ${entry.key}"
            }
            if (entry.key == ArtistNameAliasesJsonKey) ArtistNameAliases.deserialize(entry.value)
            require(entry.isValidValue()) { "Invalid backup setting value for ${entry.key}" }
            if (entry.key == "maxImageCacheSize") {
                require(entry.value.toInt() >= 0) { "Invalid backup setting value for ${entry.key}" }
            }
        }
    }

    fun portableSettingsFromLegacy(values: Map<String, Any>): PortableSettings =
        PortableSettings(
            entries =
                values
                    .toMutableMap()
                    .also { legacy ->
                        if ("keepScreenOnMode" !in legacy) {
                            legacy.remove("keepScreenOn")?.let(::legacyBoolean)?.let { enabled ->
                                val mode = if (enabled) KeepScreenOnMode.PLAYER_AND_LYRICS else KeepScreenOnMode.NEVER
                                legacy["keepScreenOnMode"] = mode.ordinal
                            }
                        }
                        val canvasLimit = legacy.remove(CanvasCacheSizeKey)?.toString()?.toIntOrNull()
                        val coverLimit = legacy.remove(AnimatedCoverCacheSizeKey)?.toString()?.toIntOrNull()
                        if (AnimatedArtworkCacheSizeKey !in legacy && (canvasLimit != null || coverLimit != null)) {
                            legacy[AnimatedArtworkCacheSizeKey] = combinedAnimatedArtworkCacheLimit(canvasLimit, coverLimit)
                        }
                        if (legacy.remove("pureBlack")?.let(::legacyBoolean) == true) {
                            legacy["themeMode"] = ThemeMode.PURE_BLACK.name
                        }
                        if (!legacy.containsKey("sliderStyle")) {
                            legacy["squigglySlider"]?.let(::legacyBoolean)?.let { enabled ->
                                legacy["sliderStyle"] = if (enabled) SliderStyle.SQUIGGLY.name else SliderStyle.DEFAULT.name
                            }
                        }
                        if (!legacy.containsKey("playerType")) {
                            legacy["useNewPlayerDesign"]?.let(::legacyBoolean)?.let { enabled ->
                                legacy["playerType"] = if (enabled) PlayerType.MODERN.name else PlayerType.VINTAGE.name
                            }
                        }
                        if (!legacy.containsKey("miniPlayerType")) {
                            legacy["useNewMiniPlayerDesign"]?.let(::legacyBoolean)?.let { enabled ->
                                legacy["miniPlayerType"] = if (enabled) MiniPlayerType.MODERN.name else MiniPlayerType.VINTAGE.name
                            }
                        }
                        if (legacy.remove("pureBlackMiniPlayer")?.let(::legacyBoolean) == true &&
                            legacy["miniPlayerType"]?.toString() == MiniPlayerType.VINTAGE.name
                        ) {
                            legacy["miniPlayerBackgroundStyle"] = "SOLID_BLACK"
                        }
                        val similar = legacy.remove("similarContent")?.let { legacyBoolean(it) }
                        if (similar != null) {
                            val autoLoadMore = legacy["autoLoadMore"]?.let(::legacyBoolean) ?: true
                            legacy["autoLoadMore"] = autoLoadMore && similar
                        }
                        val providerFlags =
                            linkedMapOf(
                                "enableBetterLyrics" to "BETTERLYRICS",
                                "enableLrclib" to "LRCLIB",
                                "enableKugou" to "KUGOU",
                                "enablePaxsenix" to "PAXSENIX",
                            )
                        if (!legacy.containsKey("lyricsEnabledProviders")) {
                            val presentFlags = providerFlags.keys.filter(legacy::containsKey)
                            val flags =
                                presentFlags
                                    .mapNotNull { key ->
                                        legacy[key]?.let {
                                            legacyBoolean(it)?.let { enabled ->
                                                key to
                                                    enabled
                                            }
                                        }
                                    }.toMap()
                            if (flags.isNotEmpty()) {
                                legacy["lyricsEnabledProviders"] =
                                    buildList {
                                        providerFlags.forEach { (key, provider) ->
                                            if (flags[key] != false) add(provider)
                                        }
                                        add("UNISON")
                                    }.joinToString(",")
                            }
                        }
                    }.toList()
                    .sortedWith(compareBy({ legacyAliases[it.first] != null }, { it.first }))
                    .mapNotNull { (sourceKey, rawValue) ->
                        val key = legacyAliases[sourceKey] ?: sourceKey
                        if (key in removedLegacyKeys) return@mapNotNull null
                        val type = settingTypes[key] ?: return@mapNotNull null
                        val value = legacyValue(key, type, rawValue) ?: return@mapNotNull null
                        PortableSetting(key, type, value)
                    }.distinctBy(PortableSetting::key)
                    .sortedBy(PortableSetting::key),
        )

    internal fun getHomeSectionVisibilityMap(): Map<String, Boolean> =
        if (homeSectionVisibility.trimStart().startsWith("{")) {
            runCatching { Json.decodeFromString<Map<String, Boolean>>(homeSectionVisibility) }.getOrDefault(emptyMap())
        } else {
            Regex("(?:^|,)(.*?):(true|false)(?=,|$)")
                .findAll(homeSectionVisibility)
                .associate { it.groupValues[1] to (it.groupValues[2] == "true") }
        }

    fun shouldShowHomeSection(
        sectionKey: String,
        legacyTitle: String = sectionKey,
    ): Boolean {
        val current = getHomeSectionVisibilityMap()
        current[sectionKey]?.let { return it }
        val legacy = current[legacyTitle] ?: return true
        if (sectionKey != legacyTitle) {
            homeSectionVisibility = Json.encodeToString(current + (sectionKey to legacy))
        }
        return legacy
    }

    fun toggleHomeSectionVisibility(
        sectionTitle: String,
        visible: Boolean,
    ) {
        homeSectionVisibility = Json.encodeToString(getHomeSectionVisibilityMap() + (sectionTitle to visible))
    }

    fun getBlockedSongIdSet(): Set<String> =
        blockedSongIds
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun isSongBlocked(songId: String): Boolean = songId.isNotBlank() && getBlockedSongIdSet().contains(songId)

    fun setSongBlocked(
        songId: String,
        blocked: Boolean,
    ) {
        if (songId.isBlank()) return
        val updated = getBlockedSongIdSet().toMutableSet()
        if (blocked) {
            updated.add(songId)
        } else {
            updated.remove(songId)
        }
        blockedSongIds = updated.joinToString(",")
    }

    fun getCurrentThemeIsDark(): Boolean =
        when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK, ThemeMode.PURE_BLACK -> true
            ThemeMode.AUTO -> systemInDarkTheme
        }

    fun isPureBlackTheme(darkTheme: Boolean = getCurrentThemeIsDark()): Boolean =
        darkTheme && (themeMode == ThemeMode.PURE_BLACK || (themeMode == ThemeMode.AUTO && autoPureBlack))

    companion object {
        private val backupCredentialKeys =
            secureSettingsKeys -
                setOf(
                    "listenTogetherPairedRoomCode",
                    "deviceConnectPairedDevices",
                    "spotifySession",
                    LyricsOpenRouterOAuthSessionKey,
                    DiscordPendingAuthSessionKey,
                )
        private val nonPortableKeys =
            secureSettingsKeys +
                setOf(
                    "aiSlopBlocklistCache",
                    "aiSlopBlocklistUpdatedAt",
                    SpeedDialArtworkRefreshTimesKey,
                    "customDownloadPath",
                    "knownUploadIdsJson",
                    "hasNewUploads",
                    "desktopOnboardingComplete",
                    "desktopOnboardingInitialized",
                    "desktopPendingOnboardingSentryConsent",
                    IosOnboardingCompleteKey,
                    SentryEnabledKey,
                    IosCustomFontFileKey,
                    CollectionCoversKey,
                    IosCustomFontNameKey,
                    IosCustomFontRevisionKey,
                    "deviceConnectEnabled",
                    DeviceConnectExperimentalEnabledKey,
                    "deviceConnectDeviceId",
                    "deviceConnectLastUsedDeviceId",
                    "listenTogetherAutoJoinPairedRoom",
                    "cachedAvailableUpdateJson",
                    "cachedUpdateReleasesJson",
                    "pendingUpdateReleaseJson",
                    "lastUpdateCheckTime",
                    DevtoolsUnlockedKey,
                    DevtoolsPlaybackClientKey,
                    DevtoolsBenchmarkClientsKey,
                    DesktopRenderBackendKey,
                    "playbackClientHealthJson",
                    "appleMusicArtistCacheJson",
                    LastFmArtistCacheJsonKey,
                    LastFmPhotoCacheJsonKey,
                )
        private val backupExcludedKeys = nonPortableKeys - backupCredentialKeys
        private const val PORTABLE_SETTINGS_VERSION = 1
        const val DEFAULT_LISTEN_TOGETHER_SERVER_URL = "wss://metroserverx.meowery.eu/ws"
        const val DEFAULT_FARADAY_PLAYER_CONFIG_URL =
            "https://raw.githubusercontent.com/MetrolistGroup/faraday/master/registry/player_configs.json"

        private val defaultShared by lazy { Settings(defaultKmpSettings()) }
        private var sharedOverride: Settings? = null

        val shared: Settings
            get() = sharedOverride ?: defaultShared

        fun replaceSharedForTest(settings: Settings?) {
            sharedOverride = settings
        }

        fun sharedOverrideForTest(): Settings? = sharedOverride
    }
}

@Serializable
data class PortableSettings(
    val formatVersion: Int = 1,
    val entries: List<PortableSetting>,
)

@Serializable
data class PortableSetting(
    val key: String,
    val type: SettingType,
    val value: String,
)

@Serializable
enum class SettingType { STRING, BOOLEAN, INT, LONG, FLOAT }

internal val retiredPortableSettingTypes =
    mapOf(
        CanvasCacheSizeKey to SettingType.INT,
        AnimatedCoverCacheSizeKey to SettingType.INT,
        "keepScreenOn" to SettingType.BOOLEAN,
        "useLoginForBrowse" to SettingType.BOOLEAN,
        "enableAppleMusicArtwork" to SettingType.BOOLEAN,
        "appleMusicArtworkCacheJson" to SettingType.STRING,
        "showArtistPhotos" to SettingType.BOOLEAN,
        "lastFmPhotoAutoCycle" to SettingType.BOOLEAN,
        "lastFmPhotoCycleInterval" to SettingType.FLOAT,
        "artistLastFmPhotosJson" to SettingType.STRING,
        "showArtistSubscriberCount" to SettingType.BOOLEAN,
        "showMonthlyListeners" to SettingType.BOOLEAN,
        "lastFmShowOnHome" to SettingType.BOOLEAN,
        "lastFmArtistAvatarsJson" to SettingType.STRING,
        "enableMusicVideos" to SettingType.BOOLEAN,
        "autoPlayMusicVideos" to SettingType.BOOLEAN,
        "resumeOnBluetoothConnect" to SettingType.BOOLEAN,
        "pureBlack" to SettingType.BOOLEAN,
        "desktopSidebarExpanded" to SettingType.BOOLEAN,
        "discordRpcActivityType" to SettingType.STRING,
        "discordRpcAppId" to SettingType.STRING,
        "discordRpcButton1Text" to SettingType.STRING,
        "discordRpcButton1Url" to SettingType.STRING,
        "discordRpcButton2Text" to SettingType.STRING,
        "discordRpcButton2Url" to SettingType.STRING,
        "highQualityJapaneseRomanization" to SettingType.BOOLEAN,
        "japaneseDictionaryStatus" to SettingType.STRING,
        "lastLibraryItemsJson" to SettingType.STRING,
        "handleAudioFocus" to SettingType.BOOLEAN,
        "discordRpcShowIdle" to SettingType.BOOLEAN,
        "discordRpcUseDetails" to SettingType.BOOLEAN,
        "discordRpcShowButtons" to SettingType.BOOLEAN,
        "pinnedAppItemIds" to SettingType.STRING,
        "historyActionPlacement" to SettingType.INT,
        "listenTogetherActionPlacement" to SettingType.INT,
        "statisticsActionPlacement" to SettingType.INT,
        "discordRpcButton1Visible" to SettingType.BOOLEAN,
        "discordRpcButton2Visible" to SettingType.BOOLEAN,
        "topListLength" to SettingType.FLOAT,
        "shufflePlaylistFirst" to SettingType.BOOLEAN,
        "librarySortMode" to SettingType.INT,
        "editPlaylistViewType" to SettingType.INT,
        "sideMenuWelcomeSeen" to SettingType.BOOLEAN,
        "androidMiniPlayerVisible" to SettingType.BOOLEAN,
        "enableGoogleCast" to SettingType.BOOLEAN,
    )

private fun PortableSetting.isValidValue(): Boolean =
    when (type) {
        SettingType.STRING -> true
        SettingType.BOOLEAN -> value.toBooleanStrictOrNull() != null
        SettingType.INT -> value.toIntOrNull() != null
        SettingType.LONG -> value.toLongOrNull() != null
        SettingType.FLOAT -> value.toFloatOrNull()?.isFinite() == true
    }

private fun legacyValue(
    key: String,
    type: SettingType,
    value: Any,
): String? =
    when (type) {
        SettingType.BOOLEAN -> legacyBoolean(value)?.toString()
        else -> convertLegacyValue(key, value)?.toString()
    }

private fun legacyBoolean(value: Any): Boolean? = value as? Boolean ?: (value as? String)?.toBooleanStrictOrNull()
