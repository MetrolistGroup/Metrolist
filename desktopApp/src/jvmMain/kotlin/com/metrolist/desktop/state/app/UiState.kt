/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.update.GitHubDesktopRelease
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.theme.resolveThemeSeedColor

object UiState {
    internal var artworkSeedColor by mutableStateOf<Color?>(null)
    val seedColor: Color
        get() =
            resolveThemeSeedColor(
                materialYouEnabled = Settings.shared.materialYouEnabled,
                songArtworkColorsEnabled = Settings.shared.dynamicTheme,
                selectedThemeColor = Settings.shared.selectedThemeColor,
                artworkSeedColor = artworkSeedColor,
            )
    var isWindowFocused by mutableStateOf(false)
    var isMaximized by mutableStateOf(false)
    var isNativeFullscreen by mutableStateOf(false)
    var isPlayerExpanded by mutableStateOf(false)

    var wasMaximizedBeforeFullscreen by mutableStateOf(false)

    var showSettings by mutableStateOf(false)
    var selectedSettingsPage by mutableStateOf<SettingsPageId?>(null)
    var openDevtoolsSettingsRequested by mutableStateOf(false)
    var openPlaylistImportRequested by mutableStateOf(false)
    var showSignIn by mutableStateOf(false)
    var showSwitchChannel by mutableStateOf(false)
    var isCheckingForUpdates by mutableStateOf(false)
    var updateCheckFailed by mutableStateOf(false)
    var hasCheckedForUpdates by mutableStateOf(false)
    var createPlaylistRequested by mutableStateOf(false)
    var updateReleases by mutableStateOf<List<GitHubDesktopRelease>>(emptyList())
    var availableUpdate by mutableStateOf<GitHubDesktopRelease?>(null)
    var canInstallUpdate by mutableStateOf(false)
    var isDownloadingUpdate by mutableStateOf(false)
    var updateDownloadError by mutableStateOf<String?>(null)
    var updateManagerStarted by mutableStateOf(false)

    var showAppearanceSettings by mutableStateOf(false)
    var showThemeSettings by mutableStateOf(false)
    var showSliderStyleDialog by mutableStateOf(false)
    var showPlayerBackgroundStyleDialog by mutableStateOf(false)
    var showLayoutDialog by mutableStateOf(false)
    var showPlayerSettings by mutableStateOf(false)
    internal val playerPanels = PlayerPanelState()
    var showPersistentPlayerDrawer: Boolean
        get() = playerPanels.showQueue
        set(value) {
            playerPanels.showQueue = value
        }
    var isSearchFocused by mutableStateOf(false)
    var isTextInputFocused by mutableStateOf(false)
    var showLyricsDrawer: Boolean
        get() = playerPanels.showLyrics
        set(value) {
            playerPanels.showLyrics = value
        }
    var showDeviceConnectPanel by mutableStateOf(false)
    var isDeviceConnectPanelModal by mutableStateOf(false)
    var showVideoViewer by mutableStateOf(false)
    internal val playerPresentation = PlayerPresentationState()
    var showVideoFullscreen: Boolean
        get() = playerPresentation.showVideoFullscreen
        set(value) {
            playerPresentation.showVideoFullscreen = value
        }
    var videoFullscreenObscuresPlayer by mutableStateOf(false)
    internal val videoFullscreenPanels = PlayerPanelState()
    var showVideoFullscreenQueue: Boolean
        get() = videoFullscreenPanels.showQueue
        set(value) {
            videoFullscreenPanels.showQueue = value
        }
    var showVideoFullscreenLyrics: Boolean
        get() = videoFullscreenPanels.showLyrics
        set(value) {
            videoFullscreenPanels.showLyrics = value
        }
    var showVideoStatsForNerds by mutableStateOf(false)
    var showContentSettings by mutableStateOf(false)
    var showLyricsSettings by mutableStateOf(false)
    var showPrivacySettings by mutableStateOf(false)
    var showStorageSettings by mutableStateOf(false)
    var showAboutSettings by mutableStateOf(false)
    var showLogViewer by mutableStateOf(false)
    var showShortcutsDialog by mutableStateOf(false)

    var showIntegrationsSettings by mutableStateOf(false)

    var showTokenLogin by mutableStateOf(false)
    var showFloatingSearch by mutableStateOf(false)
    var isInlineSearchAvailable by mutableStateOf(false)
    var focusInlineSearchTrigger by mutableStateOf(0)
    var isRefreshingApp by mutableStateOf(false)
    var appRefreshKey by mutableStateOf(0)

    var restartRequested by mutableStateOf(false)

    fun toggleDeviceConnectPanel() {
        showDeviceConnectPanel = !showDeviceConnectPanel
        if (showDeviceConnectPanel) {
            PlayerState.exitExpanded()
            showPersistentPlayerDrawer = false
            showLyricsDrawer = false
        }
    }

    fun togglePersistentPlayerDrawer() {
        showPersistentPlayerDrawer = !showPersistentPlayerDrawer
        if (showPersistentPlayerDrawer) showDeviceConnectPanel = false
    }
}
