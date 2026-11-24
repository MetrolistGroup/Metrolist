/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.input

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.utils.WaylandScale
import com.metrolist.desktop.web.DesktopWebViewAvailability
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.playback.LocalPlaybackKeyFocus
import com.metrolist.shared.ui.playback.PlaybackKeyFocus
import com.metrolist.shared.ui.utils.desktopScrollEvent
import com.metrolist.shared.utils.OsUtil
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.text.JTextComponent
import java.awt.event.KeyEvent as AwtKeyEvent

internal fun triggerSearchShortcut() {
    if (Settings.shared.floatingSearch) {
        UiState.showFloatingSearch = !UiState.showFloatingSearch
    } else {
        if (UiState.isInlineSearchAvailable && !PlayerState.isExpanded) {
            UiState.focusInlineSearchTrigger++
        } else {
            UiState.showFloatingSearch = !UiState.showFloatingSearch
        }
    }
}

internal sealed interface ShortcutKeyCode {
    data class Compose(
        val key: Key,
    ) : ShortcutKeyCode

    data class Awt(
        val code: Int,
        val character: Char,
        val physicalPosition: PhysicalKeyPosition? = null,
        val physicalPositionsAvailable: Boolean = false,
    ) : ShortcutKeyCode
}

@Composable
fun DesktopShortcutLayer(
    isPlaybackBackgroundFocused: () -> Boolean,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    toggleWindowFullscreen: () -> Unit = {},
    clearPointerFocus: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // Compose gives popup/dialog handlers priority over this window-level fallback.
    NavigationBackHandler {
        handleShortcut(
            ctrl = false,
            meta = false,
            shift = false,
            keyCode = ShortcutKeyCode.Compose(Key.Escape),
            suppressUnmodified = false,
            onRefresh = onRefresh,
            toggleWindowFullscreen = toggleWindowFullscreen,
            pairingApprovalPending =
                DesktopDeviceConnect.manager.state.value.pairingRequests
                    .any { it.requiresApproval },
            cancelLyricsSelection = LyricsState.cancelSelection.takeUnless { isComposeModalActive() },
        )
    }
    val backgroundFocused by rememberUpdatedState(isPlaybackBackgroundFocused)
    val refresh by rememberUpdatedState(onRefresh)
    val toggleFullscreen by rememberUpdatedState(toggleWindowFullscreen)
    val forward by rememberUpdatedState(onForward)
    val clearFocus by rememberUpdatedState(clearPointerFocus)
    val keyFocus = remember { PlaybackKeyFocus() }
    val textInputInterceptor =
        remember(keyFocus) {
            PlatformTextInputInterceptor { request, next ->
                val session = DesktopTextInputSession()
                keyFocus.setTextInputActive(session, true)
                try {
                    next.startInputMethod(request)
                } finally {
                    keyFocus.setTextInputActive(session, false)
                }
            }
        }
    val useTaoWayland = WaylandScale.isNativeWaylandActive()
    if (!useTaoWayland) {
        DisposableEffect(Unit) {
            val dispatcher =
                installGlobalShortcutDispatcher(
                    keyFocus,
                    { backgroundFocused() },
                    { refresh() },
                    { toggleFullscreen() },
                    { forward() },
                    { clearFocus() },
                )
            onDispose { dispatcher.close() }
        }
    }

    val boxModifier =
        if (useTaoWayland) {
            // Matches the AWT mouse queue: a primary click leaves keyboard navigation.
            modifier
                .pointerInput(keyFocus) {
                    awaitPointerEventScope {
                        var forwardHeld = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // buttons is the held state, so only the transition to held counts as a forward press.
                            val forwardPressed = event.buttons.isForwardPressed && !forwardHeld
                            forwardHeld = event.buttons.isForwardPressed
                            if (event.type != PointerEventType.Press) continue
                            if (event.buttons.isPrimaryPressed) keyFocus.keyboardNavigation = false
                            // The AWT forward-button handler is not installed on Tao.
                            if (forwardPressed && !isComposeModalActive()) {
                                event.changes.forEach { it.consume() }
                                forward()
                            }
                        }
                    }
                }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key == Key.Escape) return@onPreviewKeyEvent false
                    if (event.key == Key.Tab ||
                        ((event.key == Key.DirectionUp || event.key == Key.DirectionDown) && !backgroundFocused())
                    ) {
                        keyFocus.keyboardNavigation = true
                    }
                    val editableInputActive = keyFocus.textInputActive || UiState.isTextInputFocused || UiState.isSearchFocused
                    val modalActive = isComposeModalActive()
                    if (event.key == Key.F5 && modalActive) return@onPreviewKeyEvent false
                    val pairingApprovalPending =
                        DesktopDeviceConnect.manager.state.value.pairingRequests
                            .any { it.requiresApproval }
                    handleShortcut(
                        ctrl = event.isCtrlPressed,
                        meta = event.isMetaPressed,
                        shift = event.isShiftPressed,
                        keyCode = ShortcutKeyCode.Compose(event.key),
                        suppressUnmodified = editableInputActive || modalActive,
                        modalActive = modalActive,
                        onRefresh = { refresh() },
                        toggleWindowFullscreen = { toggleFullscreen() },
                        pairingApprovalPending = pairingApprovalPending,
                        isPlaybackBackgroundFocused = backgroundFocused(),
                        mediaKeysAllowed = keyFocus.allowsMediaKeys(backgroundFocused()),
                        alt = event.isAltPressed,
                        cancelLyricsSelection = LyricsState.cancelSelection.takeUnless { modalActive },
                    )
                }
        } else {
            modifier
        }

    CompositionLocalProvider(LocalPlaybackKeyFocus provides keyFocus) {
        InterceptPlatformTextInput(
            interceptor = textInputInterceptor,
        ) {
            Box(modifier = boxModifier) { content() }
        }
    }
}

private fun installGlobalShortcutDispatcher(
    keyFocus: PlaybackKeyFocus,
    isPlaybackBackgroundFocused: () -> Boolean,
    onRefresh: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    onForward: () -> Unit,
    clearPointerFocus: () -> Unit,
): AutoCloseable {
    val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val toolkit = Toolkit.getDefaultToolkit()
    val pressed = ShortcutPressTracker()
    val physicalKeyCapture: PlatformPhysicalKeyCapture? =
        when {
            OsUtil.isWindows -> WindowsPhysicalKeyCapture()
            OsUtil.isMac -> MacOsPhysicalKeyCapture()
            OsUtil.isLinux && toolkit.javaClass.name == "sun.awt.X11.XToolkit" -> LinuxPhysicalKeyCapture()
            else -> null
        }

    fun updatePhysicalKeyCapture() {
        physicalKeyCapture?.setEnabled(keyboardFocusManager.activeWindow != null)
    }

    fun restorePointerFocus() {
        if (keyboardFocusManager.activeWindow != null && !keyFocus.keyboardNavigation && !keyFocus.retainsFocus &&
            !editableInputActive(keyboardFocusManager.focusOwner) && !isPlaybackModalActive()
        ) {
            clearPointerFocus()
        }
    }
    val focusListener =
        PropertyChangeListener {
            pressed.clear()
            updatePhysicalKeyCapture()
            EventQueue.invokeLater { restorePointerFocus() }
        }
    val dispatcher =
        KeyEventDispatcher { event ->
            if (event.id == AwtKeyEvent.KEY_PRESSED && (
                    event.keyCode == AwtKeyEvent.VK_TAB ||
                        (event.keyCode in setOf(AwtKeyEvent.VK_UP, AwtKeyEvent.VK_DOWN) && !isPlaybackBackgroundFocused())
                )
            ) {
                keyFocus.keyboardNavigation = true
            }
            val physicalCaptureAvailable = physicalKeyCapture?.isAvailable == true
            val isModifierPress =
                event.id == AwtKeyEvent.KEY_PRESSED && event.keyCode.isAwtModifierKey()
            val physicalCapture =
                if (physicalCaptureAvailable && event.id == AwtKeyEvent.KEY_PRESSED && !isModifierPress) {
                    physicalKeyCapture?.consume(event)
                } else {
                    null
                }
            val physicalPosition = physicalCapture?.position
            val physicalPositionsAvailable = physicalCaptureAvailable && physicalCapture != null
            pressed.dispatch(event, physicalPosition, physicalPositionsAvailable) {
                handleAwtKeyEvent(
                    event,
                    isPlaybackBackgroundFocused(),
                    keyFocus,
                    onRefresh,
                    toggleWindowFullscreen,
                    physicalPosition,
                    physicalPositionsAvailable = physicalPositionsAvailable,
                )
            }
        }
    val backButton = backMouseButton(toolkit.javaClass.name)
    val forwardButton = forwardMouseButton(toolkit.javaClass.name)
    val mouseEventQueue =
        object : EventQueue() {
            override fun dispatchEvent(event: AWTEvent) {
                if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED && event.button == MouseEvent.BUTTON1) {
                    keyFocus.keyboardNavigation = false
                }
                if (event is MouseEvent) {
                    val scrollEvent = desktopScrollEvent(event, toolkit.javaClass.name) ?: return
                    if (scrollEvent !== event) {
                        super.dispatchEvent(scrollEvent)
                        return
                    }
                }
                // Compose handles consumed AWT events; replace Back before it can also dismiss a popup.
                if (event is MouseEvent &&
                    handleBackMouseEvent(event, keyboardFocusManager.focusOwner, backButton) { postEvent(it) }
                ) {
                    return
                }
                if (event is MouseEvent &&
                    handleForwardMouseEvent(
                        event,
                        keyboardFocusManager.focusOwner,
                        forwardButton,
                        isModalActive = { keyboardFocusManager.activeWindow is Dialog || isComposeModalActive() },
                        onForward = onForward,
                    )
                ) {
                    return
                }
                if (OsUtil.isWindows) {
                    DesktopWebViewAvailability.shared.dispatchEvent { super.dispatchEvent(event) }
                } else {
                    super.dispatchEvent(event)
                }
                if (event is MouseEvent && event.id == MouseEvent.MOUSE_RELEASED && event.button == MouseEvent.BUTTON1) {
                    EventQueue.invokeLater { restorePointerFocus() }
                }
            }

            fun close() = pop()
        }

    keyboardFocusManager.addPropertyChangeListener("activeWindow", focusListener)
    keyboardFocusManager.addKeyEventDispatcher(dispatcher)
    updatePhysicalKeyCapture()
    toolkit.systemEventQueue.push(mouseEventQueue)

    return AutoCloseable {
        mouseEventQueue.close()
        keyboardFocusManager.removeKeyEventDispatcher(dispatcher)
        keyboardFocusManager.removePropertyChangeListener("activeWindow", focusListener)
        physicalKeyCapture?.close()
    }
}

// X11 reserves buttons 4-7 for scrolling; AWT removes only vertical wheel buttons 4/5.
internal fun backMouseButton(toolkitClassName: String): Int = if (toolkitClassName == "sun.awt.X11.XToolkit") 6 else 4

internal fun forwardMouseButton(toolkitClassName: String): Int = if (toolkitClassName == "sun.awt.X11.XToolkit") 7 else 5

internal fun handleForwardMouseEvent(
    event: MouseEvent,
    focusOwner: Component?,
    forwardButton: Int,
    isModalActive: () -> Boolean,
    onForward: () -> Unit,
): Boolean {
    if (event.isConsumed || event.button != forwardButton || focusOwner == null) return false
    if (event.id !in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) return false
    event.consume()
    if (event.id == MouseEvent.MOUSE_PRESSED && !isModalActive()) onForward()
    return true
}

internal fun handleBackMouseEvent(
    event: MouseEvent,
    focusOwner: Component?,
    backButton: Int = 4,
    dispatchKeyEvent: (AwtKeyEvent) -> Unit,
): Boolean {
    if (event.isConsumed || event.button != backButton || focusOwner == null) return false
    if (event.id !in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) return false
    event.consume()
    if (event.id == MouseEvent.MOUSE_PRESSED) {
        for (id in listOf(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.KEY_RELEASED)) {
            dispatchKeyEvent(AwtKeyEvent(focusOwner, id, event.`when`, 0, AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.CHAR_UNDEFINED))
        }
    }
    return true
}

internal fun shouldDispatchGlobalShortcut(event: AwtKeyEvent): Boolean =
    !event.isConsumed && event.id == AwtKeyEvent.KEY_PRESSED && event.keyCode != AwtKeyEvent.VK_ESCAPE

private fun Int.isAwtModifierKey(): Boolean =
    this == AwtKeyEvent.VK_SHIFT ||
        this == AwtKeyEvent.VK_CONTROL ||
        this == AwtKeyEvent.VK_ALT ||
        this == AwtKeyEvent.VK_META ||
        this == AwtKeyEvent.VK_ALT_GRAPH ||
        this == AwtKeyEvent.VK_WINDOWS ||
        this == AwtKeyEvent.VK_CAPS_LOCK ||
        this == AwtKeyEvent.VK_KANA_LOCK

private fun handleAwtKeyEvent(
    event: AwtKeyEvent,
    isPlaybackBackgroundFocused: Boolean,
    keyFocus: PlaybackKeyFocus,
    onRefresh: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    physicalPosition: PhysicalKeyPosition?,
    physicalPositionsAvailable: Boolean,
): Boolean {
    if (!shouldDispatchGlobalShortcut(event)) return false
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val owner = focusManager.focusOwner
    val editableInputActive = editableInputActive(owner) || keyFocus.textInputActive
    val pairingApprovalPending =
        DesktopDeviceConnect.manager.state.value.pairingRequests
            .any { it.requiresApproval }
    val mediaKey =
        !event.isControlDown && !event.isMetaDown && !event.isAltDown && !event.isShiftDown &&
            event.keyCode in setOf(AwtKeyEvent.VK_SPACE, AwtKeyEvent.VK_LEFT, AwtKeyEvent.VK_RIGHT)
    val modalActive = if (mediaKey) isPlaybackModalActive() else focusManager.activeWindow is Dialog || isComposeModalActive()
    if (event.keyCode == AwtKeyEvent.VK_F5 && modalActive) return false
    return handleShortcut(
        ctrl = event.isControlDown,
        meta = event.isMetaDown,
        shift = event.isShiftDown,
        keyCode =
            ShortcutKeyCode.Awt(
                event.keyCode,
                normalizeControlCharacter(event.keyChar, event.isControlDown),
                physicalPosition,
                physicalPositionsAvailable,
            ),
        suppressUnmodified = editableInputActive || modalActive,
        modalActive = modalActive,
        onRefresh = onRefresh,
        toggleWindowFullscreen = toggleWindowFullscreen,
        pairingApprovalPending = pairingApprovalPending,
        isPlaybackBackgroundFocused = isPlaybackBackgroundFocused,
        mediaKeysAllowed = keyFocus.allowsMediaKeys(isPlaybackBackgroundFocused),
        alt = event.isAltDown,
        cancelLyricsSelection = LyricsState.cancelSelection.takeUnless { modalActive },
    )
}

private fun editableInputActive(owner: Component?): Boolean =
    (owner as? JTextComponent)?.isEditable == true || owner?.inputMethodRequests != null ||
        UiState.isTextInputFocused || UiState.isSearchFocused

private fun isPlaybackModalActive(): Boolean {
    val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    return window is Dialog || window?.type == Window.Type.POPUP || UiState.showFloatingSearch || UiState.showShortcutsDialog ||
        UiState.showSignIn || UiState.showSwitchChannel || UiState.showTokenLogin || UiState.showLogViewer ||
        (UiState.showDeviceConnectPanel && UiState.isDeviceConnectPanelModal) || PlayerState.showAddToPlaylistSong != null ||
        PlayerState.showPlayerError
}

private fun isPrimaryModifier(
    ctrl: Boolean,
    meta: Boolean,
): Boolean = if (OsUtil.isMac) meta && !ctrl else ctrl || meta

internal fun handleShortcut(
    ctrl: Boolean,
    meta: Boolean,
    shift: Boolean,
    keyCode: ShortcutKeyCode,
    suppressUnmodified: Boolean,
    onRefresh: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    pairingApprovalPending: Boolean = false,
    isPlaybackBackgroundFocused: Boolean = false,
    mediaKeysAllowed: Boolean = isPlaybackBackgroundFocused,
    alt: Boolean = false,
    cancelLyricsSelection: (() -> Unit)? = null,
    modalActive: Boolean = isComposeModalActive(),
): Boolean {
    if (pairingApprovalPending) return false
    val primary = isPrimaryModifier(ctrl, meta)
    if (modalActive && !keyCodeMatches(keyCode, Key.Escape, AwtKeyEvent.VK_ESCAPE)) {
        if (primary && UiState.showFloatingSearch &&
            (keyCodeMatches(keyCode, Key.K, AwtKeyEvent.VK_K) || keyCodeMatches(keyCode, Key.F, AwtKeyEvent.VK_F))
        ) {
            UiState.showFloatingSearch = false
            return true
        }
        if (primary && UiState.showShortcutsDialog &&
            (keyCodeMatches(keyCode, Key.Grave, AwtKeyEvent.VK_BACK_QUOTE) || keyCode.isAwtCode(AwtKeyEvent.VK_DEAD_GRAVE))
        ) {
            UiState.showShortcutsDialog = false
            return true
        }
        return false
    }

    if (primary && keyCodeMatches(keyCode, Key.K, AwtKeyEvent.VK_K)) {
        triggerSearchShortcut()
        return true
    }

    if (primary && keyCodeMatches(keyCode, Key.F, AwtKeyEvent.VK_F)) {
        triggerSearchShortcut()
        return true
    }

    if (primary && !alt && keyCodeMatches(keyCode, Key.Slash, AwtKeyEvent.VK_SLASH)) {
        UiState.showShortcutsDialog = true
        return true
    }

    if (primary && shift && keyCodeMatches(keyCode, Key.L, AwtKeyEvent.VK_L)) {
        UiState.showLogViewer = true
        return true
    }

    if (primary && keyCodeMatches(keyCode, Key.Comma, AwtKeyEvent.VK_COMMA)) {
        UiState.showSettings = true
        return true
    }

    if (primary &&
        (keyCodeMatches(keyCode, Key.Grave, AwtKeyEvent.VK_BACK_QUOTE) || keyCode.isAwtCode(AwtKeyEvent.VK_DEAD_GRAVE))
    ) {
        UiState.showShortcutsDialog = !UiState.showShortcutsDialog
        return true
    }

    if (keyCodeMatches(keyCode, Key.Escape, AwtKeyEvent.VK_ESCAPE)) {
        cancelLyricsSelection?.let { cancel ->
            cancel()
            return true
        }
        return when {
            UiState.showShortcutsDialog -> {
                UiState.showShortcutsDialog = false
                true
            }

            UiState.showFloatingSearch -> {
                UiState.showFloatingSearch = false
                true
            }

            UiState.showDeviceConnectPanel -> {
                UiState.showDeviceConnectPanel = false
                true
            }

            UiState.showVideoStatsForNerds -> {
                UiState.showVideoStatsForNerds = false
                true
            }

            UiState.showVideoFullscreenQueue -> {
                UiState.showVideoFullscreenQueue = false
                true
            }

            UiState.showVideoFullscreenLyrics -> {
                UiState.showVideoFullscreenLyrics = false
                true
            }

            PlayerState.isFullscreen || UiState.isNativeFullscreen -> {
                toggleWindowFullscreen()
                true
            }

            UiState.showVideoFullscreen -> {
                UiState.showVideoFullscreen = false
                true
            }

            PlayerState.isExpanded -> {
                PlayerState.exitExpanded()
                true
            }

            UiState.showVideoViewer -> {
                PlayerState.closeVideoViewer()
                true
            }

            else -> {
                DesktopNavigation.goBack()
            }
        }
    }

    if (!ctrl && !meta && !shift && keyCodeMatches(keyCode, Key.F5, AwtKeyEvent.VK_F5)) {
        onRefresh()
        return true
    }

    if (suppressUnmodified) return false

    if (primary && !shift && !alt) {
        when {
            keyCodeMatches(keyCode, Key.DirectionRight, AwtKeyEvent.VK_RIGHT) -> {
                PlayerState.skipNext()
                return true
            }

            keyCodeMatches(keyCode, Key.DirectionLeft, AwtKeyEvent.VK_LEFT) -> {
                PlayerState.skipPrevious()
                return true
            }
        }
    }
    val shiftedPlus =
        shift && (keyCodeMatches(keyCode, Key.Equals, AwtKeyEvent.VK_EQUALS) || keyCodeMatches(keyCode, Key.Plus, AwtKeyEvent.VK_ADD))
    if (ctrl || meta || alt || (shift && !shiftedPlus)) return false

    val isMediaKey =
        keyCodeMatches(keyCode, Key.Spacebar, AwtKeyEvent.VK_SPACE) ||
            keyCodeMatches(keyCode, Key.DirectionLeft, AwtKeyEvent.VK_LEFT) ||
            keyCodeMatches(keyCode, Key.DirectionRight, AwtKeyEvent.VK_RIGHT)
    if (isMediaKey && !mediaKeysAllowed) return false
    val isVolumeArrow =
        keyCodeMatches(keyCode, Key.DirectionUp, AwtKeyEvent.VK_UP) ||
            keyCodeMatches(keyCode, Key.DirectionDown, AwtKeyEvent.VK_DOWN)
    if (isVolumeArrow && !isPlaybackBackgroundFocused) return false

    fun seekToTenth(tenth: Int) {
        if (PlayerState.currentTrack == null) return
        val durationMs = PlayerState.playbackDuration.value
        if (durationMs <= 0L) return
        PlayerState.seekToPosition(durationMs / 10L * tenth)
    }

    return when {
        !ctrl && !meta && !shift && keyCodeMatches(keyCode, Key.D, AwtKeyEvent.VK_D) -> {
            UiState.toggleDeviceConnectPanel()
            true
        }

        keyCodeMatches(keyCode, Key.Slash, AwtKeyEvent.VK_SLASH) -> {
            triggerSearchShortcut()
            true
        }

        keyCodeMatches(keyCode, Key.Spacebar, AwtKeyEvent.VK_SPACE) -> {
            PlayerState.togglePlay()
            true
        }

        keyCodeMatches(keyCode, Key.DirectionRight, AwtKeyEvent.VK_RIGHT) -> {
            PlayerState.seekRelative(Settings.shared.seekDurationSeconds * 1000L)
            true
        }

        keyCodeMatches(keyCode, Key.DirectionLeft, AwtKeyEvent.VK_LEFT) -> {
            PlayerState.seekRelative(-Settings.shared.seekDurationSeconds * 1000L)
            true
        }

        keyCodeMatches(keyCode, Key.M, AwtKeyEvent.VK_M) -> {
            if (PlayerState.volume > 0f) {
                PlayerState.previousVolume = PlayerState.volume
                PlayerState.setVolumeLevel(0f)
            } else {
                PlayerState.setVolumeLevel(PlayerState.previousVolume)
            }
            true
        }

        keyCodeMatches(keyCode, Key.S, AwtKeyEvent.VK_S) -> {
            PlayerState.toggleShuffle()
            true
        }

        keyCodeMatches(keyCode, Key.R, AwtKeyEvent.VK_R) -> {
            PlayerState.cycleRepeatMode()
            true
        }

        keyCodeMatches(keyCode, Key.C, AwtKeyEvent.VK_C) -> {
            PlayerState.isSubtitleActive = !PlayerState.isSubtitleActive
            if (PlayerState.isSubtitleActive && LyricsState.currentLyricsEntries.isEmpty()) {
                PlayerState.currentTrack?.let(LyricsState::fetchLyrics)
            }
            true
        }

        keyCodeMatches(keyCode, Key.V, AwtKeyEvent.VK_V) -> {
            if (!Settings.shared.musicVideoPlaybackEnabled) return true
            if (PlayerState.isVideoActive) {
                PlayerState.closeVideoViewer()
            } else {
                PlayerState.requestVideoMode()
                UiState.showVideoFullscreen = PlayerState.isExpanded
            }
            true
        }

        keyCodeMatches(keyCode, Key.T, AwtKeyEvent.VK_T) -> {
            PlayerState.toggleExpandedMode()
            if (UiState.showVideoViewer && (PlayerState.isVideoActive || PlayerState.isVideoAvailable)) {
                UiState.showVideoFullscreen = PlayerState.isExpanded
            }
            true
        }

        keyCodeMatches(keyCode, Key.F, AwtKeyEvent.VK_F) -> {
            toggleWindowFullscreen()
            true
        }

        keyCodeMatches(keyCode, Key.L, AwtKeyEvent.VK_L) -> {
            if (UiState.showVideoFullscreen) {
                val showLyrics = !UiState.showVideoFullscreenLyrics
                UiState.showVideoFullscreenLyrics = showLyrics
                if (showLyrics && LyricsState.currentLyricsEntries.isEmpty()) {
                    PlayerState.currentTrack?.let(LyricsState::fetchLyrics)
                }
            } else {
                LyricsState.toggleLyricsDrawer()
            }
            true
        }

        keyCodeMatches(keyCode, Key.Q, AwtKeyEvent.VK_Q) -> {
            if (UiState.showVideoFullscreen) {
                val showQueue = !UiState.showVideoFullscreenQueue
                UiState.showVideoFullscreenQueue = showQueue
            } else {
                UiState.togglePersistentPlayerDrawer()
            }
            true
        }

        keyCodeMatches(keyCode, Key.Equals, AwtKeyEvent.VK_EQUALS) ||
            keyCodeMatches(keyCode, Key.Plus, AwtKeyEvent.VK_ADD) -> {
            PlayerState.setVolumeLevel((PlayerState.volume + 0.1f).coerceIn(0f, 1f))
            true
        }

        isVolumeDecreaseShortcut(keyCode) -> {
            PlayerState.setVolumeLevel((PlayerState.volume - 0.1f).coerceIn(0f, 1f))
            true
        }

        keyCodeMatches(keyCode, Key.DirectionUp, AwtKeyEvent.VK_UP) -> {
            PlayerState.setVolumeLevel((PlayerState.volume + 0.1f).coerceIn(0f, 1f))
            true
        }

        keyCodeMatches(keyCode, Key.DirectionDown, AwtKeyEvent.VK_DOWN) -> {
            PlayerState.setVolumeLevel((PlayerState.volume - 0.1f).coerceIn(0f, 1f))
            true
        }

        keyCodeMatches(keyCode, Key.Zero, AwtKeyEvent.VK_0) -> {
            if (PlayerState.currentTrack != null) {
                PlayerState.seekToPosition(0L)
            }
            true
        }

        keyCodeMatches(keyCode, Key.One, AwtKeyEvent.VK_1) -> {
            seekToTenth(1)
            true
        }

        keyCodeMatches(keyCode, Key.Two, AwtKeyEvent.VK_2) -> {
            seekToTenth(2)
            true
        }

        keyCodeMatches(keyCode, Key.Three, AwtKeyEvent.VK_3) -> {
            seekToTenth(3)
            true
        }

        keyCodeMatches(keyCode, Key.Four, AwtKeyEvent.VK_4) -> {
            seekToTenth(4)
            true
        }

        keyCodeMatches(keyCode, Key.Five, AwtKeyEvent.VK_5) -> {
            seekToTenth(5)
            true
        }

        keyCodeMatches(keyCode, Key.Six, AwtKeyEvent.VK_6) -> {
            seekToTenth(6)
            true
        }

        keyCodeMatches(keyCode, Key.Seven, AwtKeyEvent.VK_7) -> {
            seekToTenth(7)
            true
        }

        keyCodeMatches(keyCode, Key.Eight, AwtKeyEvent.VK_8) -> {
            seekToTenth(8)
            true
        }

        keyCodeMatches(keyCode, Key.Nine, AwtKeyEvent.VK_9) -> {
            seekToTenth(9)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad0, AwtKeyEvent.VK_NUMPAD0) -> {
            if (PlayerState.currentTrack != null) {
                PlayerState.seekToPosition(0L)
            }
            true
        }

        keyCodeMatches(keyCode, Key.NumPad1, AwtKeyEvent.VK_NUMPAD1) -> {
            seekToTenth(1)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad2, AwtKeyEvent.VK_NUMPAD2) -> {
            seekToTenth(2)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad3, AwtKeyEvent.VK_NUMPAD3) -> {
            seekToTenth(3)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad4, AwtKeyEvent.VK_NUMPAD4) -> {
            seekToTenth(4)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad5, AwtKeyEvent.VK_NUMPAD5) -> {
            seekToTenth(5)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad6, AwtKeyEvent.VK_NUMPAD6) -> {
            seekToTenth(6)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad7, AwtKeyEvent.VK_NUMPAD7) -> {
            seekToTenth(7)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad8, AwtKeyEvent.VK_NUMPAD8) -> {
            seekToTenth(8)
            true
        }

        keyCodeMatches(keyCode, Key.NumPad9, AwtKeyEvent.VK_NUMPAD9) -> {
            seekToTenth(9)
            true
        }

        keyCodeMatches(keyCode, Key.N, AwtKeyEvent.VK_N) -> {
            PlayerState.skipNext()
            true
        }

        keyCodeMatches(keyCode, Key.P, AwtKeyEvent.VK_P) -> {
            PlayerState.skipPrevious()
            true
        }

        else -> {
            false
        }
    }
}

private class DesktopTextInputSession

private fun isComposeModalActive(): Boolean =
    UiState.showShortcutsDialog ||
        UiState.showSettings ||
        UiState.showSignIn ||
        UiState.showSwitchChannel ||
        UiState.showLogViewer ||
        UiState.showTokenLogin ||
        UiState.showFloatingSearch ||
        (UiState.showDeviceConnectPanel && UiState.isDeviceConnectPanelModal) ||
        UiState.showAppearanceSettings ||
        UiState.showThemeSettings ||
        UiState.showSliderStyleDialog ||
        UiState.showPlayerBackgroundStyleDialog ||
        UiState.showLayoutDialog ||
        UiState.showPlayerSettings ||
        UiState.showContentSettings ||
        UiState.showLyricsSettings ||
        UiState.showPrivacySettings ||
        PlayerState.showAddToPlaylistSong != null ||
        PlayerState.showPlayerError

internal fun isVolumeDecreaseShortcut(keyCode: ShortcutKeyCode): Boolean =
    keyCodeMatches(keyCode, Key.Minus, AwtKeyEvent.VK_MINUS) ||
        keyCodeMatches(keyCode, Key.NumPadSubtract, AwtKeyEvent.VK_SUBTRACT)

internal fun keyCodeMatches(
    keyCode: ShortcutKeyCode,
    composeKey: Key,
    awtKeyCode: Int,
): Boolean =
    when (keyCode) {
        is ShortcutKeyCode.Compose -> {
            keyCode.key == composeKey
        }

        is ShortcutKeyCode.Awt -> {
            val physicalPosition = physicalPositionForAwtCode(awtKeyCode)
            if (physicalPosition != null && keyCode.physicalPositionsAvailable) {
                keyCode.physicalPosition == physicalPosition
            } else if (awtKeyCode in AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z) {
                keyCode.character.uppercaseChar().code == awtKeyCode
            } else {
                keyCode.code == awtKeyCode
            }
        }
    }

internal fun normalizeControlCharacter(
    character: Char,
    controlDown: Boolean,
): Char =
    if (controlDown && character.code in 1..26) {
        (character.code + 'A'.code - 1).toChar()
    } else {
        character
    }

private fun ShortcutKeyCode.isAwtCode(expectedCode: Int): Boolean {
    val awtKey = this as? ShortcutKeyCode.Awt ?: return false
    val physicalPosition = physicalPositionForAwtCode(expectedCode)
    return if (physicalPosition != null && awtKey.physicalPositionsAvailable) {
        awtKey.physicalPosition == physicalPosition
    } else {
        awtKey.code == expectedCode
    }
}

internal enum class PhysicalKeyPosition { BACKQUOTE, MINUS, EQUALS, NUMPAD_PLUS, COMMA, SLASH }

internal fun physicalPositionForAwtCode(awtKeyCode: Int): PhysicalKeyPosition? =
    when (awtKeyCode) {
        AwtKeyEvent.VK_BACK_QUOTE, AwtKeyEvent.VK_DEAD_GRAVE -> PhysicalKeyPosition.BACKQUOTE
        AwtKeyEvent.VK_MINUS -> PhysicalKeyPosition.MINUS
        AwtKeyEvent.VK_EQUALS -> PhysicalKeyPosition.EQUALS
        AwtKeyEvent.VK_ADD -> PhysicalKeyPosition.NUMPAD_PLUS
        AwtKeyEvent.VK_COMMA -> PhysicalKeyPosition.COMMA
        AwtKeyEvent.VK_SLASH -> PhysicalKeyPosition.SLASH
        else -> null
    }

internal fun windowsPhysicalPositionForScanCode(
    scanCode: Int,
    flags: Int = 0,
): PhysicalKeyPosition? =
    when {
        scanCode and 0xFF == 0x35 && flags and WINDOWS_LOW_LEVEL_EXTENDED_KEY_FLAG != 0 -> null
        scanCode and 0xFF == 0x29 -> PhysicalKeyPosition.BACKQUOTE
        scanCode and 0xFF == 0x0C -> PhysicalKeyPosition.MINUS
        scanCode and 0xFF == 0x0D -> PhysicalKeyPosition.EQUALS
        scanCode and 0xFF == 0x4E -> PhysicalKeyPosition.NUMPAD_PLUS
        scanCode and 0xFF == 0x33 -> PhysicalKeyPosition.COMMA
        scanCode and 0xFF == 0x35 -> PhysicalKeyPosition.SLASH
        else -> null
    }

internal class ShortcutPressTracker {
    private val handled = mutableSetOf<Int>()

    fun clear() = handled.clear()

    fun dispatch(
        event: AwtKeyEvent,
        physicalPosition: PhysicalKeyPosition?,
        physicalPositionsAvailable: Boolean,
        action: () -> Boolean,
    ): Boolean {
        if (event.id == AwtKeyEvent.KEY_RELEASED) {
            return handled.remove(event.keyCode)
        }
        if (event.id != AwtKeyEvent.KEY_PRESSED) return false
        if (event.keyCode in handled) return true
        val consumed = action()
        // Layouts can report a physical slash as VK_MINUS, so repeat follows the captured position when known.
        val repeatable =
            if (physicalPositionsAvailable && physicalPosition != null) {
                physicalPosition in REPEATABLE_PHYSICAL_POSITIONS
            } else {
                event.keyCode in REPEATABLE_AWT_KEY_CODES
            }
        if (consumed && !repeatable) handled.add(event.keyCode)
        return consumed
    }

    private companion object {
        val REPEATABLE_PHYSICAL_POSITIONS =
            setOf(
                PhysicalKeyPosition.MINUS,
                PhysicalKeyPosition.EQUALS,
                PhysicalKeyPosition.NUMPAD_PLUS,
            )
        val REPEATABLE_AWT_KEY_CODES =
            setOf(
                AwtKeyEvent.VK_LEFT,
                AwtKeyEvent.VK_RIGHT,
                AwtKeyEvent.VK_UP,
                AwtKeyEvent.VK_DOWN,
                AwtKeyEvent.VK_EQUALS,
                AwtKeyEvent.VK_ADD,
                AwtKeyEvent.VK_MINUS,
                AwtKeyEvent.VK_SUBTRACT,
            )
    }
}
