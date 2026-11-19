package com.metrolist.desktop.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.tray_by
import com.metrolist.shared.generated.resources.tray_hide_miniplayer
import com.metrolist.shared.generated.resources.tray_hide_window
import com.metrolist.shared.generated.resources.tray_next_track
import com.metrolist.shared.generated.resources.tray_pause
import com.metrolist.shared.generated.resources.tray_play
import com.metrolist.shared.generated.resources.tray_previous_track
import com.metrolist.shared.generated.resources.tray_quit_metrolist
import com.metrolist.shared.generated.resources.tray_settings
import com.metrolist.shared.generated.resources.tray_show_miniplayer
import com.metrolist.shared.generated.resources.tray_show_window
import com.metrolist.shared.generated.resources.tray_tooltip_paused
import com.metrolist.shared.generated.resources.tray_tooltip_playing
import com.metrolist.shared.generated.resources.tray_unknown
import com.metrolist.shared.generated.resources.tray_unknown_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.window.Tray as StockTray
import dev.nucleusframework.composenativetray.tray.api.Tray as NativeTray

object ComposeTrayManager {
    init {
        if (OsUtil.isMac) {
            System.setProperty("apple.awt.enableTemplateImages", "true")
        }
    }

    private var windowState: WindowState? = null
    private var mainWindow: Window? = null
    private var onQuit: (() -> Unit)? = null
    private var isWindowVisible = mutableStateOf(true)
    internal val windowVisible: Boolean get() = isWindowVisible.value
    private val trayEnabled = mutableStateOf(true)
    private var trayDisposed = CompletableDeferred(Unit)

    private var isDarkTheme: Boolean = false

    private var useThemeColors: Boolean = false
    private var themeBgColor: Color? = null
    private var themeFgColor: Color? = null
    private var themeBorderColor: Color? = null

    fun initialize(
        mainWindowState: WindowState,
        mainWindow: Window,
        onQuit: () -> Unit,
    ) {
        windowState = mainWindowState
        this.mainWindow = mainWindow
        this.onQuit = onQuit
        trayDisposed = CompletableDeferred()
        trayEnabled.value = true
    }

    fun setThemeColors(
        bgArgb: Int,
        fgArgb: Int,
        borderArgb: Int,
        dark: Boolean,
    ) {
        try {
            useThemeColors = true
            themeBgColor = Color(bgArgb)
            themeFgColor = Color(fgArgb)
            themeBorderColor = Color(borderArgb)
            isDarkTheme = dark
        } catch (_: Exception) {
        }
    }

    fun toggleWindow() {
        if (isWindowVisible.value) {
            hideWindow()
        } else {
            showWindow()
        }
    }

    fun showWindow() {
        EventQueue.invokeLater {
            // Disposal can precede tray cleanup; a disposed Compose window cannot be shown again.
            val window = mainWindow?.takeIf { it.isDisplayable } ?: return@invokeLater
            windowState?.let { state ->
                state.isMinimized = false
                isWindowVisible.value = true
            }
            if (window is Frame && (window.extendedState and Frame.ICONIFIED) != 0) {
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            }
            window.isVisible = true
            if (OsUtil.isMac) {
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().requestForeground(true)
                    }
                }
            }
            window.toFront()
            window.requestFocus()
        }
    }

    fun hideWindow() {
        EventQueue.invokeLater {
            val window = mainWindow?.takeIf { it.isDisplayable } ?: return@invokeLater
            isWindowVisible.value = false
            window.isVisible = false
        }
    }

    fun setWindowVisible(visible: Boolean) {
        isWindowVisible.value = visible
    }

    @Composable
    fun ApplicationScope.TrayIcon() {
        if (!trayEnabled.value) return
        val mediaControls by PlayerState.mediaControls.collectAsState()
        val isPlaying = PlayerState.isPlaying
        val currentTrack = PlayerState.currentTrack
        val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()

        @Suppress("DEPRECATION")
        val icon = painterResource("logo.png")

        val strUnknownTitle = stringResource(Res.string.tray_unknown_title)
        val strUnknown = stringResource(Res.string.tray_unknown)
        val strPlay = stringResource(Res.string.tray_play)
        val strPause = stringResource(Res.string.tray_pause)
        val strNextTrack = stringResource(Res.string.tray_next_track)
        val strPreviousTrack = stringResource(Res.string.tray_previous_track)
        val strHideWindow = stringResource(Res.string.tray_hide_window)
        val strShowWindow = stringResource(Res.string.tray_show_window)
        val strShowMiniplayer = stringResource(Res.string.tray_show_miniplayer)
        val strHideMiniplayer = stringResource(Res.string.tray_hide_miniplayer)
        val strSettings = stringResource(Res.string.tray_settings)
        val strQuitMetrolist = stringResource(Res.string.tray_quit_metrolist)
        val strTrayByTemplate = stringResource(Res.string.tray_by)

        val tooltip =
            currentTrack?.let { track ->
                val artistStr = track.artists.joinToString { it.displayName }.takeIf { it.isNotBlank() } ?: strUnknown
                val title = track.title.takeIf { it.isNotBlank() } ?: strUnknownTitle
                if (isPlaying) {
                    stringResource(Res.string.tray_tooltip_playing, title, artistStr)
                } else {
                    stringResource(Res.string.tray_tooltip_paused, title, artistStr)
                }
            } ?: "Metrolist"

        val currentArtistLabel =
            currentTrack?.let { track ->
                val artistStr = track.artists.joinToString { it.displayName }.takeIf { it.isNotBlank() } ?: strUnknown
                String.format(strTrayByTemplate, artistStr)
            }
        val currentArtistId = currentTrack?.artists?.firstOrNull { !it.id.isNullOrBlank() }?.id
        val currentTitleLabel = currentTrack?.title?.takeIf { it.isNotBlank() } ?: strUnknownTitle
        val playPauseLabel = if (isPlaying) strPause else strPlay
        val windowToggleLabel = if (isWindowVisible.value) strHideWindow else strShowWindow
        val miniplayerToggleLabel = if (PlayerState.showMiniplayer) strHideMiniplayer else strShowMiniplayer
        val onTrayAction: () -> Unit = {
            if (Settings.shared.desktopTrayToggleOnClick) {
                if (isWindowVisible.value) {
                    hideWindow()
                } else {
                    showWindow()
                }
            }
        }

        if (OsUtil.isMac) {
            StockTray(
                icon = icon,
                tooltip = tooltip,
                onAction = onTrayAction,
                menu = {
                    currentTrack?.let {
                        Item(
                            text = currentTitleLabel,
                            onClick = { showWindow() },
                        )
                        Item(
                            text = currentArtistLabel ?: "",
                            enabled = currentArtistId != null,
                            onClick = {
                                currentArtistId?.let {
                                    showWindow()
                                    EventQueue.invokeLater { DesktopNavigation.navigate(Screen.Artist(it)) }
                                }
                            },
                        )
                        Separator()
                    }

                    if (!isListenTogetherGuest) {
                        Item(
                            text = playPauseLabel,
                            enabled = mediaControls.playPause,
                            onClick = { PlayerState.togglePlay() },
                        )

                        if (currentTrack != null) {
                            Item(
                                text = strNextTrack,
                                enabled = mediaControls.next,
                                onClick = { PlayerState.skipNext() },
                            )

                            Item(
                                text = strPreviousTrack,
                                enabled = mediaControls.previous,
                                onClick = { PlayerState.skipPrevious() },
                            )
                        }

                        Separator()
                    }

                    Item(text = windowToggleLabel, onClick = { toggleWindow() })
                    Item(text = miniplayerToggleLabel, onClick = { PlayerState.showMiniplayer = !PlayerState.showMiniplayer })
                    Separator()
                    Item(
                        text = strSettings,
                        onClick = {
                            UiState.showSettings = true
                            if (!isWindowVisible.value) {
                                showWindow()
                            }
                        },
                    )
                    Separator()
                    Item(text = strQuitMetrolist, onClick = { onQuit?.invoke() })
                },
            )
        } else {
            NativeTray(
                icon = icon,
                tooltip = tooltip,
                primaryAction = onTrayAction,
                menuContent = {
                    currentTrack?.let {
                        Item(label = currentTitleLabel) { showWindow() }
                        Item(label = currentArtistLabel ?: "", isEnabled = currentArtistId != null) {
                            currentArtistId?.let {
                                showWindow()
                                EventQueue.invokeLater { DesktopNavigation.navigate(Screen.Artist(it)) }
                            }
                        }
                        Divider()
                    }

                    if (!isListenTogetherGuest) {
                        Item(
                            label = playPauseLabel,
                            isEnabled = mediaControls.playPause,
                        ) {
                            PlayerState.togglePlay()
                        }

                        if (currentTrack != null) {
                            Item(label = strNextTrack, isEnabled = mediaControls.next) {
                                PlayerState.skipNext()
                            }

                            Item(label = strPreviousTrack, isEnabled = mediaControls.previous) {
                                PlayerState.skipPrevious()
                            }
                        }

                        Divider()
                    }

                    Item(label = windowToggleLabel) { toggleWindow() }
                    Item(label = miniplayerToggleLabel) { PlayerState.showMiniplayer = !PlayerState.showMiniplayer }
                    Divider()
                    Item(label = strSettings) {
                        UiState.showSettings = true
                        if (!isWindowVisible.value) {
                            showWindow()
                        }
                    }
                    Divider()
                    Item(label = strQuitMetrolist) { onQuit?.invoke() }
                },
            )
        }
        DisposableEffect(Unit) {
            onDispose { trayDisposed.complete(Unit) }
        }
    }

    suspend fun cleanup() {
        withContext(Dispatchers.Main) { trayEnabled.value = false }
        withTimeoutOrNull(1000.milliseconds) { trayDisposed.await() }
        delay(50.milliseconds)
        windowState = null
        mainWindow = null
        onQuit = null
    }
}
