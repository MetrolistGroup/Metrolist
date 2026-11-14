package com.metrolist.desktop.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.WindowState
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.tray_by
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
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.utils.OsUtil
import dev.nucleusframework.composenativetray.lib.mac.MacOSMenuBarThemeDetector
import dev.nucleusframework.composenativetray.tray.api.Tray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import java.awt.EventQueue
import java.awt.Window
import kotlin.time.Duration.Companion.milliseconds

object ComposeTrayManager {
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
        windowState?.let { state ->
            if (state.isMinimized) {
                state.isMinimized = false
                isWindowVisible.value = true
            } else {
                state.isMinimized = true
                isWindowVisible.value = false
            }
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
            window.isVisible = true
            window.toFront()
            window.requestFocus()
        }
    }

    fun setWindowVisible(visible: Boolean) {
        isWindowVisible.value = visible
    }

    @Composable
    private fun rememberMacOsMenuBarIsDark(): Boolean {
        var isDark by remember {
            mutableStateOf(runCatching { MacOSMenuBarThemeDetector.isDark() }.getOrDefault(true))
        }
        DisposableEffect(Unit) {
            val listener =
                java.util.function.Consumer<Boolean> { dark ->
                    isDark = dark
                }
            runCatching { MacOSMenuBarThemeDetector.registerListener(listener) }
            onDispose {
                runCatching { MacOSMenuBarThemeDetector.removeListener(listener) }
            }
        }
        return isDark
    }

    @Composable
    fun TrayIcon() {
        if (!trayEnabled.value) return
        val mediaControls by PlayerState.mediaControls.collectAsState()
        val isPlaying = PlayerState.isPlaying
        val currentTrack = PlayerState.currentTrack
        val isListenTogetherGuest = ListenTogetherState.isListenTogetherGuest()

        val isMacDarkMenu = if (OsUtil.isMac) rememberMacOsMenuBarIsDark() else true

        @Suppress("DEPRECATION")
        val icon = painterResource(if (isMacDarkMenu) "logo.png" else "logo_dark.png")

        val strUnknownTitle = stringResource(Res.string.tray_unknown_title)
        val strUnknown = stringResource(Res.string.tray_unknown)
        val strPlay = stringResource(Res.string.tray_play)
        val strPause = stringResource(Res.string.tray_pause)
        val strNextTrack = stringResource(Res.string.tray_next_track)
        val strPreviousTrack = stringResource(Res.string.tray_previous_track)
        val strHideWindow = stringResource(Res.string.tray_hide_window)
        val strShowWindow = stringResource(Res.string.tray_show_window)
        val strShowMiniplayer = stringResource(Res.string.tray_show_miniplayer)
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

        Tray(
            icon = icon,
            tooltip = tooltip,
            menuContent = {
                currentTrack?.let { track ->
                    val titleLabel = track.title.takeIf { it.isNotBlank() } ?: strUnknownTitle
                    val artistStr = track.artists.joinToString { it.displayName }.takeIf { it.isNotBlank() } ?: strUnknown
                    val artistLabel = String.format(strTrayByTemplate, artistStr)
                    Item(label = titleLabel) { showWindow() }
                    val artistId = track.artists.firstOrNull { !it.id.isNullOrBlank() }?.id
                    Item(label = artistLabel, isEnabled = artistId != null) {
                        artistId?.let {
                            showWindow()
                            EventQueue.invokeLater { DesktopNavigation.navigate(Screen.Artist(it)) }
                        }
                    }
                    Divider()
                }

                if (!isListenTogetherGuest) {
                    Item(
                        label = if (isPlaying) strPause else strPlay,
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

                Item(
                    label = if (isWindowVisible.value) strHideWindow else strShowWindow,
                ) {
                    toggleWindow()
                }

                Item(label = strShowMiniplayer) {
                    PlayerState.showMiniplayer = true
                }

                Divider()

                Item(label = strSettings) {
                    UiState.showSettings = true
                    if (!isWindowVisible.value) {
                        showWindow()
                    }
                }

                Divider()

                Item(label = strQuitMetrolist) {
                    onQuit?.invoke()
                }
            },
        )
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
