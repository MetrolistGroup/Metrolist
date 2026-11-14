/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.ui.screens.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.desktop.state.DesktopLocaleManager
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.appLanguageChoices
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.SearchState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.SongCacheManager
import com.metrolist.desktop.ui.components.LocalCaptionBarInsets
import com.metrolist.desktop.ui.components.rememberMetroEqProfilePicker
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.utils.DesktopFontManager
import com.metrolist.desktop.utils.SentryManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_font_active
import com.metrolist.shared.generated.resources.app_font_checking
import com.metrolist.shared.generated.resources.app_font_download_and_use
import com.metrolist.shared.generated.resources.app_font_download_error
import com.metrolist.shared.generated.resources.app_font_downloading
import com.metrolist.shared.generated.resources.app_font_google_sans
import com.metrolist.shared.generated.resources.app_font_installed
import com.metrolist.shared.generated.resources.app_font_inter
import com.metrolist.shared.generated.resources.app_font_maple_mono
import com.metrolist.shared.generated.resources.app_font_system
import com.metrolist.shared.generated.resources.app_language_system
import com.metrolist.shared.generated.resources.device_language
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.settings_artist_source_custom
import com.metrolist.shared.generated.resources.settings_artist_source_itunes
import com.metrolist.shared.generated.resources.settings_artist_source_lastfm
import com.metrolist.shared.generated.resources.settings_artist_source_youtube
import com.metrolist.shared.generated.resources.settings_diagnostic_logs_summary
import com.metrolist.shared.generated.resources.settings_home_section_dynamic
import com.metrolist.shared.generated.resources.settings_home_section_items
import com.metrolist.shared.generated.resources.settings_log_directory_summary
import com.metrolist.shared.generated.resources.settings_open_log_folder
import com.metrolist.shared.generated.resources.settings_playback_speed_locked_listen_together
import com.metrolist.shared.generated.resources.settings_storage_summary
import com.metrolist.shared.generated.resources.settings_system_font_summary
import com.metrolist.shared.generated.resources.settings_view_application_logs
import com.metrolist.shared.model.ArtistSource
import com.metrolist.shared.settings.FontOption
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.applyContentRegion
import com.metrolist.shared.state.contentCountryCodes
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.screens.HomeShelfBuilder
import com.metrolist.shared.ui.screens.homeContentText
import com.metrolist.shared.ui.settings.LyricsSettingsPreview
import com.metrolist.shared.ui.settings.SettingsChangeEffect
import com.metrolist.shared.ui.settings.SettingsChoice
import com.metrolist.shared.ui.settings.SettingsHomeSection
import com.metrolist.shared.ui.settings.SettingsHostAction
import com.metrolist.shared.ui.settings.SettingsHostChoice
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPlatform
import com.metrolist.shared.ui.settings.SharedSettingsHost
import com.metrolist.shared.ui.settings.SharedSettingsScreen
import com.metrolist.shared.ui.settings.settingsPageSubtitle
import com.metrolist.shared.ui.utils.rememberFileSizeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.util.Locale

@Composable
fun SettingsOverlay(colorScheme: ColorScheme) {
    val formatFileSize = rememberFileSizeFormatter()
    var devtoolsUnlocked by remember { mutableStateOf(Settings.shared.devtoolsUnlocked) }
    val openDevtoolsOnLaunch = remember { UiState.openDevtoolsSettingsRequested }
    val openPlaylistImportOnLaunch = remember { UiState.openPlaylistImportRequested }
    val isInListenTogetherRoom by ListenTogetherState.listenTogetherManager.isInRoom.collectAsState()
    val customPages =
        buildSet {
            add(SettingsPageId.STORAGE)
            add(SettingsPageId.BACKUP_RESTORE)
            add(SettingsPageId.UPDATES)
            add(SettingsPageId.ABOUT)
            if (devtoolsUnlocked) add(SettingsPageId.DEVTOOLS)
        }
    val availablePages =
        customPages +
            setOf(
                SettingsPageId.FONTS,
                SettingsPageId.HIDDEN_HOME_ITEMS,
                SettingsPageId.HIDDEN_LIBRARY_ITEMS,
                SettingsPageId.BLOCKED_ARTISTS,
                SettingsPageId.LYRICS_SOURCES,
                SettingsPageId.LYRICS_AI_TRANSLATION,
                SettingsPageId.LYRICS_ROMANIZATION,
                SettingsPageId.PLAYER_ARTWORK,
                SettingsPageId.PLAYER_STYLE,
            )
    val systemDefaultLabel = stringResource(Res.string.app_language_system)
    val systemLocale = Locale.getDefault()
    val appLanguageChoices = appLanguageChoices()
    val deviceLanguageLabel = stringResource(Res.string.device_language)
    val lyricsTargetLanguageChoices =
        listOf(SettingsChoice("device", deviceLanguageLabel)) + appLanguageChoices.filterNot { it.id == GlobalYouTube.SYSTEM }
    val artistSourceChoices =
        ArtistSource.entries.map { source ->
            SettingsChoice(
                id = source.name,
                label =
                    stringResource(
                        when (source) {
                            ArtistSource.YOUTUBE -> Res.string.settings_artist_source_youtube
                            ArtistSource.ITUNES -> Res.string.settings_artist_source_itunes
                            ArtistSource.LASTFM -> Res.string.settings_artist_source_lastfm
                            ArtistSource.CUSTOM -> Res.string.settings_artist_source_custom
                        },
                    ),
            )
        }
    val contentCountryChoices =
        listOf(SettingsChoice(GlobalYouTube.SYSTEM, systemDefaultLabel)) +
            contentCountryCodes
                .asSequence()
                .map { code ->
                    SettingsChoice(
                        code,
                        Locale
                            .Builder()
                            .setRegion(code)
                            .build()
                            .getDisplayCountry(systemLocale)
                            .ifBlank { code },
                    )
                }.sortedBy(SettingsChoice::label)
                .toList()
    val homeSections =
        HomeShelfBuilder.build(HomeState.uiState, homeContentText(), 1000.dp, includeHidden = true).map { section ->
            SettingsHomeSection(
                id = section.key,
                preferenceKey = section.visibilityKey,
                title = section.title,
                description =
                    if (section.items.isNotEmpty()) {
                        stringResource(Res.string.settings_home_section_items, section.items.size)
                    } else {
                        stringResource(Res.string.settings_home_section_dynamic)
                    },
                visible = Settings.shared.shouldShowHomeSection(section.visibilityKey, section.title),
            )
        }
    val scope = rememberCoroutineScope()
    var installedFontOptions by remember { mutableStateOf<Set<FontOption>?>(null) }
    var downloadingFont by remember { mutableStateOf<FontOption?>(null) }
    var failedFontDownload by remember { mutableStateOf<FontOption?>(null) }
    val fontLabels =
        mapOf(
            FontOption.INTER to stringResource(Res.string.app_font_inter),
            FontOption.GOOGLE_SANS to stringResource(Res.string.app_font_google_sans),
            FontOption.MAPLE_MONO to stringResource(Res.string.app_font_maple_mono),
        )
    val fontPrimaryActions =
        listOf(
            SettingsHostAction(
                id = FontOption.SYSTEM.name,
                title = stringResource(Res.string.app_font_system),
                description =
                    settingsPageSubtitle(
                        stringResource(Res.string.settings_system_font_summary),
                        if (Settings.shared.appFont ==
                            FontOption.SYSTEM
                        ) {
                            stringResource(Res.string.app_font_active)
                        } else {
                            null
                        },
                    ),
                enabled = downloadingFont == null,
                onClick = { Settings.shared.appFont = FontOption.SYSTEM },
            ),
        )
    val fontDownloadActions =
        DesktopFontManager.downloadableOptions.map { option ->
            val installed = option in installedFontOptions.orEmpty()
            val fontLabel = checkNotNull(fontLabels[option])
            SettingsHostAction(
                id = option.name,
                title = fontLabel,
                description =
                    when {
                        downloadingFont == option -> stringResource(Res.string.app_font_downloading, fontLabel)
                        installedFontOptions == null -> stringResource(Res.string.app_font_checking)
                        failedFontDownload == option -> stringResource(Res.string.app_font_download_error, fontLabel)
                        Settings.shared.appFont == option && installed -> stringResource(Res.string.app_font_active)
                        installed -> stringResource(Res.string.app_font_installed)
                        else -> stringResource(Res.string.app_font_download_and_use)
                    },
                enabled = installedFontOptions != null && downloadingFont == null,
                onClick = {
                    if (installed) {
                        Settings.shared.appFont = option
                    } else {
                        downloadingFont = option
                        failedFontDownload = null
                        scope.launch {
                            DesktopFontManager.download(option).fold(
                                onSuccess = {
                                    installedFontOptions = installedFontOptions.orEmpty() + option
                                    Settings.shared.appFont = option
                                },
                                onFailure = { failedFontDownload = option },
                            )
                            downloadingFont = null
                        }
                    }
                },
            )
        }
    val privacyDiagnosticActions =
        listOf(
            SettingsHostAction(
                id = "viewApplicationLogs",
                title = stringResource(Res.string.settings_view_application_logs),
                description = stringResource(Res.string.settings_diagnostic_logs_summary),
                onClick = { UiState.showLogViewer = true },
            ),
            SettingsHostAction(
                id = "openLogFolder",
                title = stringResource(Res.string.settings_open_log_folder),
                description = stringResource(Res.string.settings_log_directory_summary),
                enabled = Desktop.isDesktopSupported(),
                onClick = { runCatching { Desktop.getDesktop().open(AppLogger.getLogDirectory()) } },
            ),
        )

    LaunchedEffect(Unit) {
        UiState.openDevtoolsSettingsRequested = false
        UiState.openPlaylistImportRequested = false
        installedFontOptions = withContext(Dispatchers.IO) { DesktopFontManager.installedOptions() }
    }

    val dismiss = {
        UiState.showSettings = false
        UiState.openDevtoolsSettingsRequested = false
        UiState.openPlaylistImportRequested = false
    }
    val settingsTitle = stringResource(Res.string.settings)
    val focusRequester = remember { FocusRequester() }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        val downloadedFiles = LibraryState.downloadedFiles
        val cachedSongIds by produceState(emptySet<String>()) {
            withContext(Dispatchers.IO) {
                SongCacheManager.cachedSongIds.collect { value = it }
            }
        }
        val storageSizes by
            produceState<Pair<String, String>?>(null, downloadedFiles, cachedSongIds, formatFileSize) {
                value =
                    withContext(Dispatchers.IO) {
                        val downloadSize = DownloadManager.getDownloadDir().walkTopDown().sumOf { it.length() }
                        val cacheSize = SongCacheManager.getCacheSizeBytes()
                        val totalUsed = downloadSize + cacheSize
                        val freeBytes = DownloadManager.getDownloadDir().freeSpace
                        formatFileSize(totalUsed) to formatFileSize(freeBytes)
                    }
            }

        val storageSummary = storageSizes?.let { (used, free) -> stringResource(Res.string.settings_storage_summary, used, free) }

        Card(
            modifier =
                Modifier
                    .windowInsetsPadding(LocalCaptionBarInsets.current.only(WindowInsetsSides.Top))
                    .padding(16.dp)
                    .semantics {
                        paneTitle = settingsTitle
                        isTraversalGroup = true
                    }.focusRequester(focusRequester)
                    .focusable()
                    .focusGroup()
                    .widthIn(max = 1180.dp)
                    .fillMaxWidth()
                    .heightIn(max = 800.dp)
                    .fillMaxHeight(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            SharedSettingsScreen(
                host =
                    SharedSettingsHost(
                        platform = SettingsPlatform.DESKTOP,
                        onSwitchYouTubeChannel = if (AuthState.isSignedIn) ({ UiState.showSwitchChannel = true }) else null,
                        lyricsPreview = { modifier ->
                            val song = PlayerState.currentTrack
                            val castState by PlayerState.castManager.state.collectAsState()
                            LaunchedEffect(
                                song?.id,
                                PlayerState.isPlaying,
                                Settings.shared.lyricsProviderOrder,
                                Settings.shared.lyricsEnabledProviders,
                                Settings.shared.preferWordSyncedLyrics,
                            ) {
                                if (PlayerState.isPlaying) song?.let { LyricsState.fetchLyrics(it) }
                            }
                            LyricsSettingsPreview(
                                modifier = modifier,
                                songId = song?.id?.takeIf { it == LyricsState.currentLyricsSongId },
                                currentLyrics = LyricsState.currentLyricsEntries,
                                currentAgents = LyricsState.currentLyricsAgents,
                                currentSongwriters = LyricsState.currentLyricsSongwriters,
                                currentProvider = LyricsState.currentLyricsProvider,
                                currentPosition = PlayerState.playbackPosition,
                                positionReader = PlayerState::currentMediaPositionMs,
                                playbackSpeed =
                                    PlayerState.player.playbackSpeed
                                        .collectAsState()
                                        .value,
                                timingOffsetMs = LyricsState.currentLyricsOffsetMs,
                                isPlaying = PlayerState.isPlaying,
                                isCasting = castState.isConnected,
                                lyricsRetimed = LyricsState.mvLyricsRetimed,
                                onSeek = PlayerState::seekToPosition,
                            )
                        },
                        rememberMetroEqProfilePicker = { onPicked -> rememberMetroEqProfilePicker(onPicked) },
                        storageSummary = storageSummary,
                        onClose = {
                            UiState.showSettings = false
                            UiState.openDevtoolsSettingsRequested = false
                            UiState.openPlaylistImportRequested = false
                        },
                        onOpenArtist = { artistId ->
                            UiState.showSettings = false
                            DesktopNavigation.navigate(Screen.Artist(artistId))
                        },
                        availablePages = availablePages,
                        initialPage =
                            when {
                                openPlaylistImportOnLaunch -> SettingsPageId.BACKUP_RESTORE
                                openDevtoolsOnLaunch && devtoolsUnlocked -> SettingsPageId.DEVTOOLS
                                else -> UiState.selectedSettingsPage
                            },
                        onPageChanged = { UiState.selectedSettingsPage = it },
                        onSettingChanged = { effect, value ->
                            when (effect) {
                                SettingsChangeEffect.SENTRY -> {
                                    value?.let(SentryManager::setEnabled)
                                }

                                SettingsChangeEffect.RESTART -> {
                                    UiState.restartRequested = true
                                }

                                SettingsChangeEffect.LOGGING -> {
                                    value?.let(AppLogger::setEnabled)
                                }

                                SettingsChangeEffect.VIDEO_PLAYBACK -> {
                                    if (value == false) PlayerState.closeVideoViewer()
                                }

                                SettingsChangeEffect.AUDIO,
                                SettingsChangeEffect.CONTENT,
                                SettingsChangeEffect.AUTO_DOWNLOAD_ON_LIKE,
                                SettingsChangeEffect.PLAYBACK_SPEED,
                                SettingsChangeEffect.PLAYBACK_PITCH,
                                -> {
                                }
                            }
                        },
                        onFloatSettingChanged = { effect, value ->
                            when (effect) {
                                SettingsChangeEffect.PLAYBACK_SPEED -> PlayerState.setPlaybackSpeed(value)
                                SettingsChangeEffect.PLAYBACK_PITCH -> PlayerState.setPlaybackPitch(value)
                                else -> Unit
                            }
                        },
                        hostChoices =
                            mapOf(
                                SettingsHostChoice.CONTENT_LANGUAGE to appLanguageChoices,
                                SettingsHostChoice.CONTENT_COUNTRY to contentCountryChoices,
                                SettingsHostChoice.APP_LANGUAGE to appLanguageChoices,
                                SettingsHostChoice.LYRICS_TARGET_LANGUAGE to lyricsTargetLanguageChoices,
                                SettingsHostChoice.ARTIST_ICON_SOURCE to artistSourceChoices,
                                SettingsHostChoice.ARTIST_BANNER_SOURCE to artistSourceChoices,
                            ),
                        onHostChoiceSelected = { choice, value ->
                            when (choice) {
                                SettingsHostChoice.CONTENT_LANGUAGE -> {
                                    Settings.shared.contentLanguage = value
                                }

                                SettingsHostChoice.CONTENT_COUNTRY -> {
                                    Settings.shared.contentCountry = value
                                }

                                SettingsHostChoice.APP_LANGUAGE -> {
                                    Settings.shared.appLanguage = value
                                    UiState.restartRequested = true
                                }

                                SettingsHostChoice.LYRICS_TARGET_LANGUAGE -> {
                                    Settings.shared.lyricsAiTargetLanguage = value
                                }

                                SettingsHostChoice.APP_ICON -> {
                                }

                                SettingsHostChoice.ARTIST_ICON_SOURCE -> {
                                    ArtistState.updateArtistIconSource(ArtistSource.valueOf(value))
                                }

                                SettingsHostChoice.ARTIST_BANNER_SOURCE -> {
                                    ArtistState.updateArtistBannerSource(ArtistSource.valueOf(value))
                                }
                            }
                            if (choice == SettingsHostChoice.CONTENT_LANGUAGE || choice == SettingsHostChoice.CONTENT_COUNTRY) {
                                val systemLocale = DesktopLocaleManager.systemLocale
                                GlobalYouTube.applyContentRegion(
                                    contentCountry = Settings.shared.contentCountry,
                                    contentLanguage = Settings.shared.contentLanguage,
                                    systemLocale = systemLocale,
                                )
                                HomeState.fetchHomeData()
                            }
                        },
                        playbackSpeedEnabled = !isInListenTogetherRoom,
                        playbackSpeedOverride = 1f.takeIf { isInListenTogetherRoom },
                        playbackSpeedDescription =
                            stringResource(Res.string.settings_playback_speed_locked_listen_together).takeIf {
                                isInListenTogetherRoom
                            },
                        homeSections = homeSections,
                        onHomeSectionVisibilityChanged = { sectionTitle, visible ->
                            Settings.shared.toggleHomeSectionVisibility(sectionTitle, visible)
                        },
                        onClearSearchHistory = SearchState::clearSearchHistory,
                        privacyDiagnosticActions = privacyDiagnosticActions,
                        fontPrimaryActions = fontPrimaryActions,
                        fontDownloadActions = fontDownloadActions,
                        pageContent = { page, _, _ ->
                            when (page) {
                                SettingsPageId.STORAGE -> {
                                    StorageSettingsScreen(
                                        colorScheme,
                                        onNavigateToDownloads = {
                                            UiState.showSettings = false
                                            LibraryState.navigateToAutoPlaylist("downloaded")
                                        },
                                    )
                                }

                                SettingsPageId.BACKUP_RESTORE -> {
                                    BackupRestoreSettingsScreen(colorScheme)
                                }

                                SettingsPageId.UPDATES -> {
                                    UpdatesSettingsScreen(colorScheme)
                                }

                                SettingsPageId.DEVTOOLS -> {
                                    DevtoolsSettingsScreen(colorScheme)
                                }

                                SettingsPageId.ABOUT -> {
                                    DesktopAboutScreen(
                                        colorScheme = colorScheme,
                                        onDevtoolsUnlocked = { devtoolsUnlocked = true },
                                    )
                                }

                                else -> {}
                            }
                        },
                    ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
